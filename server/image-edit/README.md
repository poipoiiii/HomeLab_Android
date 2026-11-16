# 本地图片网关（Qwen 改图 + Krea2 生图）

本地图片网关：手机 App 提交照片和/或一句描述，网关排队调用本机 ComfyUI 出图，结果落盘并回传。支持两条工作流：

- `qwen_edit`：Qwen-Image-Edit-2511 改图（1–3 张参考图）。
- `krea2_t2i`：Krea2-Moody 文生图（纯文字）。

图片不离开家里的机器。旧「AI 修图」接口保持兼容，不传 `workflow` 时默认 `qwen_edit`。

本文只讲服务端：架构、部署、模型、接口、运维与调试。手机端配置与使用见 [Android 修图文档](../../docs/image-edit-android.md)；新的「AI 创作」安卓需求见 [creative-android-requirements.md](../../docs/creative-android-requirements.md)；完整设计与实测数据见 [creative-design.md](../../docs/creative-design.md) 和 [image-edit-design.md](../../docs/image-edit-design.md)。

## 1. 架构

```text
Android App（独立修图页 / 小砂糖工具）
        │  HTTP + Bearer Token（局域网 / Tailscale）
        ▼
img-edit-gateway :8094        ← 本文（systemd: img-edit-gateway.service）
  ├─ 鉴权、参数校验、输入缩略图（去 EXIF）
  ├─ SQLite job 表：queued / running / succeeded / failed / canceled
  ├─ 单并发 worker + 队列（上限 12）+ request_id 幂等 + 内容缓存
  ├─ 结果图 / 缩略图落盘，TTL 清理（结果 7 天、元数据 30 天）
        │  http://127.0.0.1:8188
        ▼
ComfyUI（systemd: comfyui.service）
  ├─ qwen_edit：UnetLoaderGGUF + Qwen2.5-VL 文本编码器 + KSampler
  └─ krea2_t2i：UNETLoader + Qwen3-VL 文本编码器 + KSampler
        ▼
~/ComfyUI/models/{diffusion_models,text_encoders,vae}
```

- 端口：网关 **8094**（8090 留给 Beszel，8091 账单，8092 电源面板，8093 宿主机代理）。
- 网关本身不 import torch，只依赖 FastAPI + httpx + Pillow；GPU 相关都在 ComfyUI 里。

## 2. 目录

```text
server/image-edit/
├─ docker-compose.yml        可选：只跑网关（mock / 有 GPU 的容器环境）
├─ .env.example              配置模板（真实 .env 不进仓库）
├─ provision.sh              一次性准备 ROCm torch + diffusers + 权重
├─ gateway/
│  ├─ app.py                 路由、鉴权、错误码
│  ├─ jobs.py                队列、worker、状态机、超时
│  ├─ backend.py             comfyui / diffusers / mock 三种适配器
│  ├─ imageops.py            解码、缩放、EXIF 清理、缩略图
│  ├─ storage.py             SQLite + 结果文件 + TTL 清理
│  ├─ schemas.py             请求/响应模型
│  ├─ requirements.txt
│  └─ Dockerfile
└─ data/                     运行期数据（gitignore）
   ├─ results/               结果 PNG 与缩略图
   ├─ inputs/                输入缩略图
   └─ jobs.sqlite            job 元数据
```

实际运行时本目录不是工作目录：代码同步到 `/home/ubuntu/img-edit/gateway`，数据在 `/home/ubuntu/img-edit/data`，配置在 `/home/ubuntu/img-edit/.env`（权限 600）。

## 3. 本机硬件与实测

| 项 | 值 |
| --- | --- |
| GPU | AMD Radeon RX 7900 XTX 24GB（gfx1100） |
| 内存 | WSL2 19GB，宿主机 32GB |
| 系统 | Ubuntu 24.04（WSL2），ROCm 7.2 + rocr4wsl |
| 推理 | ComfyUI + GGUF Q4_K_M（12.8GB）+ Qwen2.5-VL fp8（9.4GB）+ Qwen VAE |
| 单张耗时 | Qwen 改图 1088×1088、30 步、CFG 1.0 约 250 秒；首张含加载 3–5 分钟 |
| Krea2 生图 | 832×1216、10 步：冷启动 45.1s，热运行 5.0s（2026-11-16 实测） |
| 峰值显存 | 约 22GB |

