# 家庭服务器 Qwen-Image-Edit-2511 修图服务设计稿

> 状态：已实现（2026-11-02）。推理后端从 vLLM-Omni 改为 **ComfyUI + GGUF 量化**，原因见 §4.2 的本机实测。
> 范围：客户端（`poi's home` / 小砂糖）+ 服务端（家里部署的模型与网关）
> 目标：在 AI 对话和独立修图页里，用一句中文直接改相册照片；图片只走家里的服务器，不经过第三方 AI。

## 1. 一句话描述

小砂糖或独立修图页把用户指定的 1–3 张照片和一句修图指令发给家里的 `img-edit-gateway`；网关把任务排进队列，调用 Qwen-Image-Edit-2511 生成结果图，App 下载后保存到相册，并在对话或任务中心里显示缩略图、进度与历史。

## 2. 目标与非目标

### 目标

- 新增 Agent 工具：`edit_photo`、`batch_edit_photos`、`list_image_edit_jobs`、`save_image_edit_result`。
- 新增独立「AI 修图」页：选图、指令、效果预设、高级参数、前后对比、保存/分享/传 Cloudreve。
- 新增任务中心：异步任务、批量修图、队列位置、取消、重试、历史列表、结果补存。
- 服务端一次性交付：模型部署 + 网关 + 异步 job + 批量接口 + 结果/缩略图存储 + Token 鉴权 + 健康检查 + 清理策略。
- 支持 1–3 张参考图（多图合成/人物放进场景），中文指令；批量时一次最多 8 张原图。
- 图片只经过家庭服务器与 Tailscale，不发送给 DeepSeek/OpenAI 等服务商。
- 失败可解释：未配置、服务离线、忙、超时、图片过大、授权缺失、结果过期分别给出明确提示。
- 结果自动保存到相册，并能在对话里点开看大图，可继续二次修改。
- 任务在服务端异步执行；App 切后台、断网或进程被杀后，重新打开修图页能同步状态并补存结果。

### 非目标

- 不做文生图（继续用现有 `generate_image`）。
- 不做视频、3D、换脸训练、自定义 LoRA 训练；模型自带的 LoRA 效果通过提示词使用，不提供 LoRA 管理界面。
- 不把模型跑在手机上。
- 不暴露到公网，不做多租户、账号体系、计费。
- 不伪造扩散步进度百分比：后端不提供逐 step 进度，只展示排队位置、已用时间和阶段。
- 不改动已有文件，不覆盖原图；结果只新增到相册。
- 不做通用云端相册：服务端结果与输入缩略图只保留 7 天，手机相册里的副本由用户自己管理。

## 3. 总体架构

```text
小砂糖（ADK LlmAgent）                  独立「AI 修图」页
  └─ AgentTools                           └─ ImageEditRepository
       └──────────────┬──────────────────────────┘
                      ▼
              ImageEditClient（OkHttp）
                      │  HTTP + Bearer Token（Tailscale / 局域网）
                      ▼
           家庭服务器 img-edit-gateway :8094
             ├─ Token 校验、参数校验、输入缩略图（去 EXIF）
             ├─ SQLite job 表：queued / running / succeeded / failed / canceled
             ├─ 单并发 worker + 队列 + request_id 幂等 + 内容缓存
             ├─ 结果图与缩略图落盘，返回下载 URL
             └─ 结果 7 天 / 元数据 30 天 TTL 清理
                      │  内网 HTTP
                      ▼
            vLLM-Omni（Qwen/Qwen-Image-Edit-2511）:8092
```

### 为什么不直连 vLLM-Omni

1. vLLM-Omni 默认没有鉴权，直接暴露等于整条 tailnet 都能白用 GPU。
2. 手机网络切换/锁屏容易断长连接，网关用 job + `request_id` 做幂等，重试不会重复出图。
3. 统一错误码、输入大小限制、EXIF 清理、队列、任务历史和结果保留策略；App 不需要理解 vLLM 细节。
4. 以后换后端（fp8、diffusers、ComfyUI、更强的 2512/2.0）只改网关，不动 App。

## 4. 服务端设计

### 4.1 模型与推理后端

- 模型：`Qwen/Qwen-Image-Edit-2511`（2025-12 发布，重点提升角色/多人一致性，内置部分社区 LoRA 效果，增强工业设计与几何推理）。开源权重，License 以模型卡为准。
- 结构：约 20B MMDiT + Qwen2.5-VL 文本编码器；支持多图输入（本项目按最多 3 张设计，实际上限以模型卡为准）。
- 首选后端：**vLLM-Omni** 在线服务，暴露 OpenAI 风格 `/v1/chat/completions`，图片编辑结果在 `choices[0].message.content[].image_url.url` 返回 data URI。
- 备选后端：Diffusers `QwenImageEditPlusPipeline`（1–3 张参考图）或 ComfyUI workflow。网关用 `BACKEND` 环境变量切换，App 无感。
- 官方说明线上版本有加速优化，追求效果建议本地部署。

启动示例（版本与参数以实际安装的 vLLM-Omni 为准）：

```bash
vllm serve Qwen/Qwen-Image-Edit-2511 --omni --port 8092
# 显存紧张时按官方文档追加：--vae-use-slicing --vae-use-tiling
```

### 4.2 硬件与显存

模型只部署在家里服务器，不涉及手机性能。以下是部署前需要实测确认的量级：

| 显存 | 可行性 | 取舍 |
| --- | --- | --- |
| 80GB（A100/H100 级） | 推荐 | bf16，1 并发，输入 1536 边长较稳 |
| 48GB（L40S / A6000 48G 级） | 可用 | 需限制参考图数量/分辨率，开启 VAE slicing/tiling |
| 24GB（3090/4090 级） | 勉强 | 需 fp8/量化或 CPU offload，单张耗时明显上升 |
| < 24GB | 不建议 | 量化/offload 后体验不可接受 |

补充：社区部署记录显示 bf16 全量加载约需 40GB+ 显存；权重与 HF 缓存建议预留 100GB 以上磁盘；内存建议 32GB 以上。以上为经验值，最终以家里机器实测为准，**不写死到 App**。

**本机实测（2026-11-02，AMD RX 7900 XTX 24GB + WSL2 19GB 内存）**：

- GPU 计算要在 WSL 里用 ROCm：`torch 2.10.0+rocm7.0`，并设置 `LD_PRELOAD=/opt/rocm-7.2.0/lib/libhsa-runtime64.so.1`（rocr4wsl 运行时，走 `/dev/dxg`）。不预加载时 `torch.cuda.is_available()` 为 False；`torch 2.5.1+rocm6.2` 与本机 ROCm 7.2 混用会直接 Bus error。
- bf16 权重共 55GB（transformer 39GB + text encoder 16GB），24GB 显存 + 19GB 内存放不下，diffusers 的 `QwenImageEditPlusPipeline` 在本机加载不起来。
- 实际路线：**ComfyUI + GGUF**。`qwen-image-edit-2511-Q4_K_M.gguf`（12.8GB）+ `qwen_2.5_vl_7b_fp8_scaled`（9.4GB）+ `qwen_image_vae`（254MB），ComfyUI 用 `TextEncodeQwenImageEditPlus` + `ModelSamplingAuraFlow(shift=3.1)` + `KSampler(cfg=1.0)`。
- 实测出图：1088×1088、30 步、CFG 1.0，**单张约 250 秒**（含首次加载；第二张热机同样约 255 秒），峰值显存约 22GB，出图期间 `/system_stats` 偶尔超时属正常。
- 因此 App 的默认步数建议按需下调到 20–24（设置页可调）；批量 8 张约 30 分钟量级，不要一次塞满队列。

