"""用本地小模型把用户的修图指令扩写成更可执行的提示词。

默认关闭（PROMPT_OPTIMIZER=none）。两种模式：
- llama：进程内 llama.cpp 直接加载 GGUF（无需额外服务，CPU 推理）
- openai：OpenAI 兼容接口，例如 Ollama（http://127.0.0.1:11434/v1）
失败时静默回退到用户原文，不影响出图。
"""

from __future__ import annotations

import logging
import os
import threading

import httpx

log = logging.getLogger("prompts")

SYSTEM_PROMPT = (
    "你是图像编辑提示词优化器。把用户的中文修图指令改写成给扩散模型的具体提示词，要求：\n"
    "1. 只描述可见的像素级修改：要改的区域、效果、材质、光影、边缘、皮肤或布料质感；\n"
    "2. 明确需要保留的内容：人物身份、五官、发型、姿势、服装、构图、背景（除非用户要求改）；\n"
    "3. 禁止新增用户没提到的动作、姿势、场景、道具，不要解释，不要分点，不要编号；\n"
    "4. 控制在 40–120 个汉字，只输出最终提示词本身。\n"
    "示例：\n"
    "用户：给我P个腹肌\n"
    "输出：露出腹部，腹肌与腹外斜肌线条清晰，皮肤质感自然，光影与现有画面一致。\n"
    "用户：把背景换成海边日落\n"
    "输出：背景替换为海边日落，暖橙色天空与海面反光，人物五官、发型、姿势、服装保持不变。"
)


class PromptOptimizer:
    def __init__(self) -> None:
        self.mode = os.getenv("PROMPT_OPTIMIZER", "none").lower()
        self.url = (os.getenv("OPTIMIZER_URL") or "http://127.0.0.1:11434/v1").rstrip("/")
        self.model = os.getenv("OPTIMIZER_MODEL", "qwen2.5:3b-instruct")
        self.timeout = float(os.getenv("OPTIMIZER_TIMEOUT", "20"))
        self.max_chars = int(os.getenv("OPTIMIZER_MAX_CHARS", "800"))
        # llama 模式：进程内加载 GGUF，常驻约 2GB 内存，不占显存
        self.gguf = os.getenv("PROMPT_LLM_GGUF", "/home/ubuntu/models/rewrite-llm-q4.gguf")
        self.threads = int(os.getenv("PROMPT_LLM_THREADS", "4"))
        self.ctx = int(os.getenv("PROMPT_LLM_CTX", "2048"))
        self.gpu_layers = int(os.getenv("PROMPT_LLM_GPU_LAYERS", "0"))
        self._llama = None
        self._llama_lock = threading.Lock()

    @property
    def enabled(self) -> bool:
        return self.mode not in {"", "none", "off", "0"}

    def optimize(self, prompt: str) -> str:
        text = (prompt or "").strip()
        if not self.enabled or not text:
            return text
        try:
            content = self._optimize_llama(text) if self.mode == "llama" else self._optimize_openai(text)
            content = content.strip().strip('"“”').replace("\n", " ").strip()
            if not content:
                return text
            return content[: self.max_chars]
        except Exception as error:  # noqa: BLE001
            log.warning("提示词优化失败，改用原文：%s", str(error)[:160])
            return text

    def _optimize_openai(self, text: str) -> str:
        with httpx.Client(timeout=self.timeout) as client:
            response = client.post(
                self.url + "/chat/completions",
                json={
                    "model": self.model,
                    "temperature": 0.2,
                    "max_tokens": 400,
                    "messages": [
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": text},
                    ],
                },
            )
            response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"] or ""

    def _runner(self):
        """懒加载 llama.cpp；首次调用会读 2GB 模型，约 2–5s。"""
        if self._llama is None:
            with self._llama_lock:
                if self._llama is None:
                    from llama_cpp import Llama

                    if not os.path.exists(self.gguf):
                        raise FileNotFoundError(f"找不到本地模型：{self.gguf}")
                    self._llama = Llama(
                        model_path=self.gguf,
                        n_ctx=self.ctx,
                        n_threads=self.threads,
                        n_gpu_layers=self.gpu_layers,
                        verbose=False,
                    )
                    log.info("提示词模型已加载：%s", self.gguf)
        return self._llama

    def _optimize_llama(self, text: str) -> str:
        runner = self._runner()
        out = runner.create_chat_completion(
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": text},
            ],
            temperature=0.2,
            top_p=0.9,
            max_tokens=320,
        )
        return out["choices"][0]["message"]["content"] or ""


optimizer = PromptOptimizer()
