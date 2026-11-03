"""img-edit-gateway：鉴权、路由、错误码。设计稿 §4.4–§4.10。"""

from __future__ import annotations

import asyncio
import hmac
import io
import logging
import os
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from PIL import Image

import backend as backend_module
from imageops import ImageError, decode_inputs
from jobs import JobManager
from schemas import (
    BatchAck,
    BatchAckJob,
    BatchRequest,
    CreateAck,
    HealthOut,
    JobOut,
    JobRequest,
    JobWithImage,
    JobsPage,
)
from storage import Storage

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("gateway")

TOKEN = os.getenv("IMAGE_EDIT_TOKEN", "").strip()
MAX_QUEUE = int(os.getenv("MAX_QUEUE", "12"))
MAX_BATCH = int(os.getenv("MAX_BATCH", "8"))
MAX_INPUT_MB = int(os.getenv("MAX_INPUT_MB", "20"))
MAX_SIDE = int(os.getenv("MAX_SIDE", "2048"))
JOB_TIMEOUT = int(os.getenv("JOB_TIMEOUT_SECONDS", "1200"))
SYNC_WAIT = int(os.getenv("SYNC_WAIT_SECONDS", "240"))
RESULT_TTL_HOURS = int(os.getenv("RESULT_TTL_HOURS", "168"))
JOB_RETENTION_DAYS = int(os.getenv("JOB_RETENTION_DAYS", "30"))
RESULT_MAX_GB = float(os.getenv("RESULT_MAX_GB", "20"))
CACHE_ENABLED = os.getenv("CACHE_ENABLED", "1") not in {"0", "false", "False"}

storage = Storage(
    db_path=os.getenv("JOBS_DB", "/data/jobs.sqlite"),
    result_dir=os.getenv("RESULT_DIR", "/data/results"),
    input_dir=os.getenv("INPUT_DIR", "/data/inputs"),
)
backend = backend_module.build_backend()
manager = JobManager(
    storage,
    backend,
    {
        "max_queue": MAX_QUEUE,
        "job_timeout": JOB_TIMEOUT,
        "max_side": MAX_SIDE,
        "cache": CACHE_ENABLED,
    },
)


def _cleanup_loop() -> asyncio.Task:
    async def run() -> None:
        while True:
            await asyncio.sleep(3600)
            try:
                stats = storage.cleanup(RESULT_TTL_HOURS, JOB_RETENTION_DAYS, RESULT_MAX_GB)
                log.info("清理完成 %s", stats)
            except Exception as error:  # noqa: BLE001
                log.warning("清理失败：%s", error)

    return asyncio.create_task(run(), name="image-edit-cleanup")


@asynccontextmanager
async def lifespan(_: FastAPI):
    storage.init()
    await manager.start()
    cleaner = _cleanup_loop()
    try:
        yield
    finally:
        cleaner.cancel()
        await manager.stop()


app = FastAPI(title="img-edit-gateway", lifespan=lifespan, docs_url=None, redoc_url=None)


@app.exception_handler(ImageError)
async def _image_error(_: Request, error: ImageError) -> JSONResponse:
    return JSONResponse({"code": error.code, "message": error.message}, status_code=400)


def require_token(request: Request) -> None:
    if not TOKEN:
        raise HTTPException(503, detail="服务端未配置 IMAGE_EDIT_TOKEN")
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not hmac.compare_digest(value.strip(), TOKEN):
        raise HTTPException(401, detail="Token 无效")


auth = [Depends(require_token)]


# ---------------------------------------------------------------- 健康与能力
@app.get("/health", response_model=HealthOut)
async def health() -> HealthOut:
    running, queued = manager.depth()
    state = backend.status()
    if state.startswith("failed"):
        status = "degraded"
    elif state in {"warming_up", "running", "sleeping"}:
        # sleeping = 闲置定时器停了 ComfyUI，提交任务会自动唤醒
        status = "warming_up"
    else:
        status = "ok"
    return HealthOut(
        status=status,
        model=backend.model,
        backend=backend.name,
        queue={"running": running, "queued": queued, "capacity": MAX_QUEUE},
        gpu_vram_used_mb=backend.vram_used_mb(),
        detail=state,
    )