### 4.3 目录结构

放在本仓库 `server/image-edit/`（不参与 APK 构建）：

```text
server/image-edit/
  docker-compose.yml
  .env.example
  gateway/
    Dockerfile
    requirements.txt        # fastapi、uvicorn、httpx、pillow
    app.py                  # 路由、鉴权、错误码
    jobs.py                 # job 状态机、队列、worker
    backend.py              # vLLM-Omni / diffusers 适配器
    imageops.py             # 校验、EXIF 清理、缩放、缩略图
    storage.py              # SQLite 元数据 + 结果文件 + TTL 清理
    schemas.py              # 请求/响应模型
  models/                   # 模型权重（gitignore）
  data/
    results/                # 结果图（gitignore）
    inputs/                 # 输入缩略图（gitignore）
    jobs.sqlite             # job 元数据（gitignore）
```

### 4.4 鉴权与 API 总览

除 `/health` 外都需要 `Authorization: Bearer <IMAGE_EDIT_TOKEN>`。Token 用 `openssl rand -hex 24` 生成，只存服务端 `.env` 和手机本机 DataStore。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 服务与后端状态、队列深度、模型名；App「测试连接」用 |
| GET | `/v1/models` | 模型标识与能力（最多参考图数、最大批量、支持尺寸） |
| POST | `/v1/jobs` | 创建单个异步修图任务 |
| POST | `/v1/jobs/batch` | 创建批量任务（2–8 个，原子校验） |
| GET | `/v1/jobs` | 任务列表，支持 `status`、`limit`、`cursor` |
| GET | `/v1/jobs/{id}` | 单个任务状态与结果元信息 |
| DELETE | `/v1/jobs/{id}` | 取消任务：排队中直接取消，运行中尽力中止 |
| GET | `/v1/jobs/{id}/inputs/{index}.jpg` | 输入缩略图（≤320px，去 EXIF） |
| GET | `/v1/results/{id}.png` | 结果图；`?max_side=320` 返回缩略图 |
| POST | `/v1/edit` | 同步便捷接口（内部等待 job 完成，供 curl/其他客户端） |

### 4.5 Job 状态机与队列

```text
queued ──▶ running ──▶ succeeded
   │           ├────▶ failed
   │           └────▶ cancel_requested ──▶ canceled
   └────▶ canceled（尚未开始）
```

- `stage`：`queued` / `running` / `finalizing`；**不提供百分比**。
- `queue_position`：仅排队中的任务返回，前面还有几个。
- 单 GPU 默认 `MAX_CONCURRENCY=1`；`MAX_QUEUE=12` 统计 queued + running。
- 批量提交前先校验容量；容量不足整批返回 429，不做部分入队。
- `request_id` 幂等：同一 ID 在 15 分钟内重复提交返回原 job，`deduplicated: true`。
- 内容缓存：相同图片 + prompt + 参数在 24 小时内命中成功结果时，返回原 job，`cached: true`，不重复占用 GPU。
- 网关重启时，`running` 状态的任务标记为 `failed`，错误码 `server_restarted`；App 可一键重试，不会假装还在跑。
- 运行中的取消是尽力而为：网关取消到 vLLM 的 HTTP 请求并丢弃结果；若后端已无法中止，任务可能仍以 `succeeded` 结束，UI 会如实显示。

### 4.6 创建单个任务

`POST /v1/jobs`：

```json
{
  "prompt": "把背景换成傍晚的海边，保留人物脸部和姿势不变",
  "label": "海边背景",
  "images": [
    { "mime": "image/jpeg", "b64": "<base64>" }
  ],
  "size": "1024x1024",
  "steps": 40,
  "guidance_scale": 1.0,
  "negative_prompt": "",
  "seed": 42,
  "request_id": "6f1c2f0e-9f3c-4b6d-9a4e-2c7b8d1a0e55"
}
```

约束：

| 项 | 规则 |
| --- | --- |
| `images` | 1–3 张；单张解码后 ≤ 8 MiB；总量 ≤ 20 MiB；只接受 JPEG/PNG/WebP |
| `prompt` | 必填，≤ 2000 字符；服务端只存 `label`，不存 prompt 原文 |
| `label` | 可选，≤ 40 字符；App 默认取指令前 20 字，任务卡展示用 |
| `size` | 可选，`宽x高`；留空由后端按输入比例选桶（推荐留空） |
| `steps` | 20–60，默认 40 |
| `guidance_scale` | 默认 1.0（该系列 guidance-distilled，不要套用 SD 的 7.5） |
| `seed` | 可选；不传随机。想复现同一结果时由 App 回传上次的值 |
| `request_id` | App 生成的 UUID；同一 ID 15 分钟内幂等 |

响应 `202`：

```json
{
  "id": "job_20261030_6f1c2f0e",
  "status": "queued",
  "queue_position": 2,
  "created_at": 1793520000,
  "deduplicated": false,
  "cached": false
}
```

### 4.7 创建批量任务

批量是「N 张原图 → N 个独立结果」，不是把 N 张图放进同一个 job。多图合成（图1 + 图2 生成一张）仍然用单 job 的 `images` 多图参考。

`POST /v1/jobs/batch`：

```json
{
  "jobs": [
    {
      "prompt": "换成雪景，保留人物",
      "label": "雪景-1",
      "images": [{ "mime": "image/jpeg", "b64": "<base64-1>" }],
      "steps": 40,
      "request_id": "uuid-1"
    },
    {
      "prompt": "换成雪景，保留人物",
      "label": "雪景-2",
      "images": [{ "mime": "image/jpeg", "b64": "<base64-2>" }],
      "steps": 40,
      "request_id": "uuid-2"
    }
  ]
}
```

- 一次 2–8 个 job；每个 job 仍然遵守 §4.6 的单 job 约束。
- 整个批量请求解码后的图片总量 ≤ 20 MiB（`MAX_INPUT_MB`）；超过时 App 拆成多批提交，不把 8 张大图硬塞一个请求。
- 服务端生成 `batch_id` 并在每个 job 上返回；App 用它分组任务卡。
- 响应 `202`：

```json
{
  "batch_id": "batch_20261030_ab12",
  "jobs": [
    { "id": "job_...1", "status": "queued", "queue_position": 3 },
    { "id": "job_...2", "status": "queued", "queue_position": 4 }
  ]
}
```

- 批量里的 `seed` 默认不传（每张随机）；用户显式填了 seed 时，所有 job 使用同一个 seed，便于复现整批效果。

### 4.8 查询、取消与任务对象

