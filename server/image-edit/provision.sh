#!/usr/bin/env bash
# 一次性准备：ROCm PyTorch + diffusers + Qwen-Image-Edit-2511 权重。
# 本机是 AMD RX 7900 XTX（gfx1100）：官方 ROCm 轮子需要 rocm7.0，
# 并预加载 WSL 的 rocr4wsl HSA 运行时（/dev/dxg 路径），否则 torch 看不到显卡。
set -uo pipefail
exec >> /var/log/img-edit-provision.log 2>&1

echo "===== $(date '+%F %T') 开始 ====="
export HF_ENDPOINT=https://hf-mirror.com
export HF_HOME=/home/ubuntu/.cache/huggingface
export PIP_DISABLE_PIP_VERSION_CHECK=1

VENV=/home/ubuntu/.venvs/img-edit
PY="$VENV/bin/python"

HSA_WSL="$(ls -1 /opt/rocm-*/lib/libhsa-runtime64.so.1 2>/dev/null | head -n 1)"
if [ -n "$HSA_WSL" ]; then
  export LD_PRELOAD="$HSA_WSL"
else
  echo "!!! 找不到 /opt/rocm-*/lib/libhsa-runtime64.so.1，GPU 可能不可见"
fi

# torch 的 ROCm 轮子按 CentOS 布局链接 libgomp.so，Ubuntu 只提供 libgomp.so.1
if [ ! -e /usr/lib/x86_64-linux-gnu/libgomp.so ] && [ -e /usr/lib/x86_64-linux-gnu/libgomp.so.1 ]; then
  ln -s /usr/lib/x86_64-linux-gnu/libgomp.so.1 /usr/lib/x86_64-linux-gnu/libgomp.so && ldconfig
fi

if [ ! -x "$PY" ]; then
  echo "--- 创建 venv"
  python3 -m venv "$VENV" || exit 1
  "$VENV/bin/pip" install -q -U pip wheel setuptools || exit 1
fi

echo "--- 安装 ROCm 版 torch（rocm7.0 + 系统 rocr4wsl 运行时）"
"$VENV/bin/pip" install --upgrade --index-url https://download.pytorch.org/whl/rocm7.0 \
  "torch==2.10.0+rocm7.0" || exit 1
if ! "$PY" -c "import torch; assert torch.cuda.is_available()" ; then
  echo "!!! torch 看不到 GPU；20B 模型不能退回 CPU 轮子，先修好 ROCm 再重跑"
  exit 1
fi

echo "--- 安装 diffusers 等依赖"
"$VENV/bin/pip" install diffusers transformers accelerate safetensors sentencepiece protobuf \
  fastapi "uvicorn[standard]" pillow pydantic huggingface_hub || exit 1

echo "--- 环境自检"
"$PY" - <<'PY' || exit 1
import torch
print("torch", torch.__version__, "| hip", torch.version.hip, "| 显卡", torch.cuda.get_device_name(0))
PY

echo "--- 下载权重（可断点续传）"
"$PY" - <<'PY' || exit 1
from huggingface_hub import snapshot_download
path = snapshot_download(
    "Qwen/Qwen-Image-Edit-2511",
    local_dir="/home/ubuntu/models/Qwen-Image-Edit-2511",
    max_workers=4,
)
print("权重目录", path)
PY

du -sh /home/ubuntu/models/Qwen-Image-Edit-2511
echo "===== $(date '+%F %T') 完成 ====="
