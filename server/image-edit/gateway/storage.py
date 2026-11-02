"""SQLite 元数据、结果文件与 TTL 清理。设计稿 §4.12。"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import time
from typing import Any, Iterable

SCHEMA = """
create table if not exists jobs (
    id            text primary key,
    request_id    text,
    batch_id      text,
    label         text,
    status        text not null,
    stage         text not null default 'queued',
    created_at    integer not null,
    started_at    integer,
    finished_at   integer,
    elapsed_ms    integer,
    input_count   integer not null default 1,
    input_thumbs  text not null default '[]',
    prompt_hash   text not null default '',
    prompt_len    integer not null default 0,
    steps         integer not null default 40,
    size          text,
    seed          integer,
    guidance_scale real not null default 1.0,
    result_path   text,
    result_sha256 text,
    width         integer,
    height        integer,
    bytes         integer,
    error_code    text,
    error_message text,
    cache_key     text,
    cached        integer not null default 0
);
create index if not exists jobs_created on jobs(created_at desc);
create index if not exists jobs_status on jobs(status);
create index if not exists jobs_request on jobs(request_id);
create index if not exists jobs_cache on jobs(cache_key);
"""


class Storage:
    def __init__(self, db_path: str, result_dir: str, input_dir: str) -> None:
        self.db_path = db_path
        self.result_dir = result_dir
        self.input_dir = input_dir

    # ---------------------------------------------------------------- 基础
    def connect(self) -> sqlite3.Connection:
        os.makedirs(os.path.dirname(self.db_path) or ".", exist_ok=True)
        con = sqlite3.connect(self.db_path, timeout=15)
        con.row_factory = sqlite3.Row
        con.execute("pragma journal_mode=wal")
        con.execute("pragma busy_timeout=8000")
        return con

    def init(self) -> None:
        os.makedirs(self.result_dir, exist_ok=True)
        os.makedirs(self.input_dir, exist_ok=True)
        with self.connect() as con:
            con.executescript(SCHEMA)

    # ---------------------------------------------------------------- 写入
    def insert(self, job: dict[str, Any]) -> None:
        columns = ", ".join(job)
        marks = ", ".join(["?"] * len(job))
        with self.connect() as con:
            con.execute(f"insert into jobs ({columns}) values ({marks})", list(job.values()))

    def patch(self, job_id: str, **fields: Any) -> None:
        if not fields:
            return
        assignments = ", ".join(f"{key} = ?" for key in fields)
        with self.connect() as con:
            con.execute(f"update jobs set {assignments} where id = ?", [*fields.values(), job_id])

    def fail_unfinished(self) -> int:
        """网关重启时，把还在跑的 job 标成 failed，不假装还在跑。"""
        now = int(time.time())
        with self.connect() as con:
            cursor = con.execute(
                "update jobs set status='failed', stage='done', finished_at=?, "
                "error_code='server_restarted', error_message='网关重启，任务已中止' "
                "where status in ('queued','running','cancel_requested')",
                (now,),
            )
            return cursor.rowcount

    def delete(self, job_id: str) -> bool:
        """彻底删除一条历史任务：数据库记录 + 结果图 / 缩略图。"""
        with self.connect() as con:
            row = con.execute("select id from jobs where id = ?", (job_id,)).fetchone()
            if row is None:
                return False
            con.execute("delete from jobs where id = ?", (job_id,))
        self._drop_files(job_id)
        return True

    # ---------------------------------------------------------------- 查询
    def get(self, job_id: str) -> sqlite3.Row | None:
        with self.connect() as con:
            return con.execute("select rowid, * from jobs where id = ?", (job_id,)).fetchone()

    def find_request(self, request_id: str, since: int) -> sqlite3.Row | None:
        with self.connect() as con:
            return con.execute(
                "select * from jobs where request_id = ? and created_at >= ? order by created_at desc limit 1",
                (request_id, since),
            ).fetchone()

    def find_cache(self, cache_key: str, since: int) -> sqlite3.Row | None:
        with self.connect() as con:
            return con.execute(
                "select * from jobs where cache_key = ? and status = 'succeeded' and created_at >= ? "
                "order by created_at desc limit 1",
                (cache_key, since),
            ).fetchone()

    def list(self, status: str, limit: int, cursor: int | None) -> list[sqlite3.Row]:
        clause, values = "", []
        if status == "active":
            clause = "where status in ('queued','running','cancel_requested')"
        elif status in {"succeeded", "failed", "canceled"}:
            clause = "where status = ?"
            values.append(status)
        if cursor:
            # 用 rowid 而不是 created_at：批量任务常在同一秒创建，
            # 按秒级时间戳翻页会漏掉同秒的记录。
            clause += (" and " if clause else "where ") + "rowid < ?"
            values.append(cursor)
        values.append(limit)
        with self.connect() as con:
            return con.execute(
                f"select rowid, * from jobs {clause} order by rowid desc limit ?", values
            ).fetchall()

    def queue_depth(self) -> tuple[int, int]:
        with self.connect() as con:
            running = con.execute("select count(*) from jobs where status in ('running','cancel_requested')").fetchone()[0]
            queued = con.execute("select count(*) from jobs where status = 'queued'").fetchone()[0]
        return int(running), int(queued)

    def position(self, job_id: str) -> int:
        row = self.get(job_id)
        if row is None or row["status"] != "queued":
            return 0
        with self.connect() as con:
            ahead = con.execute(
                "select count(*) from jobs where status = 'queued' and rowid <= ? and id != ?",
                (row["rowid"], job_id),
            ).fetchone()[0]
        return int(ahead)

    # ---------------------------------------------------------------- 文件
    def write_result(self, job_id: str, png: bytes, width: int, height: int) -> dict[str, Any]:
        path = os.path.join(self.result_dir, f"{job_id}.png")
        with open(path, "wb") as handle:
            handle.write(png)
        return {
            "result_path": path,
            "result_sha256": hashlib.sha256(png).hexdigest(),
            "width": width,
            "height": height,
            "bytes": len(png),
        }

    def write_input_thumbs(self, job_id: str, thumbs: Iterable[bytes]) -> list[str]:
        urls = []
        for index, data in enumerate(thumbs):
            path = os.path.join(self.input_dir, f"{job_id}_{index}.jpg")
            with open(path, "wb") as handle:
                handle.write(data)
            urls.append(f"/v1/jobs/{job_id}/inputs/{index}.jpg")
        return urls

    def input_thumb_path(self, job_id: str, index: int) -> str:
        return os.path.join(self.input_dir, f"{job_id}_{index}.jpg")

    def result_path(self, job_id: str) -> str:
        return os.path.join(self.result_dir, f"{job_id}.png")

    # ---------------------------------------------------------------- 清理
    def cleanup(self, ttl_hours: int, retention_days: int, max_gb: float) -> dict[str, int]:
        now = time.time()
        removed_files = 0
        expired = int(now - ttl_hours * 3600)
        with self.connect() as con:
            rows = con.execute(
                "select id from jobs where result_path is not null and finished_at is not null and finished_at < ?",
                (expired,),
            ).fetchall()
        for row in rows:
            removed_files += self._drop_files(row["id"])
            self.patch(row["id"], result_path=None, result_sha256=None, error_code="result_expired",
                       error_message="结果已过期清理")

        cutoff = int(now - retention_days * 86400)
        with self.connect() as con:
            old = con.execute("select id from jobs where created_at < ?", (cutoff,)).fetchall()
        for row in old:
            removed_files += self._drop_files(row["id"])
        with self.connect() as con:
            deleted = con.execute("delete from jobs where created_at < ?", (cutoff,)).rowcount

        trimmed = self._trim_size(max_gb)
        return {"files": removed_files, "jobs": int(deleted), "trimmed": trimmed}

    def _drop_files(self, job_id: str) -> int:
        count = 0
        for path in (
            self.result_path(job_id),
            self.input_thumb_path(job_id, 0),
            self.input_thumb_path(job_id, 1),
            self.input_thumb_path(job_id, 2),
        ):
            if os.path.exists(path):
                os.remove(path)
                count += 1
        for name in os.listdir(self.result_dir):
            if name.startswith(f"{job_id}_") and name.endswith(".jpg"):
                os.remove(os.path.join(self.result_dir, name))
                count += 1
        return count

    def _trim_size(self, max_gb: float) -> int:
        limit = max_gb * 1024 ** 3
        files = []
        total = 0
        for folder in (self.result_dir, self.input_dir):
            for name in os.listdir(folder):
                path = os.path.join(folder, name)
                if not os.path.isfile(path):
                    continue
                stat = os.stat(path)
                total += stat.st_size
                files.append((stat.st_mtime, path))
        if total <= limit:
            return 0
        removed = 0
        for _, path in sorted(files):
            if total <= limit:
                break
            size = os.path.getsize(path)
            os.remove(path)
            total -= size
            removed += 1
        return removed


def input_thumbs(row: sqlite3.Row) -> list[str]:
    try:
        value = json.loads(row["input_thumbs"])
        return value if isinstance(value, list) else []
    except (TypeError, ValueError):
        return []