`GET /v1/jobs?status=all&limit=30&cursor=...`：

```json
{
  "jobs": [
    {
      "id": "job_20261030_6f1c2f0e",
      "batch_id": "batch_20261030_ab12",
      "label": "海边背景",
      "status": "succeeded",
      "stage": "finalizing",
      "queue_position": 0,
      "created_at": 1793520000,
      "started_at": 1793520003,
      "finished_at": 1793520022,
      "elapsed_ms": 18432,
      "input_count": 1,
      "input_thumbs": ["/v1/jobs/job_20261030_6f1c2f0e/inputs/0.jpg"],
      "width": 1024,
      "height": 1024,
      "bytes": 1812345,
      "thumb_url": "/v1/results/job_20261030_6f1c2f0e.png?max_side=320",
      "result_url": "/v1/results/job_20261030_6f1c2f0e.png",
      "cached": false,
      "error": null
    }
  ],
  "next_cursor": null
}
```

- `status` 过滤：`all` / `active`（queued + running + cancel_requested）/ `succeeded` / `failed` / `canceled`。
- 默认 `limit=20`，最大 50；按 `created_at` 倒序。
- 失败时 `error` 为 `{"code":"backend_timeout","message":"…"}`。
- 结果文件过期后，`result_url` 与 `thumb_url` 返回 `null`，`error.code = result_expired`，但 job 元数据仍可展示。
- `DELETE /v1/jobs/{id}`：
  - queued → `200 {"id":"…","status":"canceled"}`
  - running → `202 {"id":"…","status":"cancel_requested"}`，尽力中止
  - 已结束 → `409 job_finished`

### 4.9 同步便捷接口

`POST /v1/edit` 与 `POST /v1/jobs` 请求体相同，网关内部创建 job 并等待最多 `SYNC_WAIT_SECONDS=240`：

- 完成：`200`，返回与 job 详情一致的对象，并附带 `image` 字段（`url/width/height/bytes/sha256`）。
- 仍在运行：`202`，返回 job 对象；调用方改用 `GET /v1/jobs/{id}` 轮询。
- 这个接口只给 curl、调试和其他客户端用；App 统一走异步 job，避免长连接。

### 4.10 结果图与缩略图

- 结果按后端返回的 PNG 原样保存，文件名用 job id。
- `GET /v1/results/{id}.png` 支持 `?max_side=320`：首次生成 JPEG 缩略图并缓存到 `data/results/{id}_{max_side}.jpg`，最大边限制 128–2048。
- 输入图只存缩略图（≤320px JPEG q80，去 EXIF），路径 `data/inputs/{job_id}_{index}.jpg`，用于任务卡和前后对比。
- 响应头：`Cache-Control: private, max-age=604800, immutable`；过期或文件被清理返回 `404 result_expired`。

### 4.11 网关到 vLLM-Omni 的映射

网关调用 `POST {VLLM_BASE_URL}/v1/chat/completions`：

```json
{
  "model": "Qwen/Qwen-Image-Edit-2511",
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "<prompt>" },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<b64>" } },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<b64-2>" } }
      ]
    }
  ],
  "extra_body": {
    "num_inference_steps": 40,
    "guidance_scale": 1.0,
    "seed": 42,
    "negative_prompt": "",
    "height": 1024,
    "width": 1024
  }
}
```

要点与陷阱：

- 用 `requests`/`curl` 调 vLLM-Omni 时，生成参数必须包在字面的 `"extra_body"` 键里；OpenAI SDK 的 `extra_body` 参数会自动摊平，二者行为不同。
- 多图**顺序有意义**：App 传的顺序即图1/图2/图3，prompt 里的「图2的人」按这个顺序解释。
- `size` 在网关侧拆成 `height`/`width`；不要同时传 `size` 与 `height/width`，避免版本差异。
- 返回内容可能是 `content` 数组，也可能被某些版本包成字符串；适配器要同时兼容，取 `image_url.url` 的 data URI 后剥掉前缀存盘。
- 后端启动/首次推理可能很慢，网关单 job 超时 240s，vLLM 侧 300s；App 轮询总时长 300s。
- 单卡扩散模型并发收益有限，网关默认单并发；队列满返回 429，不无限堆积。

### 4.12 图片预处理与存储

- 用 Pillow `Image.open` + `verify()` 校验，限制总像素 ≤ 4000 万，防解压炸弹。
- `ImageOps.exif_transpose` 纠正方向，转 RGB；**重新编码以去掉 EXIF/GPS**（隐私）。
- 输入最长边超过 2048 时等比缩小；App 已压到 1536，正常情况下不会二次缩放。
- 输入缩略图与结果图分开存；SQLite 只存元数据与文件路径。
- SQLite 表 `jobs` 关键字段：

```text
id / request_id / batch_id / label / status / stage
created_at / started_at / finished_at / elapsed_ms
input_count / input_thumbs / prompt_hash / prompt_len
steps / size / seed / guidance_scale
result_path / result_sha256 / width / height / bytes
error_code / error_message / cache_key / cached
```

- 不存 prompt 原文，只存哈希、长度和用户可见的 `label`。
- 清理：结果图与输入缩略图保留 7 天（`RESULT_TTL_HOURS`），job 元数据保留 30 天（`JOB_RETENTION_DAYS`），每小时清理一次；目录总量超过 20GB 时按最旧优先删除。手机相册里的副本不受影响。

### 4.13 部署

`docker-compose.yml` 结构（镜像 tag 以 vLLM-Omni 发布页为准，不要照抄占位符上线）：

```yaml
services:
  vllm:
    image: <vllm-omni-official-image>
    command:
      - vllm
      - serve
      - /models/Qwen-Image-Edit-2511
      - --omni
      - --port
      - "8092"
    volumes:
      - ./models:/models:ro
      - ./hf-cache:/root/.cache/huggingface
    ports:
      - "127.0.0.1:8092:8092"     # 只给网关访问，不直接暴露
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
    restart: unless-stopped

  gateway:
    build: ./gateway
    env_file: .env
    volumes:
      - ./data:/data
    ports:
      - "8094:8090"
    depends_on:
      - vllm
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8090/health"]
      interval: 30s
      timeout: 5s
      retries: 3
    restart: unless-stopped
```

`.env.example`：

```text
IMAGE_EDIT_TOKEN=please-change-me
VLLM_BASE_URL=http://vllm:8092
MODEL_NAME=Qwen/Qwen-Image-Edit-2511
BACKEND=vllm
MAX_CONCURRENCY=1
MAX_QUEUE=12
MAX_REFS=3
MAX_BATCH=8
MAX_INPUT_MB=20
MAX_SIDE=2048
JOB_TIMEOUT_SECONDS=240
SYNC_WAIT_SECONDS=240
RESULT_DIR=/data/results
INPUT_DIR=/data/inputs
JOBS_DB=/data/jobs.sqlite
RESULT_TTL_HOURS=168
JOB_RETENTION_DAYS=30
RESULT_MAX_GB=20
```

模型下载（二选一）：