为什么不用 diffusers/vLLM：bf16 权重共 55GB（transformer 39GB + 文本编码器 16GB），24GB 显存 + 19GB 内存放不下，`QwenImageEditPlusPipeline` 直接加载失败；GGUF 量化版才能在 24GB 卡上跑。

ROCm 的坑（只影响部署 `provision.sh`，网关不碰）：

- 必须用 `torch 2.10.0+rocm7.0`；`2.5.1+rocm6.2` 与本机 ROCm 7.2 混用会 Bus error。
- 必须预加载 WSL 的 HSA 运行时：`LD_PRELOAD=/opt/rocm-7.2.0/lib/libhsa-runtime64.so.1`，否则 `torch.cuda.is_available()` 为 False（走 `/dev/dxg`，不是 `/dev/kfd`）。
- 缺 `libgomp.so` 时补软链：`ln -s /usr/lib/x86_64-linux-gnu/libgomp.so.1 /usr/lib/x86_64-linux-gnu/libgomp.so`。

## 4. 部署（从零到可用）

### 4.1 准备模型与推理环境

```bash
# 1) 系统依赖与 ROCm 运行时（本机已装 rocm 7.2 + hsa-runtime-rocr4wsl-amdgpu）
sudo apt-get install -y libgomp1 libnuma1

# 2) 一次性准备：ROCm torch、diffusers、55GB bf16 权重（可续传）
#    首次运行约 1–2 小时，日志 /var/log/img-edit-provision.log
sudo install -m755 ~/homelab_android/server/image-edit/provision.sh /home/ubuntu/img-edit/provision.sh
sudo systemctl start img-edit-provision   # 或按 unit 安装后 start

# 3) 准备完成后停用，避免每次开机重跑 pip
sudo systemctl disable --now img-edit-provision
```

`provision.sh` 会：装 ROCm7 torch → 自检 `torch.cuda.is_available()`（不通过直接退出，不会退回 CPU 轮子）→ 装 diffusers/transformers → 用 `HF_ENDPOINT=https://hf-mirror.com` 下载 `Qwen/Qwen-Image-Edit-2511` 到 `/home/ubuntu/models/Qwen-Image-Edit-2511`。

### 4.2 ComfyUI 与 GGUF 模型

ComfyUI 已作为 `comfyui.service` 常驻（`127.0.0.1:8188`，已装 ComfyUI-GGUF 节点）。模型放到：

```text
~/ComfyUI/models/diffusion_models/qwen-image-edit-2511-Q4_K_M.gguf         12.8GB
~/ComfyUI/models/text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors        9.4GB
~/ComfyUI/models/vae/qwen_image_vae.safetensors                             254MB

# Krea2 文生图（v1「生图」）
~/ComfyUI/models/diffusion_models/Krea2-Moody-Mix-premium_int8_convrot.safetensors  14GB
~/ComfyUI/models/text_encoders/qwen3vl_4b_fp8_scaled.safetensors                     4.9GB
~/ComfyUI/models/vae/qwen_image_vae.safetensors                                      254MB
```

来源：`unsloth/Qwen-Image-Edit-2511-GGUF`、`Comfy-Org/Qwen-Image_ComfyUI`（`split_files/text_encoders`、`split_files/vae`）。下载在 WSL 内走 hf-mirror，落 ext4。

### 4.3 同步网关并建 venv

```bash
rsync -a --delete --exclude '__pycache__' \
  ~/homelab_android/server/image-edit/gateway/ /home/ubuntu/img-edit/gateway/

python3 -m venv /home/ubuntu/.venvs/img-edit-gateway
/home/ubuntu/.venvs/img-edit-gateway/bin/pip install -r /home/ubuntu/img-edit/gateway/requirements.txt
```

