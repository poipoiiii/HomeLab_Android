"""推理后端适配器。

- `BACKEND=comfyui`：调用本机已跑起来的 ComfyUI（GGUF 量化版 Qwen-Image-Edit-2511），
  24GB 显存的 AMD 卡上这是唯一能装下的路线。
- `BACKEND=diffusers`：本机 ROCm + `QwenImageEditPlusPipeline`（需要足够内存放下 bf16 权重）。
- `BACKEND=mock`：不加载模型，返回带水印的占位图，只用于链路联调。
"""

from __future__ import annotations

import io
import logging
import os
import random
import threading
import time
import uuid
from typing import Protocol

import httpx
from PIL import Image, ImageDraw

log = logging.getLogger("backend")


class Backend(Protocol):
    name: str
    model: str

    def ready(self) -> bool: ...

    def status(self) -> str: ...

    def generate(
        self,
        images: list[Image.Image],
        prompt: str,
        *,
        steps: int,
        guidance: float,
        negative: str,
        seed: int | None,
        width: int,
        height: int,
    ) -> Image.Image: ...

    def vram_used_mb(self) -> int | None: ...


class MockBackend:
    name = "mock"

    def __init__(self, model: str) -> None:
        self.model = model

    def ready(self) -> bool:
        return True

    def status(self) -> str:
        return "mock"

    def generate(self, images, prompt, *, steps, guidance, negative, seed, width, height):
        base = images[0].convert("RGB").resize((width, height), Image.LANCZOS)
        canvas = Image.new("RGB", (width, height), (250, 249, 245))
        canvas.paste(base, (0, 0))
        overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)
        draw.rectangle([0, height - 56, width, height], fill=(20, 20, 19, 190))
        draw.text((16, height - 40), "MOCK BACKEND · 未加载模型", fill=(255, 255, 255, 255))
        return Image.alpha_composite(canvas.convert("RGBA"), overlay).convert("RGB")

    def vram_used_mb(self) -> int | None:
        return None


DTYPES = {
    "bf16": "bfloat16",
    "fp16": "float16",
    "fp8": "float8_e4m3fn",
    "fp32": "float32",
}


class DiffusersBackend:
    """ROCm 下的 diffusers 后端。首次 generate 时才加载权重，避免挡住 /health。"""

    name = "diffusers"

    def __init__(self) -> None:
        self.model = os.getenv("MODEL_PATH") or os.getenv("MODEL_NAME", "Qwen/Qwen-Image-Edit-2511")
        self.dtype = os.getenv("MODEL_DTYPE", "bf16").lower()
        self.offload = os.getenv("MODEL_OFFLOAD", "model").lower()
        self.device = os.getenv("MODEL_DEVICE", "cuda")
        self._pipe = None
        self._lock = threading.Lock()
        self._state = "warming_up"
        self._error = ""

    def ready(self) -> bool:
        return self._pipe is not None

    def status(self) -> str:
        if self._state == "failed":
            return f"failed: {self._error}"
        return self._state

    # ---------------------------------------------------------------- 加载
    def _load(self) -> None:
        import torch
        from diffusers import QwenImageEditPlusPipeline

        dtype_name = DTYPES.get(self.dtype)
        if dtype_name is None:
            raise RuntimeError(f"MODEL_DTYPE 只能是 {', '.join(DTYPES)}")
        dtype = getattr(torch, dtype_name)
        log.info("加载模型 %s（dtype=%s, offload=%s）", self.model, self.dtype, self.offload)
        pipe = QwenImageEditPlusPipeline.from_pretrained(
            self.model,
            torch_dtype=dtype,
            low_cpu_mem_usage=True,
        )
        if self.offload == "sequential":
            pipe.enable_sequential_cpu_offload()
        elif self.offload == "model":
            pipe.enable_model_cpu_offload()
        else:
            pipe.to(self.device)
        pipe.enable_vae_slicing()
        pipe.enable_vae_tiling()
        if hasattr(pipe, "enable_attention_slicing"):
            pipe.enable_attention_slicing()
        self._pipe = pipe
        self._state = "ok"
        log.info("模型就绪")

    def _ensure(self):
        if self._pipe is not None:
            return self._pipe
        with self._lock:
            if self._pipe is None:
                try:
                    self._load()
                except Exception as error:  # noqa: BLE001
                    self._state = "failed"
                    self._error = str(error)[:300]
                    raise
        return self._pipe

    # ---------------------------------------------------------------- 推理
    def generate(self, images, prompt, *, steps, guidance, negative, seed, width, height):
        import torch

        pipe = self._ensure()
        generator = None
        if seed is not None:
            generator = torch.Generator(device="cpu").manual_seed(int(seed))
        self._state = "running"
        try:
            result = pipe(
                image=images,
                prompt=prompt,
                negative_prompt=negative or None,
                num_inference_steps=steps,
                guidance_scale=guidance,
                width=width,
                height=height,
                generator=generator,
            )
        finally:
            self._state = "ok"
        return result.images[0]

    def vram_used_mb(self) -> int | None:
        try:
            import torch

            if not torch.cuda.is_available():
                return None
            free, total = torch.cuda.mem_get_info()
            return int((total - free) / (1024 * 1024))
        except Exception:  # noqa: BLE001
            return None