```bash
# 官方推荐的 ModelScope
modelscope download --model Qwen/Qwen-Image-Edit-2511 --local_dir ./models/Qwen-Image-Edit-2511

# 或 Hugging Face
huggingface-cli download Qwen/Qwen-Image-Edit-2511 --local-dir ./models/Qwen-Image-Edit-2511
```

网络：

- 服务器装 Tailscale，手机用 MagicDNS 主机名访问 `http://<tailnet-host>:8094`；只在 tailnet 内，不映射公网端口。
- 需要 HTTPS 时用 `tailscale serve` 反代，网关本身保持 HTTP。
- Tailscale ACL 只允许自己的设备访问 8094；8092 不要开放。

启动顺序与冷启动：

1. 下载权重 → 起 `vllm` → 等 `/v1/models` 可访问（首次加载可能几分钟）。
2. 起 `gateway` → `curl /health` 返回 `backend: ok`。
3. 手机设置页点「测试连接」，再做一次 1024×1024 的真实修图验证。

### 4.14 观测与运维

- 访问日志只记录：`request_id`、`job_id`、`status`、`elapsed_ms`、`input_bytes`、`output_bytes`、`cached`、`queue_depth`。
- **不记录** base64、图片内容、prompt 原文、Token。
- `/health` 返回：`status`（`ok` / `degraded` / `warming_up`）、`model`、`backend`、`queue`（running/queued/capacity）、`gpu_vram_used_mb`（可读则返回）。
- vLLM 有 `/health` 时用它探活；版本不一致时退回 `/v1/models`。
- 网关不写系统临时目录大片文件；结果、缩略图、SQLite、日志都在 `data/` 下，随部署目录迁移。

### 4.15 提示词优化（可选，`PROMPT_OPTIMIZER`）

动因：用户习惯说「给我 P 个腹肌」这类口语，扩散模型需要的是「改哪里、什么效果、保留什么」的具体描述。

- 位置：网关 worker 内，`backend.generate()` 之前。改写失败、超时或模型缺失时静默回退用户原文，不影响出图。
- 实现：`prompts.py` 的 `PromptOptimizer`，两种模式——`llama`（进程内 llama.cpp 读 GGUF，无需额外服务）与 `openai`（Ollama 等 OpenAI 兼容接口）。
- 模型：`Qwen2.5-3B-Instruct Q4_K_M`（2.0GB），懒加载，CPU 4 线程，不占显存。
- 提示词约束：只描述像素级修改，必须写清保留项（身份/五官/发型/姿势/服装/构图/背景），禁止新增用户没提的动作与场景，输出 40–120 字，附两条 few-shot。
- 缓存：job 的 `cache_key` 仍按用户原文计算，避免同一原文因改写波动导致缓存失效。
- 实测：改写一次约 16–23s；网关 RSS 约 3.6GB（其中 2.1GB 是 GGUF 的 mmap 文件页，可回收，匿名内存约 1.5GB）。
- 调试：`POST /v1/prompts/optimize`（Bearer Token）直接返回改写结果与 `changed`。

## 5. 客户端设计

### 5.1 新增配置项

`core/settings/SettingsStore.kt` 的 `AppSettings` 增加：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `imageEditUrl` | `""` | 网关地址，如 `http://home-server:8094`（本机实际端口，8090 留给 Beszel）；空 = 未启用 |
| `imageEditToken` | `""` | Bearer Token，只存本机 DataStore |
| `imageEditModel` | `"Qwen/Qwen-Image-Edit-2511"` | 展示与服务端校验用 |
| `imageEditSteps` | `24` | 20–60；本机实测约 8 秒/步，40 步单张要 5 分钟以上 |
| `imageEditMaxSide` | `1536` | 上传前压缩的最长边，768–2048 |
| `imageEditAutoSave` | `true` | 任务成功后自动保存到相册 |

DataStore 键前缀 `image_edit_`；`allowBackup=false` 已有，Token 不进日志、不进文档、不进提交。

另建一个小 DataStore（`homelab_image_edit`）存 `saved_job_ids`（最多 300 个），用于跨进程避免重复保存同一结果；不存图片内容与 Token。

### 5.2 入口与设置

- 设置 → 小砂糖 → 新增「AI 修图」卡片：显示「已配置 / 未配置」+「打开修图」「服务设置」。
- 首页右下角快捷面板（`QuickPanelFab`）新增「AI 修图」。
- AI 页空状态提供一个「去修图」按钮；不改对话页主体布局。
- 独立设置页 `ImageEditSettingsScreen`：
  - 网关地址、Token、模型名、步数、上传最长边、完成后自动保存；
  - 「测试连接」调 `/health`，显示「模型在线 · 队列 0」或失败原因；
  - 一行说明：图片只发给家里的服务器，不发送给上面的 AI 服务商。
- AI 配置页（`AiSettingsScreen`）只保留对话服务商，不再混入修图设置，避免两套地址/Key 互相干扰。

### 5.3 新增目录与文件

新增 `feature/imageedit/`：

| 文件 | 职责 |
| --- | --- |
| `ImageEditModels.kt` | 请求/响应/job/错误/预设的 `@Serializable` 模型 |
| `ImageEditClient.kt` | HTTP：health、创建 job、批量、列表、详情、取消、下载 |
| `ImageEditRepository.kt` | 上传前压缩、提交、轮询、保存相册、Cloudreve 上传、结果去重 |
| `ImageEditStore.kt` | DataStore：`saved_job_ids`（跨进程去重） |
| `ImageEditPresets.kt` | 效果预设与转换后的中文提示词 |
| `ui/ImageEditScreen.kt` | 独立修图页 + 任务中心 |
| `ui/ImageEditSettingsScreen.kt` | 服务地址、Token、参数与测试连接 |
| `ui/BeforeAfter.kt` | 拖动分割的前后对比组件 |

改动：

| 文件 | 改动 |
| --- | --- |
| `core/settings/SettingsStore.kt` | 新增 §5.1 字段与键 |
| `core/AppContainer.kt` | 新增 `imageEdit` 仓库与本地 Store |
| `feature/ai/AgentTools.kt` | 新增 4 个工具，注入 `ImageEditRepository` |
| `navigation/MainViewModel.kt` | 修图页状态、任务轮询、批量提交/取消/重试/保存、system prompt |
| `navigation/AppRoot.kt` | `imageEditOpen` / `imageEditSettingsOpen` 全屏 overlay |
| `feature/home/ui/HomeScreen.kt` | 快捷面板新增「AI 修图」 |
| `feature/settings/ui/SettingsScreen.kt` | 小砂糖分组增加「AI 修图」卡片 |
| `feature/ai/ui/AiScreen.kt` | 空状态入口；工具消息展示结果缩略图（沿用现有机制） |
| `feature/ai/DeviceTools.kt` | 新增 `encodePickedImage(uri, maxSide)`：给 `PickVisualMedia` 的临时授权 uri 用，跳过全局相册权限检查 |
| `AndroidManifest.xml` + `res/xml/file_paths.xml` | 分享结果需要 `FileProvider`；不新增运行时权限 |
| `feature/photos/CloudreveRepository.kt` | 复用 `uploadAll`；结果图以合成 `PhotoFile` 上传，不新增上传协议 |

