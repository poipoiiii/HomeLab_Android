"""图片工作流注册表：改图（Qwen-Image-Edit）与生图（Krea2）。

这里只放元数据和校验规则，不碰 torch / ComfyUI。
"""

from __future__ import annotations

WORKFLOWS: dict[str, dict] = {
    "qwen_edit": {
        "id": "qwen_edit",
        "mode": "edit",
        "name": "改图",
        "model": "Qwen-Image-Edit-2511",
        "min_refs": 1,
        "max_refs": 3,
        "sizes": ["1024x1024", "1328x1328", "1536x1024", "832x1216", "1216x832"],
        "default_size": None,
        "steps": {"default": 40, "min": 20, "max": 60},
        "guidance": 1.0,
        "timeout": 900,
        "est_cold_seconds": 240,
        "est_warm_seconds": 240,
    },
    "krea2_t2i": {
        "id": "krea2_t2i",
        "mode": "generate",
        "name": "生图",
        "model": "Krea2-Moody-Mix",
        "min_refs": 0,
        "max_refs": 0,
        "sizes": ["1024x1024", "832x1216", "1216x832", "768x1344", "1344x768"],
        "default_size": "832x1216",
        "steps": {"default": 10, "min": 6, "max": 20},
        "guidance": 1.0,
        "timeout": 300,
        "est_cold_seconds": 60,
        "est_warm_seconds": 15,
    },
}


def workflow_spec(workflow: str) -> dict | None:
    return WORKFLOWS.get(workflow)


def workflows_payload() -> list[dict]:
    return [
        {
            "id": item["id"],
            "mode": item["mode"],
            "name": item["name"],
            "model": item["model"],
            "min_refs": item["min_refs"],
            "max_refs": item["max_refs"],
            "sizes": list(item["sizes"]),
            "default_size": item["default_size"],
            "steps": dict(item["steps"]),
            "est_cold_seconds": item["est_cold_seconds"],
            "est_warm_seconds": item["est_warm_seconds"],
        }
        for item in WORKFLOWS.values()
    ]
