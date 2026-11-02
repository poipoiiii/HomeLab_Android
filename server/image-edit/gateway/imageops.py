"""图片解码、校验、EXIF 清理与缩略图。限制见设计稿 §4.12。"""

from __future__ import annotations

import base64
import binascii
import io
from dataclasses import dataclass

from PIL import Image, ImageOps

MAX_PIXELS = 40_000_000
MAX_SIDE = 2048
THUMB_SIDE = 320
JPEG_QUALITY = 90
THUMB_QUALITY = 80
ALLOWED_MIME = {"image/jpeg", "image/jpg", "image/png", "image/webp"}


class ImageError(ValueError):
    """图片不合法，映射成 400。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class Input:
    pil: Image.Image
    jpeg: bytes
    thumb: bytes
    width: int
    height: int


def _decode_b64(raw: str) -> bytes:
    text = raw.strip()
    if text.startswith("data:"):
        _, _, text = text.partition(",")
    try:
        return base64.b64decode(text, validate=False)
    except (binascii.Error, ValueError) as error:
        raise ImageError("bad_image", f"图片 base64 解不开：{error}") from error


def _load(data: bytes) -> Image.Image:
    try:
        probe = Image.open(io.BytesIO(data))
        probe.verify()
    except Exception as error:  # noqa: BLE001 - Pillow 抛的异常类型很杂
        raise ImageError("bad_image", f"图片格式无法识别：{error}") from error
    image = Image.open(io.BytesIO(data))
    width, height = image.size
    if width * height > MAX_PIXELS:
        raise ImageError("image_too_large", f"图片像素超过 {MAX_PIXELS // 1_000_000} 千万")
    # 纠正手机拍照方向，再转 RGB 并重新编码，彻底丢掉 EXIF/GPS
    image = ImageOps.exif_transpose(image)
    return image.convert("RGB")


def _fit(image: Image.Image) -> Image.Image:
    width, height = image.size
    longest = max(width, height)
    if longest <= MAX_SIDE:
        return image
    scale = MAX_SIDE / longest
    return image.resize(
        (max(1, round(width * scale)), max(1, round(height * scale))),
        Image.LANCZOS,
    )


def _jpeg(image: Image.Image, quality: int = JPEG_QUALITY) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality, optimize=True)
    return buffer.getvalue()


def decode_inputs(images: list, max_input_mb: int) -> list[Input]:
    """按顺序解码 1–3 张参考图；顺序即 prompt 里的图1/图2/图3。"""
    results: list[Input] = []
    total = 0
    for index, item in enumerate(images):
        if item.mime.lower() not in ALLOWED_MIME:
            raise ImageError("bad_mime", f"第 {index + 1} 张不是 JPEG/PNG/WebP")
        data = _decode_b64(item.b64)
        if len(data) > 8 * 1024 * 1024:
            raise ImageError("image_too_large", f"第 {index + 1} 张超过 8 MB")
        total += len(data)
        if total > max_input_mb * 1024 * 1024:
            raise ImageError("payload_too_large", f"图片总量超过 {max_input_mb} MB")
        image = _fit(_load(data))
        thumb = image.copy()
        thumb.thumbnail((THUMB_SIDE, THUMB_SIDE), Image.LANCZOS)
        results.append(
            Input(
                pil=image,
                jpeg=_jpeg(image),
                thumb=_jpeg(thumb, THUMB_QUALITY),
                width=image.width,
                height=image.height,
            )
        )
    return results


def choose_size(width: int, height: int, max_side: int = 1536) -> tuple[int, int]:
    """没指定 size 时按输入比例选尺寸，边长对齐到 32 的倍数。"""
    scale = min(1.0, max_side / max(width, height))
    w = max(256, int(round(width * scale / 32)) * 32)
    h = max(256, int(round(height * scale / 32)) * 32)
    return min(w, max_side), min(h, max_side)


def parse_size(value: str | None, width: int, height: int, max_side: int = 1536) -> tuple[int, int]:
    if not value:
        return choose_size(width, height, max_side)
    left, right = value.lower().split("x")
    return int(left), int(right)


def to_png(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()