class ComfyUIBackend:
    """通过 ComfyUI 的 HTTP API 出图。

    ComfyUI 已经在跑（127.0.0.1:8188），这里只负责：上传参考图 → 提交 workflow → 轮询 history → 取回 PNG。
    workflow 用 Qwen-Image-Edit-Plus 节点 + GGUF 量化模型，CFG 默认 1.0（该系列是 guidance-distilled）。
    """

    name = "comfyui"

    def __init__(self) -> None:
        self.model = os.getenv("MODEL_NAME", "Qwen/Qwen-Image-Edit-2511")
        self.url = (os.getenv("COMFY_URL") or "http://127.0.0.1:8188").rstrip("/")
        self.unet = os.getenv("COMFY_UNET", "qwen-image-edit-2511-Q4_K_M.gguf")
        self.clip = os.getenv("COMFY_CLIP", "qwen_2.5_vl_7b_fp8_scaled.safetensors")
        self.vae = os.getenv("COMFY_VAE", "qwen_image_vae.safetensors")
        self.shift = float(os.getenv("COMFY_SHIFT", "3.1"))
        self.sampler = os.getenv("COMFY_SAMPLER", "euler")
        self.scheduler = os.getenv("COMFY_SCHEDULER", "simple")
        self.timeout = int(os.getenv("COMFY_TIMEOUT", "900"))
        self.client_id = uuid.uuid4().hex
        self._state = "warming_up"
        self._error = ""

    def ready(self) -> bool:
        return self._state == "ok"

    def status(self) -> str:
        try:
            with httpx.Client(timeout=8.0) as client:
                client.get(self.url + "/system_stats").raise_for_status()
            self._state = "ok"
            self._error = ""
            return "ok"
        except (httpx.ConnectError, httpx.ConnectTimeout) as error:
            self._state = "failed"
            self._error = str(error)[:200]
            return f"failed: {self._error}"
        except Exception:  # noqa: BLE001
            # 出图时 ComfyUI 主线程很忙，/system_stats 偶发超时；不据此判定离线
            return self._state

    # ---------------------------------------------------------------- 推理
    def generate(self, images, prompt, *, steps, guidance, negative, seed, width, height):
        seed_value = int(seed) if seed is not None else random.randint(0, 2**31 - 1)
        timeout = httpx.Timeout(120.0, connect=10.0)
        try:
            with httpx.Client(base_url=self.url, timeout=timeout) as client:
                refs = [self._upload(client, image, index) for index, image in enumerate(images[:3])]
                workflow = self._workflow(
                    prompt=prompt,
                    refs=refs,
                    steps=int(steps),
                    guidance=float(guidance),
                    seed=seed_value,
                    width=int(width),
                    height=int(height),
                )
                response = client.post(
                    "/prompt",
                    json={"prompt": workflow, "client_id": self.client_id},
                )
                if response.status_code >= 400:
                    raise RuntimeError(f"ComfyUI 拒绝了任务：{response.text[:300]}")
                prompt_id = response.json()["prompt_id"]
                log.info("ComfyUI 任务 %s（%dx%d, steps=%d, seed=%d）", prompt_id, width, height, steps, seed_value)
                return self._wait(client, prompt_id)
        except (httpx.ConnectError, httpx.ConnectTimeout) as error:
            raise RuntimeError(f"连不上 ComfyUI（{self.url}），检查 comfyui.service 是否在运行") from error

    def _upload(self, client: httpx.Client, image: Image.Image, index: int) -> dict:
        buffer = io.BytesIO()
        image.convert("RGB").save(buffer, format="PNG")
        name = f"gw_{uuid.uuid4().hex[:10]}_{index}.png"
        response = client.post(
            "/upload/image",
            files={"image": (name, buffer.getvalue(), "image/png")},
            data={"overwrite": "true", "subfolder": "img-edit"},
        )
        response.raise_for_status()
        info = response.json()
        return {"name": info.get("name") or name, "subfolder": info.get("subfolder") or ""}

    def _wait(self, client: httpx.Client, prompt_id: str) -> Image.Image:
        deadline = time.time() + self.timeout
        while time.time() < deadline:
            history = client.get(f"/history/{prompt_id}").json()
            entry = history.get(prompt_id)
            if entry:
                status = entry.get("status") or {}
                if status.get("status_str") == "error":
                    raise RuntimeError("ComfyUI 执行失败：" + str(status.get("messages"))[:300])
                for output in (entry.get("outputs") or {}).values():
                    for item in output.get("images") or []:
                        if item.get("type") == "output":
                            return self._fetch(client, item)
                if status.get("completed"):
                    raise RuntimeError("ComfyUI 执行完成但没返回图片")
            time.sleep(2)
        raise TimeoutError("ComfyUI 出图超时（%d 秒）" % self.timeout)

    def _fetch(self, client: httpx.Client, item: dict) -> Image.Image:
        response = client.get(
            "/view",
            params={
                "filename": item.get("filename", ""),
                "subfolder": item.get("subfolder", ""),
                "type": item.get("type", "output"),
            },
        )
        response.raise_for_status()
        return Image.open(io.BytesIO(response.content)).convert("RGB")

    def _workflow(self, *, prompt, refs, steps, guidance, seed, width, height) -> dict:
        # Qwen 的 VAE 是 8 倍下采样，潜空间尺寸必须是 8 的倍数
        width = max(256, min(2048, round(width / 8) * 8))
        height = max(256, min(2048, round(height / 8) * 8))
        workflow: dict[str, dict] = {
            "1": {"class_type": "UnetLoaderGGUF", "inputs": {"unet_name": self.unet}},
            "2": {
                "class_type": "ModelSamplingAuraFlow",
                "inputs": {"model": ["1", 0], "shift": self.shift},
            },
            "3": {
                "class_type": "CLIPLoader",
                "inputs": {"clip_name": self.clip, "type": "qwen_image"},
            },
            "4": {"class_type": "VAELoader", "inputs": {"vae_name": self.vae}},
            "9": {
                "class_type": "EmptyLatentImage",
                "inputs": {"width": width, "height": height, "batch_size": 1},
            },
            "10": {
                "class_type": "KSampler",
                "inputs": {
                    "model": ["2", 0],
                    "seed": seed,
                    "steps": steps,
                    "cfg": guidance,
                    "sampler_name": self.sampler,
                    "scheduler": self.scheduler,
                    "positive": ["8", 0],
                    "negative": ["8", 0],
                    "latent_image": ["9", 0],
                    "denoise": 1.0,
                },
            },
            "11": {"class_type": "VAEDecode", "inputs": {"samples": ["10", 0], "vae": ["4", 0]}},
            "12": {
                "class_type": "SaveImage",
                "inputs": {"images": ["11", 0], "filename_prefix": "img_edit/gateway"},
            },
        }
        encode: dict[str, dict] = {"clip": ["3", 0], "prompt": prompt, "vae": ["4", 0]}
        for index, ref in enumerate(refs):
            node_id = str(5 + index)
            path = f"{ref['subfolder']}/{ref['name']}" if ref["subfolder"] else ref["name"]
            workflow[node_id] = {"class_type": "LoadImage", "inputs": {"image": path}}
            encode[f"image{index + 1}"] = [node_id, 0]
        workflow["8"] = {"class_type": "TextEncodeQwenImageEditPlus", "inputs": encode}
        return workflow

    def vram_used_mb(self) -> int | None:
        try:
            with httpx.Client(timeout=3.0) as client:
                stats = client.get(self.url + "/system_stats").json()
            device = (stats.get("devices") or [{}])[0]
            total = int(device.get("vram_total") or 0)
            free = int(device.get("vram_free") or 0)
            if total <= 0:
                return None
            return int((total - free) / (1024 * 1024))
        except Exception:  # noqa: BLE001
            return None


def build_backend() -> Backend:
    kind = os.getenv("BACKEND", "mock").lower()
    model = os.getenv("MODEL_NAME", "Qwen/Qwen-Image-Edit-2511")
    if kind == "comfyui":
        return ComfyUIBackend()
    if kind == "diffusers":
        return DiffusersBackend()
    if kind == "mock":
        return MockBackend(model)
    raise RuntimeError(f"暂不支持 BACKEND={kind}（可用：comfyui / diffusers / mock）")