`AndroidManifest.xml` 不新增权限；`FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` 已有，但本功能**不新增前台服务**：任务在服务端继续，App 回到修图页时同步。

### 5.4 ImageEditClient 与数据模型

```kotlin
class ImageEditClient(private val http: OkHttpClient, private val json: Json) {
    suspend fun health(baseUrl: String, token: String): ImageEditHealth
    suspend fun createJob(baseUrl: String, token: String, request: ImageEditRequest): ImageEditJob
    suspend fun createBatch(baseUrl: String, token: String, requests: List<ImageEditRequest>): ImageEditBatchResult
    suspend fun jobs(baseUrl: String, token: String, status: String, limit: Int, cursor: String?): ImageEditJobPage
    suspend fun job(baseUrl: String, token: String, id: String): ImageEditJob
    suspend fun cancel(baseUrl: String, token: String, id: String): ImageEditJob
    suspend fun download(baseUrl: String, token: String, path: String): ByteArray
}
```

```kotlin
@Serializable
data class ImageEditRequest(
    val prompt: String,
    val label: String? = null,
    val images: List<ImageEditInput>,
    val size: String? = null,
    val steps: Int = 40,
    @SerialName("guidance_scale") val guidanceScale: Double = 1.0,
    val seed: Int? = null,
    @SerialName("request_id") val requestId: String,
)

@Serializable
data class ImageEditInput(
    val mime: String = "image/jpeg",
    val b64: String,
)

@Serializable
data class ImageEditJob(
    val id: String,
    @SerialName("batch_id") val batchId: String? = null,
    val label: String = "",
    val status: String,                 // queued/running/cancel_requested/succeeded/failed/canceled
    val stage: String? = null,
    @SerialName("queue_position") val queuePosition: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
    @SerialName("input_count") val inputCount: Int = 1,
    @SerialName("input_thumbs") val inputThumbs: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("result_url") val resultUrl: String? = null,
    val cached: Boolean = false,
    val error: ImageEditError? = null,
)

@Serializable
data class ImageEditError(val code: String, val message: String)
```

- Base URL 规整：去尾部 `/`，若以 `/v1`、`/v1/jobs` 结尾则剥掉，再拼对应路径（参考现有 `AiClient.imagesEndpoint`）。
- 超时：每次调用用 `http.newBuilder()` 派生独立客户端，连接 10s、读取 60s（提交任务很快，下载大图用 120s），共享连接池，不改全局 OkHttp。
- 长轮询不在 Client 里做，放到 Repository：每 2 秒查一次 `GET /v1/jobs/{id}`，可取消，最长 300 秒。
- 缩略图用 Coil `AsyncImage`，通过 `ImageRequest` 注入 `Authorization` 头；结果大图按需下载字节，不走 Coil 缓存。
- 错误：解析 `{"error":{"code","message"}}`；非 JSON 时回退 `HTTP <code>`，截断 200 字符；502/503/504 提示反代/后端问题。

### 5.5 ImageEditRepository

```kotlin
class ImageEditRepository(
    private val client: ImageEditClient,
    private val device: DeviceTools,
    private val store: ImageEditStore,
    private val cloudreve: CloudreveRepository,
) {
    suspend fun submit(config: AppSettings, uris: List<String>, instruction: String, opts: EditOptions): ImageEditJob
    suspend fun submitBatch(config: AppSettings, uris: List<String>, instruction: String, opts: EditOptions): ImageEditBatchResult
    suspend fun await(config: AppSettings, jobId: String, timeoutSeconds: Long = 300): ImageEditJob
    suspend fun jobs(config: AppSettings, status: String = "all", limit: Int = 30): List<ImageEditJob>
    suspend fun cancel(config: AppSettings, jobId: String)
    suspend fun saveResult(config: AppSettings, job: ImageEditJob): SavedImage
    suspend fun saveAllSucceeded(config: AppSettings, jobs: List<ImageEditJob>): List<SavedImage>
    suspend fun uploadToCloudreve(config: AppSettings, job: ImageEditJob): BatchUploadResult
}

data class EditOptions(
    val size: String? = null,
    val steps: Int = 40,
    val seed: Int? = null,
)
```

- 提交前逐张压缩成 JPEG base64，`label` 默认取指令前 20 字。Agent 工具用 `device.imageBase64`（需要相册权限）；独立页用 `PickVisualMedia` 的临时授权 uri，加 `device.encodePickedImage`（不依赖 `READ_MEDIA_IMAGES`）。
- `await` 轮询 job；`queued`/`running` 不报错，继续等；`failed`/`canceled` 抛出带错误码的异常。
- `saveResult` 下载 `result_url` → `device.saveImage(bytes, "png")` → 记录 `saved_job_ids`；同一 job 不重复保存。
- `saveAllSucceeded` 用于页面重新进入时补存：只在 `imageEditAutoSave=true` 时，保存 `succeeded` 且不在 `saved_job_ids` 的结果。
- `uploadToCloudreve` 用结果图构造 `PhotoFile`（`gallery_uri` 或 `file://`、名称、时间、宽高、大小、`image/png`），复用现有 `CloudreveRepository.uploadAll`；content uri 走 `DeviceTools.photoBytes`，file uri 走 `FileInputStream`。

### 5.6 Agent 工具

`AgentTools` 注入 `ImageEditRepository`，新增四个工具。

#### edit_photo

```kotlin
@Tool(
    name = "edit_photo",
    description = "调用家里部署的 Qwen-Image-Edit-2511 按中文指令修改照片，成功后把结果保存到相册。" +
        "适合换背景、去路人、换风格、合成多图、商品图、加辅助线；" +
        "只应在用户明确要求修改某张照片时调用。",
)
suspend fun editPhoto(
    @Param("要修改的照片 uri，来自 find_photos 或上一次 edit_photo 返回的 gallery_uri") uri: String,
    @Param("修图指令，中文即可，例如：把背景换成傍晚海边，保留人物脸部和姿势不变") instruction: String,
    @Param("可选，额外参考图 uri，最多 2 张，用英文逗号分隔") refs: String? = null,
    @Param("可选，输出尺寸如 1024x1024；留空按输入比例") size: String? = null,
    @Param("可选，随机种子；想复现上次结果时传上次返回的 seed") seed: Int? = null,
): Map<String, Any?>
```

行为：

1. 校验 `settings.imageEditUrl` 非空，否则返回 `error` + `hint`（去 设置 → 小砂糖 → AI 修图）。
2. 解析 `uri` 与 `refs`（逗号分隔，去空、去重、最多 3 张）；读不到图时返回与 `describe_photo` 一致的修复提示（重新 `find_photos` 或传文件名）。
3. 提交单 job，`await` 最多 240 秒：
   - `succeeded` → 下载、保存相册、`onPhotos("edit_photo", listOf(保存后的 uri))`，返回完整结果。
   - 仍在 `queued`/`running` → 返回 `status`、`job_id`、`hint`，不报错；提示用户任务还在跑。
   - `failed`/`canceled` → 返回错误码与可读原因。