### 4.4 配置与 systemd

`/home/ubuntu/img-edit/.env`（600，`openssl rand -hex 24` 生成 Token）：

```ini
IMAGE_EDIT_TOKEN=<随机 48 位 hex>
BACKEND=comfyui
MODEL_NAME=Qwen/Qwen-Image-Edit-2511
COMFY_URL=http://127.0.0.1:8188
COMFY_UNET=qwen-image-edit-2511-Q4_K_M.gguf
COMFY_CLIP=qwen_2.5_vl_7b_fp8_scaled.safetensors
COMFY_VAE=qwen_image_vae.safetensors
COMFY_SHIFT=3.1
COMFY_SAMPLER=euler
COMFY_SCHEDULER=simple
COMFY_TIMEOUT=900
MAX_CONCURRENCY=1
MAX_QUEUE=12
MAX_REFS=3
MAX_BATCH=8
MAX_INPUT_MB=20
MAX_SIDE=2048
JOB_TIMEOUT_SECONDS=600
SYNC_WAIT_SECONDS=300
RESULT_DIR=/home/ubuntu/img-edit/data/results
INPUT_DIR=/home/ubuntu/img-edit/data/inputs
JOBS_DB=/home/ubuntu/img-edit/data/jobs.sqlite
RESULT_TTL_HOURS=168
JOB_RETENTION_DAYS=30
RESULT_MAX_GB=20
```

`/etc/systemd/system/img-edit-gateway.service`：

```ini
[Unit]
Description=img-edit gateway (Qwen-Image-Edit via ComfyUI)
After=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/img-edit/gateway
EnvironmentFile=/home/ubuntu/img-edit/.env
ExecStart=/home/ubuntu/.venvs/img-edit-gateway/bin/uvicorn app:app --host 0.0.0.0 --port 8094
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now img-edit-gateway
curl -s http://127.0.0.1:8094/health
# {"status":"ok","model":"Qwen/Qwen-Image-Edit-2511","backend":"comfyui", ...}
```

注意：`/home/ubuntu/img-edit/data` 必须归 `ubuntu` 所有，否则启动报 `unable to open database file`（`chown -R ubuntu:ubuntu`）。

## 5. 配置项

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `IMAGE_EDIT_TOKEN` | 必填 | 所有接口（`/health` 除外）都要 `Authorization: Bearer` |
| `BACKEND` | `mock` | `comfyui` / `diffusers` / `mock` |
| `COMFY_URL` | `http://127.0.0.1:8188` | ComfyUI 地址 |
| `COMFY_UNET` / `COMFY_CLIP` / `COMFY_VAE` | 见上 | 必须是 ComfyUI 能列出的文件名 |
| `COMFY_SHIFT` | `3.1` | `ModelSamplingAuraFlow` 的 shift |
| `COMFY_SAMPLER` / `COMFY_SCHEDULER` | `euler` / `simple` | KSampler 采样器与调度器 |
| `COMFY_TIMEOUT` | `900` | qwen_edit 单次出图（含加载）等待秒数 |
| `COMFY_QUEUE_WAIT` | `600` | ComfyUI 有外部任务时，网关最多等待秒数 |
| `KREA2_UNET` / `KREA2_CLIP` / `KREA2_VAE` | 见 `.env.example` | Krea2 文生图模型，必须与 ComfyUI 列出的文件名逐字一致 |
| `KREA2_SAMPLER` / `KREA2_SCHEDULER` | `euler_ancestral` / `beta` | Krea2 采样器与调度器 |
| `KREA2_TIMEOUT` | `300` | krea2_t2i 单次出图超时；冷启动 45s、热 5s |
| `MAX_CONCURRENCY` / `MAX_QUEUE` | `1` / `12` | 单卡单并发；队列满返回 429 |
| `MAX_REFS` / `MAX_BATCH` | `3` / `8` | 单 job 参考图上限 / 批量 job 上限 |
| `MAX_INPUT_MB` / `MAX_SIDE` | `20` / `2048` | 输入总量与最长边 |
| `JOB_TIMEOUT_SECONDS` | `1200` | 单 job 上限；实际还会按工作流 cap：qwen_edit 900s / krea2_t2i 300s |
| `RESULT_TTL_HOURS` / `JOB_RETENTION_DAYS` | `168` / `30` | 结果保留 7 天，元数据 30 天 |
| `RESULT_MAX_GB` | `20` | 结果目录总容量上限 |

