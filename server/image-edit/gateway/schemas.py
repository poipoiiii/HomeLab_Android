"""请求与响应模型（Pydantic v2）。字段约束与设计稿 §4.5–§4.9 一致。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from workflows import WORKFLOWS

MAX_PROMPT = 2000
MAX_LABEL = 40
MAX_REFS = 3
MAX_BATCH = 8


class ImageIn(BaseModel):
    mime: str = Field(pattern=r"^image/(jpeg|jpg|png|webp)$")
    b64: str = Field(min_length=16)


class JobRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=MAX_PROMPT)
    label: str = Field(default="", max_length=MAX_LABEL)
    workflow: str = "qwen_edit"
    mode: str | None = None
    images: list[ImageIn] = Field(default_factory=list, max_length=MAX_REFS)
    size: str | None = None
    steps: int | None = None
    guidance_scale: float = 1.0
    negative_prompt: str = Field(default="", max_length=MAX_PROMPT)
    seed: int | None = None
    request_id: str = Field(default="", max_length=64)

    @field_validator("size")
    @classmethod
    def _check_size(cls, value: str | None) -> str | None:
        if value is None or value == "":
            return None
        parts = value.lower().split("x")
        if len(parts) != 2 or not all(p.isdigit() for p in parts):
            raise ValueError("size 必须是 宽x高，例如 1024x1024")
        width, height = int(parts[0]), int(parts[1])
        if not (256 <= width <= 2048 and 256 <= height <= 2048):
            raise ValueError("size 每边必须在 256–2048 之间")
        return f"{width}x{height}"

    @model_validator(mode="after")
    def _normalize_workflow(self):
        meta = WORKFLOWS.get(self.workflow)
        if meta is None:
            raise ValueError(f"workflow 不支持：{self.workflow}")

        expected_mode = meta["mode"]
        if self.mode is None:
            self.mode = expected_mode
        elif self.mode != expected_mode:
            raise ValueError(f"{meta['name']} 的 mode 必须是 {expected_mode}")

        count = len(self.images)
        if count < meta["min_refs"]:
            raise ValueError(f"{meta['name']}至少需要 {meta['min_refs']} 张照片")
        if count > meta["max_refs"]:
            raise ValueError(f"{meta['name']}最多 {meta['max_refs']} 张照片")

        steps = self.steps if self.steps is not None else meta["steps"]["default"]
        if not (meta["steps"]["min"] <= steps <= meta["steps"]["max"]):
            raise ValueError(
                f"{meta['name']}的 steps 必须在 {meta['steps']['min']}–{meta['steps']['max']} 之间"
            )
        self.steps = int(steps)

        if self.workflow == "krea2_t2i":
            if self.size is None:
                self.size = meta["default_size"]
            if self.size not in meta["sizes"]:
                raise ValueError(f"生图尺寸只支持：{', '.join(meta['sizes'])}")
            self.guidance_scale = 1.0
        elif self.size is None:
            # 改图不传 size 时按主图比例自动选择
            self.size = None

        return self

    def display_label(self) -> str:
        """App 没给 label 时用指令前 20 字，任务卡展示。"""
        if self.label.strip():
            return self.label.strip()
        return self.prompt.strip().replace("\n", " ")[:20]


class BatchRequest(BaseModel):
    jobs: list[JobRequest] = Field(min_length=2, max_length=MAX_BATCH)


class JobOut(BaseModel):
    id: str
    batch_id: str | None = None
    label: str = ""
    workflow: str = "qwen_edit"
    mode: str = "edit"
    media_type: str = "image"
    status: Literal["queued", "running", "succeeded", "failed", "canceled", "cancel_requested"]
    stage: str = "queued"
    queue_position: int = 0
    created_at: int = 0
    started_at: int | None = None
    finished_at: int | None = None
    elapsed_ms: int | None = None
    input_count: int = 1
    input_thumbs: list[str] = Field(default_factory=list)
    width: int | None = None
    height: int | None = None
    bytes: int | None = None
    thumb_url: str | None = None
    result_url: str | None = None
    cached: bool = False
    error: dict[str, Any] | None = None


class JobsPage(BaseModel):
    jobs: list[JobOut]
    next_cursor: int | None = None


class CreateAck(BaseModel):
    id: str
    status: str
    queue_position: int
    created_at: int
    deduplicated: bool = False
    cached: bool = False


class BatchAckJob(BaseModel):
    id: str
    status: str
    queue_position: int


class BatchAck(BaseModel):
    batch_id: str
    jobs: list[BatchAckJob]


class JobWithImage(JobOut):
    image: dict[str, Any] | None = None


class HealthOut(BaseModel):
    status: Literal["ok", "degraded", "warming_up"]
    model: str
    backend: str
    queue: dict[str, int]
    gpu_vram_used_mb: int | None = None
    detail: str = ""
    workflows: list[str] = Field(default_factory=list)
    comfy_queue: dict[str, int] | None = None
    loaded_workflow: str | None = None
    gpu_busy: bool = False