4. 返回精简 JSON，**绝不返回 base64**：

```json
{
  "ok": true,
  "status": "succeeded",
  "job_id": "job_20261030_6f1c2f0e",
  "model": "Qwen/Qwen-Image-Edit-2511",
  "instruction": "把背景换成傍晚海边，保留人物",
  "size": "1024x1024",
  "elapsed_ms": 18432,
  "seed": 42,
  "path": "…/Pictures/ai/ai_20261030_153012.png",
  "gallery_uri": "content://media/external/images/media/123456"
}
```

仍在运行时：

```json
{
  "ok": false,
  "status": "running",
  "job_id": "job_20261030_6f1c2f0e",
  "queue_position": 0,
  "elapsed_ms": 92000,
  "hint": "任务还在跑，稍后用 list_image_edit_jobs 查看，或用 save_image_edit_result 保存结果。"
}
```

#### batch_edit_photos

```kotlin
@Tool(
    name = "batch_edit_photos",
    description = "一次把 2–8 张照片按同一句指令批量提交给家里的 Qwen-Image-Edit-2511。" +
        "只提交任务，不等待出图；用户要去 AI 修图页看进度。合成多图请用 edit_photo 的 refs，不要用本工具。",
)
suspend fun batchEditPhotos(
    @Param("照片 uri 列表，英文逗号分隔，2–8 张，来自 find_photos") uris: String,
    @Param("统一修图指令") instruction: String,
    @Param("可选，输出尺寸如 1024x1024") size: String? = null,
    @Param("可选，随机种子；整批复用同一个 seed") seed: Int? = null,
): Map<String, Any?>
```

返回 `batch_id`、job 列表（id/status/queue_position）、`hint`「已提交，去 AI 修图页或稍后用 list_image_edit_jobs 查看」；同时 `onPhotos("batch_edit_photos", 原始 uris)` 显示源图缩略图。

#### list_image_edit_jobs

```kotlin
@Tool(
    name = "list_image_edit_jobs",
    description = "查看最近的修图任务状态（排队/进行中/成功/失败/取消），用于回答“刚才那张修好了吗”。只读。",
)
suspend fun listImageEditJobs(
    @Param("最多返回几条，默认 10，最大 30") limit: Int? = null,
    @Param("过滤：all / active / succeeded / failed / canceled，默认 all") status: String? = null,
): Map<String, Any?>
```

返回 label、status、queue_position、elapsed_ms、结果/缩略图路径、错误码；不返回图片字节。

#### save_image_edit_result

```kotlin
@Tool(
    name = "save_image_edit_result",
    description = "把已经成功的修图任务结果下载并保存到相册，用于 edit_photo 超时后补存，或用户说“把刚才那张存下来”。",
)
suspend fun saveImageEditResult(
    @Param("job id，来自 edit_photo / batch_edit_photos / list_image_edit_jobs") jobId: String,
): Map<String, Any?>
```

只处理 `succeeded`；已保存过则返回 `already_saved: true` 与原来的路径，不重复写相册；`result_expired` 时明确告知结果已过 7 天，需要重新修。

### 5.7 MainViewModel / AppContainer 接入

- `core/AppContainer.kt` 增加 `imageEdit` 仓库（含 `ImageEditClient`、`ImageEditStore`）。
- `MainViewModel.sendChat` 构造 `AgentTools` 时传入 `imageEdit`。
- 工具调用状态文案单独识别：

```kotlin
val status = when (calls.first().name) {
    "edit_photo" -> "正在让家里的 Qwen-Image-Edit 修图，可能要几十秒…"
    "batch_edit_photos" -> "正在提交批量修图任务…"
    "save_image_edit_result" -> "正在保存修图结果…"
    else -> "调用 ${calls.first().name}…"
}
```

- 修图页状态：

```kotlin
data class ImageEditUiState(
    val open: Boolean = false,
    val settingsOpen: Boolean = false,
    val tab: String = "edit",              // edit / jobs
    val mode: String = "single",           // single / compose / batch
    val selectedUris: List<String> = emptyList(),
    val instruction: String = "",
    val steps: Int = 40,
    val size: String? = null,
    val seed: Int? = null,
    val submitting: Boolean = false,
    val jobs: List<ImageEditJob> = emptyList(),
    val jobsLoading: Boolean = false,
    val activeCount: Int = 0,
    val error: String? = null,
    val savingIds: Set<String> = emptySet(),
    val health: ImageEditHealth? = null,
)
```

- 页面可见时每 2 秒刷新 `GET /v1/jobs`；页面关闭/切后台停止轮询，任务在服务端继续。
- 重新进入页面时加载任务列表，并对 `succeeded` 且未保存的 job 自动调用 `saveAllSucceeded`。
- 结果缩略图沿用现有 `ChatMessage.photos` + `onPhotos` 机制，不新增消息类型。

### 5.8 System prompt 补充