## 6. API 摘要

除 `/health` 外全部要求 `Authorization: Bearer <IMAGE_EDIT_TOKEN>`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 服务/后端状态、队列深度、模型名、显存占用、ComfyUI 队列、当前工作流 |
| GET | `/v1/models` | 能力：最多参考图、批量上限、尺寸、`guidance_distilled`、工作流列表 |
| GET | `/v1/workflows` | 工作流能力清单：`qwen_edit` / `krea2_t2i`、尺寸、步数、预计耗时 |
| POST | `/v1/jobs` | 创建单个异步任务（改图 1–3 张参考图；生图 0 张图） |
| POST | `/v1/jobs/batch` | 批量创建（2–8 个 job，原子校验） |
| GET | `/v1/jobs` | 列表：`status=all/active/succeeded/failed/canceled`、`limit`、`cursor` |
| GET | `/v1/jobs/{id}` | 单个任务状态与结果元信息 |
| DELETE | `/v1/jobs/{id}` | 排队中直接取消；运行中 `cancel_requested` 尽力中止；已结束的任务**彻底删除**（记录 + 结果图 + 缩略图） |
| GET | `/v1/jobs/{id}/inputs/{i}.jpg` | 输入缩略图（≤320px，去 EXIF），支持 `?token=` |
| GET | `/v1/results/{id}.png` | 结果图；`?max_side=320` 返回缩略图；支持 `?token=` |
| POST | `/v1/edit` | 同步便捷接口（内部等 job，给 curl/调试用） |

要点：

- `request_id` 15 分钟内幂等，重复提交返回原 job（`deduplicated: true`）；相同图片 + 参数 24 小时内命中缓存（`cached: true`）。
- 状态机：`queued → running → succeeded/failed`；取消走 `cancel_requested → canceled`。网关重启时遗留的 `running` 标记为 `failed`（`server_restarted`）。
- 结果 URL 过期后返回 404 `result_expired`，但 job 元数据仍可查。
- 日志只记录 `request_id`、`job_id`、状态、耗时、字节数，不记录图片、prompt 原文或 Token。

### 6.1 改图 / 生图示例

所有任务共用 `/v1/jobs`；`workflow` 不传时默认 `qwen_edit`，旧客户端无需改。

改图：

```json
{
  "workflow": "qwen_edit",
  "prompt": "把背景换成傍晚海边，保留五官和衣服",
  "images": [{ "mime": "image/jpeg", "b64": "..." }],
  "size": "1024x1024",
  "steps": 30,
  "request_id": "creative-demo-1"
}
```

生图：

```json
{
  "workflow": "krea2_t2i",
  "mode": "generate",
  "prompt": "雨夜咖啡馆，35mm 胶片，暖色室内光",
  "images": [],
  "size": "832x1216",
  "steps": 10,
  "request_id": "creative-demo-2"
}
```

- `krea2_t2i` 只接受 `1024x1024` / `832x1216` / `1216x832` / `768x1344` / `1344x768`。
- `qwen_edit` 的 `size` 可省略，按主图比例自动选；`steps` 20–60。
- 任务返回里的 `workflow`、`mode`、`stage` 用于 App 展示；`stage` 可能是 `queued / waiting_gpu / loading / sampling / decoding / finalizing / done`。

## 7. 运维