@app.get("/v1/models", dependencies=auth)
async def models() -> dict:
    return {
        "data": [
            {
                "id": backend.model,
                "backend": backend.name,
                "max_refs": 3,
                "max_batch": MAX_BATCH,
                "sizes": [[1024, 1024], [1328, 1328], [1536, 1024]],
                "guidance_distilled": True,
            }
        ]
    }


# ---------------------------------------------------------------- 创建任务
def _prepare(request: JobRequest):
    inputs = decode_inputs(request.images, MAX_INPUT_MB)
    return inputs


@app.post("/v1/jobs", response_model=CreateAck, status_code=202, dependencies=auth)
async def create_job(request: JobRequest) -> CreateAck:
    duplicate = manager.find_duplicate(request.request_id)
    if duplicate is not None:
        out = manager.to_out(duplicate)
        return CreateAck(
            id=out.id,
            status=out.status,
            queue_position=out.queue_position,
            created_at=out.created_at,
            deduplicated=True,
            cached=out.cached,
        )
    if manager.capacity_left() <= 0:
        raise HTTPException(429, detail="队列已满，稍后再试")

    inputs = _prepare(request)
    cache_key = manager.cache_key(request, inputs)
    cached = manager.find_cache(cache_key)
    job_id, position = manager.create(request, inputs, seed=request.seed, cached_row=cached)
    row = storage.get(job_id)
    return CreateAck(
        id=job_id,
        status=row["status"],
        queue_position=position,
        created_at=row["created_at"],
        deduplicated=False,
        cached=bool(cached),
    )


@app.post("/v1/jobs/batch", response_model=BatchAck, status_code=202, dependencies=auth)
async def create_batch(payload: BatchRequest) -> BatchAck:
    if manager.capacity_left() < len(payload.jobs):
        raise HTTPException(429, detail="队列容量不足，整批未入队")
    prepared = [(item, _prepare(item)) for item in payload.jobs]
    total = sum(len(part.jpeg) for _, inputs in prepared for part in inputs)
    if total > MAX_INPUT_MB * 1024 * 1024:
        raise HTTPException(400, detail=f"整批图片总量超过 {MAX_INPUT_MB} MB，请拆成多批")

    batch_id = f"batch_{time.strftime('%Y%m%d')}_{uuid.uuid4().hex[:4]}"
    created = []
    for request, inputs in prepared:
        job_id, position = manager.create(
            request,
            inputs,
            batch_id=batch_id,
            seed=request.seed,
        )
        row = storage.get(job_id)
        created.append(BatchAckJob(id=job_id, status=row["status"], queue_position=position))
    return BatchAck(batch_id=batch_id, jobs=created)


# ---------------------------------------------------------------- 查询与取消
@app.get("/v1/jobs", response_model=JobsPage, dependencies=auth)
async def list_jobs(
    status: str = Query(default="all", pattern="^(all|active|succeeded|failed|canceled)$"),
    limit: int = Query(default=20, ge=1, le=50),
    cursor: int | None = None,
) -> JobsPage:
    rows = storage.list(status, limit, cursor)
    jobs = [manager.to_out(row) for row in rows]
    next_cursor = rows[-1]["created_at"] if len(rows) == limit else None
    return JobsPage(jobs=jobs, next_cursor=next_cursor)


@app.get("/v1/jobs/{job_id}", response_model=JobOut, dependencies=auth)
async def get_job(job_id: str) -> JobOut:
    row = storage.get(job_id)
    if row is None:
        raise HTTPException(404, detail="job 不存在")
    return manager.to_out(row)