在 [MainViewModel.systemPrompt](file:///d:/taoxi_download_for_use/no_use_do/android/HomelabApp/app/src/main/java/com/poi/homelab/navigation/MainViewModel.kt#L2518-L2569) 的相册段落补一段（注意 ADK `Instruction` 不认字面花括号，这里不要写 `{...}`）：

```text
家里修图（Qwen-Image-Edit-2511）：
- 用户明确说“改这张照片 / 换背景 / 去路人 / 换风格 / 把两张图合在一起 / 做商品图 / 加辅助线”时，调用 edit_photo；不要用 generate_image 假装修图。
- edit_photo 的 uri 只能来自 find_photos 或上一次 edit_photo 返回的 gallery_uri；用户没说清是哪张，先问或用 list_recent_photos 让他选，不要默认拿最新一张。
- 单张或多图合成用 edit_photo，最多 3 张参考图；多图合成时在指令里写清“图1/图2”的对应关系，并强调“保留人物脸部/身份不变，只改背景”。
- 用户要一次改 2 到 8 张、且每张用同一句指令时，用 batch_edit_photos，不要连续调用 edit_photo。批量只提交任务，出图后让用户去 AI 修图页看，或者之后用 list_image_edit_jobs / save_image_edit_result 取结果。
- 用户说“修好看点/优化一下”这类模糊要求时，先追问要改什么；不要盲目消耗一次几十秒的推理。
- edit_photo 返回 status=running 时，告诉用户任务还在跑，不要说完工；下次用户问起，用 list_image_edit_jobs 查，再用 save_image_edit_result 保存。
- 工具返回 error/hint 时如实复述原因：没配置就引导 设置 → 小砂糖 → AI 修图；离线就提示检查 Tailscale 和家里容器；结果过期就建议重新修。
- 成功后用一两句话说明改了什么、已保存到相册；不要贴 base64、URL 或长 JSON。
```

### 5.9 独立「AI 修图」页

```text
┌────────────────────────────────────────┐
│ ←  AI 修图            服务 ● 在线  设置 │
├────────────────────────────────────────┤
│ [修图]  [任务 3]                        │
├────────────────────────────────────────┤
│ 修图                                    │
│ 模式：[单张] [合成] [批量]               │
│ 选择照片                                │
│ ┌────────┐ ┌────────┐ ┌────────┐       │
│ │ 原图   │ │ 参考1  │ │ +      │       │
│ └────────┘ └────────┘ └────────┘       │
│ 指令：把背景换成傍晚海边，保留人物        │
│ [去路人] [换背景] [换风格] [商品图]      │
│ [打光增强] [新视角] [工业设计] [辅助线]   │
│ 高级：步数 40   尺寸 按原图   种子 随机   │
│ 完成后自动保存到相册              [开]   │
│              [开始修图]                  │
├────────────────────────────────────────┤
│ 任务                                    │
│ [进行中 2] [全部]                        │
│ ┌ 源图 ┐ 海边背景                       │
│ │      │ 正在修 · 已用 12s · 前面 1 个   │
│ └──────┘ [取消]                         │
│ ┌ 源图 ┐ 雪景-1                         │
│ │      │ 已完成 · 18.4s        [查看]    │
│ └──────┘ [保存] [传 Cloudreve] [重试]    │
└────────────────────────────────────────┘
```

交互规则：

- 选图用系统照片选择器 `PickVisualMedia`，不新增相册权限；超过 8 张时提示「一次最多 8 张」。
- 三种模式必须显式选择，避免「多选」被误解为批量或合成：
  - 单张：1 张主图，提交 1 个 job。
  - 合成：1 张主图 + 1–2 张参考图，提交 1 个 job（`images` 共 2–3 张，顺序即图1/图2/图3）。
  - 批量：2–8 张主图，每张一个 job，共用同一句指令；批量模式不添加参考图。
- 效果预设只是把常用说法转成更具体的中文提示词，不改变模型参数：去路人、换背景、换风格、商品图、打光增强、新视角、工业设计、辅助线。
- 任务卡显示源图缩略图（来自本地 uri 或服务端 `input_thumbs`）、label、状态、已用时间、排队位置；运行中不显示假百分比。
- 操作：取消（排队中直接取消，运行中尽力取消）、重试（用相同参数和新 `request_id` 新建 job）、保存、传 Cloudreve、分享。
- 「保存全部」批量保存所有未保存的成功结果；保存过的 job 记入 `saved_job_ids`，重启后不重复写相册。
- 结果详情用左右拖动分割对比原图与结果；原图优先用本地 uri，历史任务用服务端输入缩略图。按钮：保存、分享、传 Cloudreve、再改一版（先确保结果已保存到相册，再把结果 uri 填回选图区）。
- 页面可见时轮询；离开页面任务继续，回来补状态；不做前台服务。
- 服务离线时顶部显示「服务离线」并禁用提交；已提交的任务仍可在恢复后刷新。

### 5.10 对话里的展示

- `edit_photo` 的工具消息显示结果缩略图；`batch_edit_photos` 显示源图缩略图；`save_image_edit_result` 显示保存后的缩略图。
- 用户说「把刚才那张再改亮一点」：模型传上次返回的 `gallery_uri` 给 `edit_photo`。
- 长耗时期间 `aiBusy` 保持，输入框禁用；状态文案见 §5.7。
- 失败时在工具消息里展示原因，助手再用自然语言给一次可执行建议；不自动重试。

## 6. 端到端流程

### 6.1 对话单张修图

```text
用户：把这张照片背景换成傍晚海边
  → 小砂糖判断需要修图
  → edit_photo(uri, instruction)
      → imageBase64(uri, 1536) 压缩成 JPEG
      → POST /v1/jobs（Bearer Token，request_id=UUID）
          ← 202 {id, status=queued, queue_position}
      → 每 2s GET /v1/jobs/{id}，最长 240s
          → gateway worker → vLLM-Omni /v1/chat/completions
          ← 结果 PNG → 网关存盘 → status=succeeded
      ← 下载结果 → Pictures/ai + 相册
      ← onPhotos("edit_photo", [uri])
  ← 小砂糖：改好了，已保存到相册
```

### 6.2 独立页批量修图

```text
用户选 6 张照片 → 输入「换成雪景，保留人物」→ 开始修图
  → 6 个 ImageEditRequest（服务端返回同一 batch_id）
  → POST /v1/jobs/batch
      ← 202 {batch_id, jobs:[6 个 queued]}
  → 任务中心每 2s 刷新；worker 串行执行
  → 每成功一个：下载、保存相册、界面显示结果缩略图
  → 用户可取消排队中的剩余任务，或重试失败项
```

### 6.3 切后台/进程被杀

```text
提交任务后离开页面 / App 被杀
  → 服务端 job 继续跑，结果保留 7 天、任务记录保留 30 天
  → 再打开 AI 修图页：GET /v1/jobs
  → 自动保存开启时，对 succeeded 且未在 saved_job_ids 的 job 自动保存到相册
  → 对 running/queued 的 job 继续轮询
```

### 6.4 失败分支

- 未配置 → 工具/页面提示去设置页填地址和 Token。
- 连不上 → 提示检查 Tailscale、网关容器和 8094 端口，并说明没有出图。
- 401 → 提示 Token 不对。
- 429 → 提示队列已满（页面显示前面还有几个），不自动重试。
- 504/`backend_timeout` → 任务标记失败，可重试或降低步数。
- `server_restarted` → 网关重启导致 running 任务中断，可一键重试。
- `result_expired` → 结果已过 7 天，提示重新修；手机相册里的旧副本不受影响。

## 7. 性能与边界

| 项 | 设计 |
| --- | --- |
| 上传体积 | 1536 边长 JPEG q85，单张约 300–800KB，base64 后 ×1.33；批量请求通常 5–10MB，超过 20MB 由 App 拆批 |
| 结果体积 | 1024 级 PNG 约 1–3MB；列表用 320px 缩略图，点开才下载大图 |
| 单次耗时 | 目标 60s 内；实际取决于 GPU 与步数，以实测为准，不写死到文案 |
| 并发 | 网关单并发，队列 12；批量 8 张按顺序执行，超出容量整批 429 |
| 超时 | App 轮询 300s / 网关单 job 240s / 后端 300s |
| 内存 | App 逐张编码，不一次性解码 8 张原图；工具结果不携带 base64；列表只加载缩略图 |
| 幂等 | `request_id` 15 分钟 + 内容哈希 24 小时；重试不重复出图 |
| 冷启动 | 模型首次加载可能数分钟；`/health` 报 `warming_up`，App 文案「模型正在加载」 |
| 断网 | 任务在服务端继续；恢复后用 job id 取回结果，不重复提交 |
| 存储 | 结果 + 输入缩略图 7 天 / 20GB 上限；SQLite 元数据保留 30 天 |
| 相册 | 原图不动，结果新增；Android 10+ 写相册，以下写应用目录并返回 `file://` |

## 8. 隐私与安全

- 图片只发送到家里的网关；不经过 DeepSeek/OpenAI 等服务商，也不经过第三方图床。
- 网关入站前去 EXIF/GPS；服务端只存输入缩略图（≤320px），不存输入原图。
- 日志不记录图片、prompt 原文或 Token；SQLite 只存 prompt 哈希、长度和用户可见的 `label`。
- 结果图与输入缩略图保留 7 天，job 元数据保留 30 天，均可配置；手机相册里的结果由用户自己管理。
- Token 只存服务端 `.env` 与手机 DataStore；`allowBackup=false`；不写进文档、日志、截图、提交。
- 8092 只绑内网/容器网络，公网不开放；8094 只通过 Tailscale 访问，ACL 限制到自己的设备。
- 用户必须明确要求才修图；不默认扫描相册、不批量上传；不覆盖、不删除原图。独立页通过系统照片选择器读取，不依赖全局相册权限；Agent 工具仍按现有相册权限规则。
- 分享结果通过 `FileProvider` 授予临时读权限，不暴露应用私有目录。

## 9. 验收标准

1. 家里服务器 `docker compose up -d` 后，`curl /health` 返回 `backend: ok`，`/v1/models` 能看到 Qwen-Image-Edit-2511。
2. 用 `curl` 发一张 1024 图 + 中文指令，150 秒内返回结果图，尺寸与输入比例一致。
3. `POST /v1/jobs` 返回 202；`GET /v1/jobs/{id}` 能观察到 queued → running → succeeded；结果可下载。
4. 同一 `request_id` 重复提交返回同一个 job，不产生第二张结果。
5. `POST /v1/jobs/batch` 提交 8 张，全部入队并按顺序完成；容量不足时整批 429，不会部分入队。
6. `DELETE /v1/jobs/{id}` 对排队任务立即取消；对运行中任务返回 cancel_requested 并尽力中止。
7. 结果缩略图、输入缩略图能正常返回；`?max_side=320` 的字节数明显小于原图。
8. 手机配置网关地址、Token、模型后，「测试连接」显示在线；Token 错误时显示 401 原因。
9. AI 对话里说「把这张照片背景换成傍晚海边」，`edit_photo` 被调用，结果保存到相册并在对话里显示缩略图。
10. 用户切后台/杀进程后重新打开修图页，能看到任务状态，成功结果自动补存相册且不重复。
11. 独立页能选 1–8 张、跑批量、取消排队任务、重试失败任务、查看前后对比、保存/分享/传 Cloudreve。
12. 未配置、服务离线、Token 错误、队列满、超时、结果过期六种情况都有明确提示，且不会假装成功。
13. 多图合成（2 张）能正确按图1/图2 理解指令；人物身份保持不变。
14. 网关日志中搜不到 base64、prompt 原文和 Token；输入图 EXIF/GPS 已被移除。
15. 不改动原图，不新增运行时权限；构建通过（`gradlew :app:compileDebugKotlin --offline`），实机验证。

## 10. 实施清单（一次性交付）

### 服务端

- [ ] `server/image-edit/docker-compose.yml`、`.env.example`、模型下载与启动说明。
- [ ] 网关：FastAPI + SQLite job 表 + 单并发 worker。
- [ ] 鉴权、`/health`、`/v1/models`。
- [ ] `POST /v1/jobs`、`POST /v1/jobs/batch`、`GET /v1/jobs`、`GET /v1/jobs/{id}`、`DELETE /v1/jobs/{id}`、`POST /v1/edit`。
- [ ] 结果图、结果缩略图、输入缩略图、TTL 与容量清理。
- [ ] vLLM-Omni 适配器与错误码映射；`extra_body` 参数、data URI 解析。
- [ ] 请求日志脱敏与 `/health` 队列信息。
- [ ] curl 脚本覆盖：单 job、批量、幂等、取消、过期、401/413/429/504。

### 客户端

- [ ] `AppSettings` 字段与 DataStore 键；`ImageEditStore` 保存 `saved_job_ids`。
- [ ] `feature/imageedit/` 模型、Client、Repository、Presets、本地 Store。
- [ ] `AgentTools` 四个工具；`AppContainer` / `MainViewModel` 接线；状态文案。
- [ ] `ImageEditScreen`：修图 tab、任务 tab、批量、取消、重试、保存全部、前后对比、分享、Cloudreve。
- [ ] `ImageEditSettingsScreen`：地址、Token、模型、步数、最长边、自动保存、测试连接。
- [ ] 入口：设置页卡片、首页快捷面板、AI 页空状态、`AppRoot` overlay。
- [ ] `FileProvider` 与 `file_paths.xml`；不新增运行时权限。
- [ ] system prompt 更新；ADK 工具 schema 编译验证（KSP）。

### 文档与验证

- [ ] 更新 `docs/agents/ai.md`：四个工具、任务中心、设置项、隐私约束。
- [ ] 更新 `docs/agents/photos.md`（如复用 Cloudreve 上传）与 `docs/agents/project.md`（新页面入口）。
- [ ] 本设计稿状态改为「已实现」，补充实测的显存、单张耗时与批量耗时。
- [ ] 构建、实机、Tailscale 断网/恢复、杀进程恢复全部验收。

## 11. 部署前必须验证

1. 家里服务器的 GPU 型号与显存；24GB 是否需要 fp8/offload。
2. 当前 vLLM-Omni 版本是否已支持 `Qwen/Qwen-Image-Edit-2511`（跑一次真实请求验证，不只看文档）。
3. 实际支持的最大参考图数与输出尺寸桶；社区资料按 1–3 张、约 1K 级设计。
4. 单张 1024/1536 输入的峰值显存与耗时，用于决定默认步数、队列容量与批量上限。
5. 运行中任务取消在目标后端是否真的能中止；不能中止时 UI 文案必须如实。
6. Tailscale 主机名/端口与 ACL；是否需要 `tailscale serve` 上 HTTPS。
7. KSP 生成的工具 schema 是否支持 `List<String>` 参数；如不支持，uri 列表用逗号分隔字符串（本设计已按逗号分隔）。
8. 批量 8 张时 App 的内存峰值与网关请求体大小；必要时把批量拆成多次单 job 提交。
9. 结果保留 7 天与 20GB 上限是否符合家里的备份策略。
10. `FileProvider` 的 authority 与现有应用一致；分享在 Android 8–15 上都能打开。

## 12. 参考资料

- Qwen 官方发布说明：Qwen-Image-Edit-2511（一致性、多人、LoRA 内置、工业设计、几何推理）：<https://qwen.ai/blog?id=qwen-image-edit-2511>
- 模型权重（Hugging Face）：<https://huggingface.co/Qwen/Qwen-Image-Edit-2511>
- 模型权重（ModelScope）：<https://modelscope.cn/models/Qwen/Qwen-Image-Edit-2511>
- vLLM-Omni 在线图生图服务与请求/响应格式：<https://docs.vllm.ai/projects/vllm-omni/en/latest/user_guide/examples/online_serving/image_to_image/>
- vLLM-Omni 离线多图编辑示例（含 2511）：<https://docs.vllm.ai/projects/vllm-omni/en/latest/user_guide/examples/offline_inference/image_to_image/>
- vLLM Qwen-Image 部署配方（加速、量化、并行）：<https://docs.vllm.ai/projects/recipes/en/latest/Qwen/Qwen-Image.html>