```bash
# 状态与日志
systemctl status img-edit-gateway
journalctl -u img-edit-gateway -n 100 --no-pager

# 健康与队列
curl -s http://127.0.0.1:8094/health

# 更新代码后
rsync -a --delete --exclude '__pycache__' \
  ~/homelab_android/server/image-edit/gateway/ /home/ubuntu/img-edit/gateway/
systemctl restart img-edit-gateway

# ComfyUI
systemctl status comfyui
curl -s http://127.0.0.1:8188/system_stats
```

- 数据目录：`/home/ubuntu/img-edit/data`（结果/输入缩略图/SQLite）。清理策略由网关定时执行，也可手工删 `results/` 里的旧文件。
- **闲置自动停**：`img-edit-comfy-idle.timer` 每分钟跑一次 `img-edit-comfy-idle.sh`，ComfyUI 连续 30 分钟没有修图活动（网关 job、ComfyUI 队列、输出目录、serve.log 都算活动）就 `systemctl stop comfyui`，释放约 16GB 显存；有任务在跑/排队时不动。改时长：`systemctl edit img-edit-comfy-idle.service` 覆盖 `IDLE_MINUTES=30`。
- **自动唤醒**：网关出图前会探测 ComfyUI，连接不上就执行 `sudo -n systemctl start comfyui.service`（sudoers 只放行这一条），最多等 3 分钟。因此闲置休眠后第一次提交会多 3–5 分钟加载模型，`/health` 期间显示 `warming_up`、`detail` 为 `sleeping`。
- **省显存**：ComfyUI 也在电源面板（`宿主机服务` 组）里，可以手动启停；网关本身很轻，不建议停。
- `~/ComfyUI/output/img_edit/` 会累积每次出图的原图，ComfyUI 不自动清理，定期删。
- 更换量化档（Q4→Q6/Q8）只改 `COMFY_UNET` 并放模型，然后重启网关；显存不够会明显变慢或 OOM。
- **模型切换**：网关记录最近成功的 `workflow`。`qwen_edit` 和 `krea2_t2i` 不能同时常驻，切换时网关会调用 ComfyUI `/free`，再加载新模型；Krea2 冷启动约 45s，Qwen 编辑约 3–5 分钟。切换失败只记日志，下一次任务还会重试。
- **外部任务**：不要再直接向 8188 提交 ref2va/视频脚本；网关提交前会检查 ComfyUI `/queue`，有外部任务时任务停在 `waiting_gpu`，最多等 `COMFY_QUEUE_WAIT` 秒。
- **升级前备份**：第一次部署带 `workflow` 列的版本前，先备份 SQLite：
  ```bash
  cp /home/ubuntu/img-edit/data/jobs.sqlite \
     /home/ubuntu/img-edit/data/jobs.sqlite.bak-$(date +%Y%m%d%H%M)
  ```
  新列由 `Storage.init()` 增量添加，旧 job 默认按 `qwen_edit` 处理。

## 8. 网络

- 网关监听 `0.0.0.0:8094`，无公网映射。
- **Tailscale 跑在 WSL 里**（节点名 `home`），不是 Windows 客户端。手机直接访问：

  ```bash
  # 在 WSL 里查节点地址
  wsl -d UbuntuD -- tailscale ip -4
  # 例：http://100.64.0.10:8094  或 MagicDNS：http://home.<tailnet>.ts.net:8094
  ```

  不需要 Windows 端口转发；只要手机和这个节点在同一个 tailnet，且 ACL 放行 8094。
- Windows 本机可用 `http://localhost:8094`（WSL localhost 转发）。
- 如果 WSL 里没有 Tailscale（或想走 Windows 客户端），再考虑 `netsh portproxy` 到 WSL IP，或 frp 隧道；注意 WSL IP 每次重启会变。

## 9. 调参与容量

