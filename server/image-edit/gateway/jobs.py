"""job 状态机、单并发队列与 worker。设计稿 §4.5、§4.8。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
import uuid
from dataclasses import dataclass

from backend import Backend
from imageops import Input, choose_size, parse_size, to_png
from prompts import optimizer
from schemas import JobOut, JobRequest
from storage import Storage, input_thumbs

log = logging.getLogger("jobs")

DEDUP_SECONDS = 15 * 60
CACHE_SECONDS = 24 * 3600


@dataclass
class Queued:
    job_id: str
    prompt: str
    negative: str
    steps: int
    guidance: float
    seed: int | None
    size: str | None
    inputs: list[Input]


class JobManager:
    def __init__(self, storage: Storage, backend: Backend, config: dict) -> None:
        self.storage = storage
        self.backend = backend
        self.max_queue = int(config["max_queue"])
        self.timeout = int(config["job_timeout"])
        self.max_side = int(config["max_side"])
        self.cache_enabled = bool(config["cache"])
        self._queue: asyncio.Queue[Queued] = asyncio.Queue()
        self._worker: asyncio.Task | None = None
        self._cancel: set[str] = set()
        self._inflight: dict[str, asyncio.Task] = {}

    # ---------------------------------------------------------------- 生命周期
    async def start(self) -> None:
        dropped = self.storage.fail_unfinished()
        if dropped:
            log.warning("启动时把 %d 个未完成的 job 标记为 failed", dropped)
        self._worker = asyncio.create_task(self._run(), name="image-edit-worker")

    async def stop(self) -> None:
        if self._worker:
            self._worker.cancel()
            with_ctx = self._worker
            try:
                await with_ctx
            except asyncio.CancelledError:
                pass

    def depth(self) -> tuple[int, int]:
        return self.storage.queue_depth()

    # ---------------------------------------------------------------- 提交
    def capacity_left(self) -> int:
        running, queued = self.storage.queue_depth()
        return self.max_queue - running - queued

    def find_duplicate(self, request_id: str):
        if not request_id:
            return None
        return self.storage.find_request(request_id, int(time.time()) - DEDUP_SECONDS)

    def find_cache(self, cache_key: str):
        if not self.cache_enabled:
            return None
        return self.storage.find_cache(cache_key, int(time.time()) - CACHE_SECONDS)

    def create(
        self,
        request: JobRequest,
        inputs: list[Input],
        *,
        batch_id: str | None = None,
        seed: int | None = None,
        cached_row=None,
    ) -> tuple[str, int]:
        now = int(time.time())
        job_id = f"job_{time.strftime('%Y%m%d')}_{uuid.uuid4().hex[:8]}"
        prompt_hash = hashlib.sha256(request.prompt.encode("utf-8")).hexdigest()
        cache_key = self.cache_key(request, inputs)
        self.storage.insert(
            {
                "id": job_id,
                "request_id": (request.request_id or "")[:64],
                "batch_id": batch_id,
                "label": request.display_label(),
                "status": "queued",
                "stage": "queued",
                "created_at": now,
                "input_count": len(inputs),
                "input_thumbs": json.dumps(self.storage.write_input_thumbs(job_id, [i.thumb for i in inputs])),
                "prompt_hash": prompt_hash,
                "prompt_len": len(request.prompt),
                "steps": request.steps,
                "size": request.size,
                "seed": seed,
                "guidance_scale": request.guidance_scale,
                "cache_key": cache_key,
                "cached": 0,
            }
        )
        payload = Queued(
            job_id=job_id,
            prompt=request.prompt,
            negative=request.negative_prompt,
            steps=request.steps,
            guidance=request.guidance_scale,
            seed=seed,
            size=request.size,
            inputs=inputs,
        )
        if cached_row is not None and self._reuse_cache(job_id, cached_row):
            position = 0
        else:
            self._queue.put_nowait(payload)
            position = self.storage.position(job_id)
        return job_id, position

    def _reuse_cache(self, job_id: str, cached_row) -> bool:
        """命中缓存时复制结果文件，不占用 GPU；返回 False 表示缓存文件已失效。"""
        result_path = cached_row["result_path"]
        if not result_path or not os.path.exists(result_path):
            return False
        target = self.storage.result_path(job_id)
        try:
            with open(result_path, "rb") as source, open(target, "wb") as dest:
                dest.write(source.read())
        except OSError:
            return False
        self.storage.patch(
            job_id,
            status="succeeded",
            stage="done",
            finished_at=int(time.time()),
            elapsed_ms=0,
            result_path=target,
            result_sha256=cached_row["result_sha256"],
            width=cached_row["width"],
            height=cached_row["height"],
            bytes=cached_row["bytes"],
            cached=1,
        )

    def cache_key(self, request: JobRequest, inputs: list[Input]) -> str:
        digest = hashlib.sha256()
        digest.update(request.prompt.encode("utf-8"))
        digest.update(f"|{request.negative_prompt}|{request.steps}|{request.guidance_scale}|{request.size}".encode())
        for item in inputs:
            digest.update(hashlib.sha256(item.jpeg).digest())
        return digest.hexdigest()

    # ---------------------------------------------------------------- 取消
    def cancel(self, job_id: str) -> tuple[int, str]:
        row = self.storage.get(job_id)
        if row is None:
            return 404, "not_found"
        status = row["status"]
        if status == "queued":
            self._cancel.add(job_id)
            self.storage.patch(job_id, status="canceled", stage="done", finished_at=int(time.time()))
            return 200, "canceled"
        if status in {"running", "cancel_requested"}:
            self._cancel.add(job_id)
            self.storage.patch(job_id, status="cancel_requested")
            return 202, "cancel_requested"
        return 409, "job_finished"

    # ---------------------------------------------------------------- worker
    async def _run(self) -> None:
        while True:
            item = await self._queue.get()
            try:
                await self._process(item)
            except asyncio.CancelledError:
                raise
            except Exception as error:  # noqa: BLE001
                log.exception("worker 异常：%s", error)
            finally:
                self._queue.task_done()

    async def _process(self, item: Queued) -> None:
        if item.job_id in self._cancel:
            self._cancel.discard(item.job_id)
            return
        started = int(time.time())
        self.storage.patch(item.job_id, status="running", stage="running", started_at=started)
        prompt = await asyncio.to_thread(optimizer.optimize, item.prompt)
        if prompt != item.prompt:
            log.info("提示词优化 %s：%d 字 → %d 字", item.job_id, len(item.prompt), len(prompt))
        width, height = parse_size(
            item.size,
            item.inputs[0].width,
            item.inputs[0].height,
            self.max_side,
        )
        log.info("开始 %s（%dx%d, steps=%d）", item.job_id, width, height, item.steps)
        try:
            image = await asyncio.wait_for(
                asyncio.to_thread(
                    self.backend.generate,
                    [i.pil for i in item.inputs],
                    prompt,
                    steps=item.steps,
                    guidance=item.guidance,
                    negative=item.negative,
                    seed=item.seed,
                    width=width,
                    height=height,
                ),
                timeout=self.timeout,
            )
        except asyncio.TimeoutError:
            self._finish_failed(item.job_id, "backend_timeout", f"超过 {self.timeout}s 未完成")
            return
        except asyncio.CancelledError:
            raise
        except Exception as error:  # noqa: BLE001
            self._finish_failed(item.job_id, "backend_error", str(error)[:300])
            return

        if item.job_id in self._cancel:
            self._cancel.discard(item.job_id)
            self.storage.patch(item.job_id, status="canceled", stage="done", finished_at=int(time.time()))
            log.info("已取消 %s，结果丢弃", item.job_id)
            return

        self.storage.patch(item.job_id, stage="finalizing")
        saved = self.storage.write_result(item.job_id, to_png(image), image.width, image.height)
        finished = int(time.time())
        self.storage.patch(
            item.job_id,
            status="succeeded",
            stage="done",
            finished_at=finished,
            elapsed_ms=(finished - started) * 1000,
            cached=0,
            **saved,
        )
        log.info("完成 %s，用时 %ds", item.job_id, finished - started)

    def _finish_failed(self, job_id: str, code: str, message: str) -> None:
        self.storage.patch(
            job_id,
            status="failed",
            stage="done",
            finished_at=int(time.time()),
            error_code=code,
            error_message=message,
        )
        log.warning("失败 %s：%s %s", job_id, code, message)

    # ---------------------------------------------------------------- 输出
    def to_out(self, row) -> JobOut:
        status = row["status"]
        result_path = row["result_path"]
        alive = bool(result_path) and os.path.exists(result_path)
        error = None
        if row["error_code"]:
            error = {"code": row["error_code"], "message": row["error_message"] or ""}
        return JobOut(
            id=row["id"],
            batch_id=row["batch_id"],
            label=row["label"] or "",
            status=status,
            stage=row["stage"] or "queued",
            queue_position=self.storage.position(row["id"]) if status == "queued" else 0,
            created_at=row["created_at"],
            started_at=row["started_at"],
            finished_at=row["finished_at"],
            elapsed_ms=row["elapsed_ms"],
            input_count=row["input_count"],
            input_thumbs=input_thumbs(row),
            width=row["width"],
            height=row["height"],
            bytes=row["bytes"],
            thumb_url=f"/v1/results/{row['id']}.png?max_side=320" if alive else None,
            result_url=f"/v1/results/{row['id']}.png" if alive else None,
            cached=bool(row["cached"]),
            error=error,
        )
