#!/usr/bin/env bash
# ComfyUI 闲置自动停：默认 30 分钟没有任何修图活动就停掉，释放显存。
# 有排队/运行中的任务、或 ComfyUI 队列非空时不动；由 timer 每分钟触发。
set -uo pipefail

IDLE_MINUTES="${IDLE_MINUTES:-30}"
DB=/home/ubuntu/img-edit/data/jobs.sqlite
COMFY=http://127.0.0.1:8188
OUTPUT=/home/ubuntu/ComfyUI/output
LOG=/home/ubuntu/ComfyUI/serve.log

systemctl is-active --quiet comfyui || exit 0

# ComfyUI 自己还有任务在跑/排队
if curl -sf --max-time 5 "$COMFY/queue" 2>/dev/null | grep -qE '"queue_running": *\[[^]]'; then
  exit 0
fi

query_db() {
  python3 - "$DB" "$1" <<'PY' 2>/dev/null || echo 0
import sqlite3
import sys

con = sqlite3.connect(sys.argv[1], timeout=5)
try:
    con.execute("pragma busy_timeout = 5000")
    row = con.execute(sys.argv[2]).fetchone()
    print(int(row[0] or 0))
finally:
    con.close()
PY
}

last_job=0
if [ -f "$DB" ]; then
  pending=$(query_db "select count(*) from jobs where status in ('queued','running','cancel_requested')")
  [ "${pending:-0}" -gt 0 ] && exit 0
  last_job=$(query_db "select coalesce(max(coalesce(finished_at, created_at)), 0) from jobs")
fi

# ComfyUI 被网页端直接使用时，输出目录会在近期活跃
if find "$OUTPUT" -type f -newermt "-${IDLE_MINUTES} minutes" -print -quit 2>/dev/null | grep -q .; then
  exit 0
fi

last_log=$(stat -c %Y "$LOG" 2>/dev/null || echo 0)
now=$(date +%s)
last=$last_job
[ "${last_log:-0}" -gt "$last" ] && last=$last_log
# 没有任何活动记录（刚部署、还没出过图）时不主动停
[ "${last:-0}" -le 0 ] && exit 0

if [ $((now - last)) -ge $((IDLE_MINUTES * 60)) ]; then
  systemctl stop comfyui
  logger -t img-edit-idle "ComfyUI 闲置超过 ${IDLE_MINUTES} 分钟，已停止（释放显存）"
fi