| 需求 | 调整 |
| --- | --- |
| 更快 | `steps=20`，最长边 1024；质量略降 |
| 更清晰 | 最长边 1536–2048、steps 30–40；单张 5–8 分钟 |
| 显存不足/OOM | 换 Q3_K_M，或降最长边；`COMFY_SHIFT` 保持 3.1 |
| 批量排队太久 | 客户端拆成多次提交；服务端 `MAX_QUEUE=12` 是硬上限 |
| 冷启动慢 | 首次加载 GGUF + 文本编码器 3–5 分钟，属正常；闲置唤醒也是同样耗时，`COMFY_TIMEOUT` 不要低于 1200 |

CFG 固定 1.0：该系列是 guidance-distilled，套 SD 的 7.5 会过曝；负向提示词在这个节点里不生效。

## 10. 排错

| 现象 | 处理 |
| --- | --- |
| 启动报 `unable to open database file` | `chown -R ubuntu:ubuntu /home/ubuntu/img-edit/data` |
| `/health` 显示 `failed: All connection attempts failed` | ComfyUI 没起：`systemctl status comfyui` |
| `/health` 偶尔 `failed: timed out` / `degraded` | 出图时 ComfyUI 主线程忙，`/system_stats` 超时；空闲会恢复，不是故障 |
| `torch.cuda.is_available()` 为 False | 忘记 `LD_PRELOAD` 或 torch 版本不对（需 2.10+rocm7.0） |
| `import torch` 报 Bus error | torch 6.2 轮子与系统 ROCm 7.2 混用，重装 rocm7.0 版本 |
| ComfyUI 报 `unet_name` 不存在 | 模型文件名与 `COMFY_UNET` 不一致，或 ComfyUI 未刷新目录（重启 `comfyui`） |
| ComfyUI 报找不到 `qwen_image` 类型 | ComfyUI 版本过旧，需要支持 Qwen Image Edit 的版本 |
| 任务一直 running | 看 `journalctl -u img-edit-gateway` 与 `~/ComfyUI/serve.log`；单张约 4 分钟 |
| 任务停在 `waiting_gpu` | ComfyUI 队列里有外部任务（如视频脚本）；等它结束，或把 `COMFY_QUEUE_WAIT` 调大 |
| Krea2 报 `unet_name` / `clip_name` 不存在 | `KREA2_UNET` / `KREA2_CLIP` / `KREA2_VAE` 与 ComfyUI 列出的文件名不一致；逐字核对，不要改名模型 |
| 生图报 `images_not_allowed` 或图片数量错误 | `krea2_t2i` 的 `images` 必须为空；改图才传 1–3 张 |
| 改图后第一次生图很慢 | 正常，网关切换模型会 `/free` 并重新加载；Krea2 冷启动约 45s |
| `/v1/workflows` 401 | 新接口也要 Bearer Token；`/health` 不需要 |
| 429 | 队列满（12），等前面的任务 |
| 结果 404 `result_expired` | 超过 7 天，已清理 |

## 11. 调试方案

### 11.1 只看服务，不动 GPU

```bash
# 健康：queue/workflows/comfy_queue/loaded_workflow/gpu_busy
curl -s http://127.0.0.1:8094/health | python3 -m json.tool

# 能力清单（需要 Bearer Token）
curl -s http://127.0.0.1:8094/v1/workflows \
  -H "Authorization: Bearer $IMAGE_EDIT_TOKEN" | python3 -m json.tool

# 网关日志：看 job 状态、workflow、耗时；不要打印 .env
journalctl -u img-edit-gateway -n 100 --no-pager | tail -100

# ComfyUI 自身
systemctl status comfyui --no-pager
curl -s http://127.0.0.1:8188/queue | python3 -m json.tool
curl -s http://127.0.0.1:8188/system_stats | python3 -m json.tool
```

### 11.2 用 curl 跑生图（不需要照片）

```bash
curl -s -X POST http://127.0.0.1:8094/v1/jobs \
  -H "Authorization: Bearer $IMAGE_EDIT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "workflow": "krea2_t2i",
    "mode": "generate",
    "prompt": "雨夜咖啡馆，35mm 胶片，暖色室内光",
    "label": "debug-krea2",
    "images": [],
    "size": "832x1216",
    "steps": 10,
    "request_id": "debug-krea2-1"
  }'
```