@app.delete("/v1/jobs/{job_id}", dependencies=auth)
async def cancel_or_delete_job(job_id: str) -> Response:
    row = storage.get(job_id)
    if row is None:
        raise HTTPException(404, detail="job 不存在")
    # 排队中/运行中：保持原来的取消语义
    if row["status"] in {"queued", "running", "cancel_requested"}:
        code, status = manager.cancel(job_id)
        if code == 404:
            raise HTTPException(404, detail="job 不存在")
        if code == 409:
            raise HTTPException(409, detail="job_finished")
        return JSONResponse({"id": job_id, "status": status}, status_code=code)
    # 已结束：从历史里彻底删除（记录 + 结果图 + 缩略图）
    storage.delete(job_id)
    return JSONResponse({"id": job_id, "status": "deleted"})


# ---------------------------------------------------------------- 文件
@app.get("/v1/jobs/{job_id}/inputs/{index}.jpg")
async def input_thumb(job_id: str, index: int, request: Request) -> FileResponse:
    # 任务卡缩略图同样由 Coil 直接加载，允许用 ?token= 传凭证
    if TOKEN:
        supplied = request_token_from_query_or_header(request)
        if not supplied or not hmac.compare_digest(supplied, TOKEN):
            raise HTTPException(401, detail="Token 无效")
    path = storage.input_thumb_path(job_id, index)
    if not os.path.exists(path):
        raise HTTPException(404, detail="输入缩略图不存在")
    return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "private, max-age=604800"})


@app.get("/v1/results/{job_id}.png")
async def result_image(job_id: str, request: Request, max_side: int | None = None) -> FileResponse:
    # 图片 URL 由 <img>/Coil 直接加载，允许用 ?token= 传凭证
    if TOKEN:
        supplied = request_token_from_query_or_header(request)
        if not supplied or not hmac.compare_digest(supplied, TOKEN):
            raise HTTPException(401, detail="Token 无效")

    source = storage.result_path(job_id)
    if not os.path.exists(source):
        raise HTTPException(404, detail="result_expired")

    if max_side is None:
        return FileResponse(source, media_type="image/png", headers={"Cache-Control": "private, max-age=604800, immutable"})

    side = max(128, min(2048, max_side))
    cached = os.path.join(storage.result_dir, f"{job_id}_{side}.jpg")
    if not os.path.exists(cached):
        with Image.open(source) as image:
            copy = image.convert("RGB")
            copy.thumbnail((side, side), Image.LANCZOS)
            buffer = io.BytesIO()
            copy.save(buffer, format="JPEG", quality=80)
        with open(cached, "wb") as handle:
            handle.write(buffer.getvalue())
    return FileResponse(cached, media_type="image/jpeg", headers={"Cache-Control": "private, max-age=604800, immutable"})


def request_token_from_query_or_header(request: Request) -> str:
    token = request.query_params.get("token")
    if token:
        return token
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    return value.strip() if scheme.lower() == "bearer" else ""


# ---------------------------------------------------------------- 同步接口
@app.post("/v1/edit", response_model=JobWithImage, dependencies=auth)
async def edit_sync(request: JobRequest) -> Response:
    ack = await create_job(request)
    deadline = time.time() + SYNC_WAIT
    while time.time() < deadline:
        row = storage.get(ack.id)
        if row is None:
            raise HTTPException(500, detail="job 丢失")
        if row["status"] in {"succeeded", "failed", "canceled"}:
            out = manager.to_out(row)
            payload = out.model_dump()
            payload["image"] = (
                {
                    "url": out.result_url,
                    "width": out.width,
                    "height": out.height,
                    "bytes": out.bytes,
                }
                if out.result_url
                else None
            )
            return JSONResponse(payload, status_code=200)
        await asyncio.sleep(0.5)
    return JSONResponse(manager.to_out(storage.get(ack.id)).model_dump(), status_code=202)