预期：返回 202 和 `job_...`；`/v1/jobs/{id}` 的 `stage` 从 `queued` → `loading`/`sampling` → `finalizing`/`done`；成功后有 `result_url`。

```bash
curl -s http://127.0.0.1:8094/v1/jobs/<id> \
  -H "Authorization: Bearer $IMAGE_EDIT_TOKEN" | python3 -m json.tool

curl -s -o /tmp/debug-krea2.png \
  "http://127.0.0.1:8094/v1/results/<id>.png?token=$IMAGE_EDIT_TOKEN"
file /tmp/debug-krea2.png
```

### 11.3 用 curl 跑改图（需要照片）

```bash
IMG_B64=$(base64 -w0 /path/to/test.jpg)
curl -s -X POST http://127.0.0.1:8094/v1/jobs \
  -H "Authorization: Bearer $IMAGE_EDIT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"workflow\": \"qwen_edit\",
    \"mode\": \"edit\",
    \"prompt\": \"把背景换成傍晚海边，保留五官和衣服\",
    \"images\": [{\"mime\": \"image/jpeg\", \"b64\": \"$IMG_B64\"}],
    \"size\": \"1024x1024\",
    \"steps\": 30,
    \"request_id\": \"debug-qwen-1\"
  }"
```

注意：`$IMG_B64` 很长，只在本机 shell 里用；不要把它、Token 或 `prompt` 原文贴进日志/截图/提交。

### 11.4 模型切换与队列

- `/health` 的 `loaded_workflow` 是网关最近一次成功提交的工作流，不是 ComfyUI 的真实加载状态；以 `comfy_queue` 和日志为准。
- 看到 `waiting_gpu`：说明 ComfyUI `/queue` 有外部任务（例如 ref2va 视频脚本）。等它结束，或调大 `COMFY_QUEUE_WAIT`；不要同时手动往 8188 提交。
- 改图 ↔ 生图第一次切换会 `/free` 并重新加载模型，Krea2 约 45s，Qwen 编辑约 3–5 分钟；这是预期耗时，不是卡死。
- 如果 `/free` 失败，日志会有 `切换工作流时 /free 失败`；任务仍会继续，ComfyUI 可能自行卸载/加载。

### 11.5 数据库与迁移

```bash
# 升级前备份
cp /home/ubuntu/img-edit/data/jobs.sqlite \
   /home/ubuntu/img-edit/data/jobs.sqlite.bak-$(date +%Y%m%d%H%M)

# 看新列；不要直接改库
python3 - <<'PY'
import sqlite3
con = sqlite3.connect('/home/ubuntu/img-edit/data/jobs.sqlite')
for row in con.execute('select id, workflow, mode, status, stage, created_at from jobs order by rowid desc limit 10'):
    print(row)
PY
```

### 11.6 不接 GPU 的联调

```bash
# 只验证网关协议和 App 链路
BACKEND=mock IMAGE_EDIT_TOKEN=debug-token python3 -m uvicorn app:app --host 127.0.0.1 --port 8094
```

`mock` 后端对 `qwen_edit`/`krea2_t2i` 都返回占位图，不加载模型；适合 Android 端先跑通上传、轮询、预览、自动保存。不要在生产 `.env` 里用 `BACKEND=mock`。

### 11.7 部署与回滚

```bash
# 同步 + 备份 + 重启
rsync -a --delete --exclude '__pycache__' \
  ~/homelab_android/server/image-edit/gateway/ /home/ubuntu/img-edit/gateway/
cp /home/ubuntu/img-edit/data/jobs.sqlite /home/ubuntu/img-edit/data/jobs.sqlite.bak-$(date +%Y%m%d%H%M)
sudo systemctl restart img-edit-gateway
curl -s http://127.0.0.1:8094/health

# 回滚：恢复上一版代码/数据库后重启
sudo systemctl restart img-edit-gateway
```

**本轮不自动重启线上网关、不跑端到端测试**；上面的命令是给部署时用的。
