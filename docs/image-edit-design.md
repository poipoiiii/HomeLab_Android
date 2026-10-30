# 家庭服务器 Qwen-Image-Edit-2511 修图服务设计稿

> 状态：设计稿，未实现
> 范围：客户端（`poi's home` / 小砂糖）+ 服务端（家里部署的模型与网关）
> 目标：在 AI 对话里让用户用一句中文直接改相册照片，图片只走家里的服务器，不经过第三方 AI。

## 1. 一句话描述

小砂糖把用户指定的 1–3 张照片和一句修图指令发给家里的 `img-edit-gateway`，网关调用 Qwen-Image-Edit-2511 生成结果图，App 下载后保存到相册，并在对话里显示缩略图。

## 2. 目标与非目标

### 目标

- 新增 Agent 工具 `edit_photo`：按自然语言修改照片（换背景、去路人、换风格、合成多图、商品图、加辅助线等）。
- 服务端可一键部署：模型 + 网关，带 Token 鉴权、健康检查、队列与结果缓存。
- 支持 1–3 张参考图（多图合成/人物放进场景），中文指令。
- 图片只经过家庭服务器与 Tailscale，不发送给 DeepSeek/OpenAI 等服务商。
- 失败可解释：未配置、服务离线、忙、超时、图片过大、授权缺失分别给出明确提示。
- 结果自动保存到相册，并能在对话里点开看大图，可继续二次修改。

### 非目标（v1 不做）

- 不做文生图（继续用现有 `generate_image`）。
- 不做视频、3D、换脸训练、LoRA 训练。
- 不把模型跑在手机上。
- 不暴露到公网，不做多租户、账号体系、计费。
- 不做异步任务中心、批量修图、任务列表（P2 再评估）。
- 不新增独立修图页（P1，见 §5.8）。
- 不改动已有文件，不覆盖原图；结果只新增到相册。

## 3. 总体架构

```text
小砂糖（ADK LlmAgent）
  └─ AgentTools.edit_photo
       └─ ImageEditClient（OkHttp）
            │  HTTP + Bearer Token（Tailscale / 局域网）
            ▼
     家庭服务器 img-edit-gateway :8090
       ├─ Token 校验、参数校验、图片预处理（去 EXIF / 限尺寸）
       ├─ 单并发队列 + 结果缓存（幂等 request_id）
       └─ 结果图落盘，返回可下载 URL
            │  内网 HTTP
            ▼
     vLLM-Omni（Qwen/Qwen-Image-Edit-2511）:8092
```

### 为什么不直连 vLLM-Omni

1. vLLM-Omni 默认没有鉴权，直接暴露等于整条 tailnet 都能白用 GPU。
2. 手机网络切换/锁屏容易断长连接，网关可以用 `request_id` 做幂等和结果缓存，重试不会重复出图。
3. 统一错误码、输入大小限制、EXIF 清理、结果保留策略；App 不需要理解 vLLM 细节。
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

### 4.3 目录结构

建议放在本仓库 `server/image-edit/`（不参与 APK 构建）：

```text
server/image-edit/
  docker-compose.yml
  .env.example
  gateway/
    Dockerfile
    requirements.txt        # fastapi、uvicorn、httpx、pillow
    app.py                  # 路由、鉴权、错误码
    backend.py              # vLLM-Omni / diffusers 适配器
    imageops.py             # 校验、EXIF 清理、缩放
    storage.py              # SQLite 元数据 + 结果文件 + TTL 清理
  models/                   # 模型权重（gitignore）
  data/results/             # 结果图（gitignore）
```

### 4.4 网关 API

鉴权：除 `/health` 外都需要 `Authorization: Bearer <IMAGE_EDIT_TOKEN>`。Token 用 `openssl rand -hex 24` 生成，只存服务端 `.env` 和手机本机 DataStore。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 服务与后端状态、队列深度、模型名；App「测试连接」用 |
| GET | `/v1/models` | 返回模型标识与能力（最多参考图数、支持尺寸） |
| POST | `/v1/edit` | 同步修图，返回结果元信息 + 下载地址 |
| GET | `/v1/results/{id}.png` | 下载结果图，`Cache-Control: immutable` |
| GET | `/v1/history?limit=20` | P1：最近修图元信息（不含图片内容与指令原文） |

#### POST /v1/edit 请求

```json
{
  "prompt": "把背景换成傍晚的海边，保留人物脸部和姿势不变",
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
| `prompt` | 必填，≤ 2000 字符 |
| `size` | 可选，`宽x高`；留空由后端按输入比例选桶（推荐留空） |
| `steps` | 20–60，默认 40 |
| `guidance_scale` | 默认 1.0（该系列 guidance-distilled，不要套用 SD 的 7.5） |
| `seed` | 可选；不传随机。想复现同一结果时由 App 回传上次的值 |
| `request_id` | App 生成的 UUID；同一 ID 15 分钟内重试返回同一结果 |

#### POST /v1/edit 响应

```json
{
  "id": "20261030-6f1c2f0e",
  "model": "Qwen/Qwen-Image-Edit-2511",
  "created_at": 1793520000,
  "elapsed_ms": 18432,
  "cached": false,
  "image": {
    "url": "/v1/results/20261030-6f1c2f0e.png",
    "width": 1024,
    "height": 1024,
    "bytes": 1812345,
    "sha256": "…"
  }
}
```

`url` 返回相对路径，App 用配置的 Base URL 拼接；这样换 Tailscale 域名/IP 不用改服务端。

#### 错误码

统一返回 `{"error":{"code":"…","message":"…","request_id":"…"}}`：

| HTTP | code | 场景 | App 提示 |
| --- | --- | --- | --- |
| 401 | `unauthorized` | Token 缺失/错误 | 「修图服务的 Token 不对，去 设置 → 小砂糖 → AI 修图 检查」 |
| 413 | `payload_too_large` | 图片/请求过大 | 「图片太大，先压缩或减少参考图」 |
| 415 | `unsupported_media` | 不是 JPEG/PNG/WebP | 「这个格式不支持，换 JPG/PNG」 |
| 422 | `invalid_image` | 解码失败/像素超限 | 「这张图打不开，可能已损坏或不是图片」 |
| 429 | `busy` | 队列已满（带 `Retry-After`） | 「家里的模型正在忙，过一会儿再试」 |
| 504 | `backend_timeout` | 后端超时 | 「这次修图超时了，可以重试或降低步数」 |
| 502 | `backend_error` | 后端异常/未启动 | 「家里模型服务没起来，检查容器」 |

### 4.5 网关到 vLLM-Omni 的映射

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
- 后端启动/首次推理可能很慢，网关请求超时 240s，vLLM 侧 300s；App 侧 300s。
- 单卡扩散模型并发收益有限，网关默认 `MAX_CONCURRENCY=1`，其余排队。

### 4.6 图片预处理与结果存储

- 用 Pillow `Image.open` + `verify()` 校验，限制总像素 ≤ 4000 万，防解压炸弹。
- `ImageOps.exif_transpose` 纠正方向，转 RGB；**重新编码以去掉 EXIF/GPS**（隐私）。
- 输入最长边超过 2048 时等比缩小；App 已压到 1536，正常情况下不会二次缩放。
- 结果按后端返回的 PNG 原样保存，文件名用结果 `id`；同时记录宽高、字节数、sha256、耗时。
- SQLite 存元数据：`id / request_id / content_hash / prompt_hash / status / path / created_at / elapsed_ms`。
  只存 prompt 哈希与长度，**不存 prompt 原文**。
- 清理：结果默认保留 7 天（`RESULT_TTL_HOURS`），每小时清理一次；目录总量超过 20GB 时按最旧优先删除。清理只影响网关缓存，已保存到手机相册的结果不受影响。

### 4.7 部署

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
      - "8090:8090"
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
MAX_QUEUE=3
MAX_REFS=3
MAX_INPUT_MB=20
MAX_SIDE=2048
REQUEST_TIMEOUT_SECONDS=240
RESULT_DIR=/data/results
RESULT_TTL_HOURS=168
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

- 服务器装 Tailscale，手机用 MagicDNS 主机名访问 `http://<tailnet-host>:8090`；只在 tailnet 内，不映射公网端口。
- 需要 HTTPS 时用 `tailscale serve` 反代，网关本身保持 HTTP。
- Tailscale ACL 只允许自己的设备访问 8090；8092 不要开放。

启动顺序与冷启动：

1. 下载权重 → 起 `vllm` → 等 `/v1/models` 可访问（首次加载可能几分钟）。
2. 起 `gateway` → `curl /health` 返回 `backend: ok`。
3. 手机设置页点「测试连接」，再做一次 1024×1024 的真实修图验证。

### 4.8 观测与运维

- 访问日志只记录：`request_id`、`status`、`elapsed_ms`、`input_bytes`、`output_bytes`、`cached`、`queue_depth`。
- **不记录** base64、图片内容、prompt 原文、Token。
- `/health` 返回：`status`（`ok` / `degraded` / `warming_up`）、`model`、`backend`、`queue`、`gpu_vram_used_mb`（可读则返回，读不到就省略）。
- vLLM 有 `/health` 时用它探活；版本不一致时退回 `/v1/models`。
- 网关不写系统临时目录大片文件；结果与日志都在 `data/` 下，随部署目录迁移。

## 5. 客户端设计

### 5.1 新增配置项

`core/settings/SettingsStore.kt` 的 `AppSettings` 增加：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `imageEditUrl` | `""` | 网关地址，如 `http://home-server:8090`；空 = 未启用 |
| `imageEditToken` | `""` | Bearer Token，只存本机 DataStore |
| `imageEditModel` | `"Qwen/Qwen-Image-Edit-2511"` | 展示与服务端校验用 |
| `imageEditSteps` | `40` | 20–60 |
| `imageEditMaxSide` | `1536` | 上传前压缩的最长边，768–2048 |

DataStore 键前缀 `image_edit_`；`allowBackup=false` 已有，Token 不进日志、不进文档、不进提交。

### 5.2 设置入口

- 设置 → 小砂糖 → 「AI 配置」卡片增加第二行摘要：`修图：已配置 / 未配置`。
- AI 配置页（[AiSettingsScreen.kt](file:///d:/taoxi_download_for_use/no_use_do/android/HomelabApp/app/src/main/java/com/poi/homelab/feature/ai/ui/AiSettingsScreen.kt)）在「模型服务商」卡片下方新增一张 `CreamCard`：**AI 修图（家庭服务器）**：
  - 网关地址、Token、模型名、步数、上传最长边；
  - 「测试连接」按钮调 `/health`，显示「模型在线 · 队列 0」或失败原因；
  - 一行说明：图片只发给家里的服务器，不发送给上面的 AI 服务商。
- 沿用现有 `SuccessButton` / `InlineAction` / 详细模式，不新增配色与字体。

### 5.3 ImageEditClient

新增 `feature/ai/ImageEditClient.kt`，只负责 HTTP，不持有设置：

```kotlin
class ImageEditClient(private val http: OkHttpClient, private val json: Json) {
    suspend fun health(baseUrl: String, token: String): ImageEditHealth
    suspend fun edit(baseUrl: String, token: String, request: ImageEditRequest): ImageEditResponse
    suspend fun download(baseUrl: String, token: String, path: String): ByteArray
}
```

- Base URL 规整：去尾部 `/`，若以 `/v1`、`/v1/edit` 结尾则剥掉，再拼 `/v1/edit`（参考现有 `AiClient.imagesEndpoint`）。
- 超时：每次调用用 `http.newBuilder()` 派生独立客户端，连接 10s、读取 300s、写入 60s，共享连接池，不改全局 OkHttp。
- 错误：解析 `{"error":{"code","message"}}`；非 JSON 时回退 `HTTP <code>`，截断 200 字符；同时识别 502/503/504 的反代提示。
- 取消：用可取消的 `Call`（`suspendCancellableCoroutine` + `enqueue`，取消时 `call.cancel()`），避免离开页面后还占着请求。

请求/响应模型放 `feature/ai/ImageEditModels.kt`：

```kotlin
@Serializable
data class ImageEditRequest(
    val prompt: String,
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
data class ImageEditResponse(
    val id: String,
    val model: String,
    @SerialName("elapsed_ms") val elapsedMs: Long,
    val cached: Boolean = false,
    val image: ImageEditImage,
)

@Serializable
data class ImageEditImage(
    val url: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
)
```

### 5.4 Agent 工具 `edit_photo`

在 `AgentTools` 增加依赖 `imageEdit: ImageEditClient`，新增工具：

```kotlin
@Tool(
    name = "edit_photo",
    description = "调用家里部署的 Qwen-Image-Edit-2511 按中文指令修改照片，" +
        "并把结果保存到相册。适合换背景、去路人、换风格、合成多图、商品图、加辅助线；" +
        "只应在用户明确要求修改某张照片时调用。",
)
suspend fun editPhoto(
    @Param("要修改的照片 uri，来自 find_photos 或上一次 edit_photo 返回的 gallery_uri") uri: String,
    @Param("修图指令，中文即可，例如：把背景换成傍晚海边，保留人物脸部和姿势不变") instruction: String,
    @Param("可选，额外参考图 uri，最多 2 张，用英文逗号分隔") refs: String? = null,
    @Param("可选，输出尺寸如 1024x1024；留空按输入比例") size: String? = null,
    @Param("可选，随机种子；想复现上次结果时传上次返回的 seed") seed: Int? = null,
): Map<String, Any?> = safely { … }
```

执行流程：

1. 校验 `settings.imageEditUrl` 非空，否则返回 `error` + `hint`（去 设置 → 小砂糖 → AI 修图）。
2. 解析 `uri` 与 `refs`（逗号分隔，去空、去重、最多 3 张）；调用 `device.imageBase64(uri, settings.imageEditMaxSide)` 逐张压缩为 JPEG base64；读不到图时返回与 `describe_photo` 一致的修复提示（重新 `find_photos` 或传文件名）。
3. 生成 `requestId = UUID.randomUUID().toString()`，调 `ImageEditClient.edit`。
4. 下载结果字节 → `device.saveImage(bytes, "png")`（保存到 `Pictures/ai`，Android 10+ 同时写入相册）。
5. `onPhotos("edit_photo", listOf(保存后的 uri))` 让对话显示缩略图。
6. 返回精简 JSON，**绝不返回 base64**：

```json
{
  "ok": true,
  "model": "Qwen/Qwen-Image-Edit-2511",
  "instruction": "把背景换成傍晚海边，保留人物",
  "size": "1024x1024",
  "elapsed_ms": 18432,
  "seed": 42,
  "path": "…/Pictures/ai/ai_20261030_153012.png",
  "gallery_uri": "content://media/external/images/media/123456"
}
```

- Android 10 以下没有 `gallery_uri`，返回 `file_uri`（`file://…`）供二次修改；`DeviceTools.photoUris` 已能处理带 scheme 的 uri。
- 网络异常/超时：不自动无限重试；同一 `request_id` 允许重试一次（网关幂等）。仍失败则返回 `error` + 可读原因。
- 429：直接返回「模型正忙」，不要重试，避免排队叠加。
- 未授权相册：返回 `need_permission`，引导 设置 → 权限 → 相册。

### 5.5 MainViewModel / AppContainer 接入

- `core/AppContainer.kt` 增加 `val imageEdit = ImageEditClient(http, json)`。
- `MainViewModel.sendChat` 构造 `AgentTools` 时传入 `imageEdit = app.imageEdit`。
- 工具调用状态文案单独识别：

```kotlin
val status = when (calls.first().name) {
    "edit_photo" -> "正在让家里的 Qwen-Image-Edit 修图，可能要几十秒…"
    else -> "调用 ${calls.first().name}…"
}
```

- 结果缩略图沿用现有 `ChatMessage.photos` + `onPhotos` 机制，不新增消息类型。
- 设置页保存/测试沿用现有 `UiState.settings` 与 `SettingsStore.save`，不新建存储。

### 5.6 System prompt 补充

在 [MainViewModel.systemPrompt](file:///d:/taoxi_download_for_use/no_use_do/android/HomelabApp/app/src/main/java/com/poi/homelab/navigation/MainViewModel.kt#L2518-L2569) 的相册段落补一段（注意 ADK `Instruction` 不认字面花括号，这里不要写 `{...}`）：

```text
家里修图（Qwen-Image-Edit-2511）：
- 用户明确说“改这张照片 / 换背景 / 去路人 / 换风格 / 把两张图合在一起 / 做商品图 / 加辅助线”时，调用 edit_photo；不要用 generate_image 假装修图。
- edit_photo 的 uri 只能来自 find_photos 或上一次 edit_photo 返回的 gallery_uri；用户没说清是哪张，先问或用 list_recent_photos 让他选，不要默认拿最新一张。
- 一次最多 3 张图，顺序就是图1、图2、图3；合成时在指令里写清对应关系，并强调“保留人物脸部/身份不变，只改背景”。
- 用户说“修好看点/优化一下”这类模糊要求时，先追问要改什么；不要盲目消耗一次几十秒的推理。
- 这是家里服务器上的模型，可能要几十秒；不要因为等待就改用 generate_image，也不要说“已经修好了”除非工具返回 ok。
- 工具返回 error/hint 时如实复述原因：没配置就引导 设置 → 小砂糖 → AI 修图；离线就提示检查 Tailscale 和家里容器。
- 成功后用一两句话说明改了什么、已保存到相册；不要贴 base64、URL 或长 JSON。
```

### 5.7 对话界面

- 工具消息里的结果缩略图复用现有照片行；点开大图走现有查看逻辑。
- 用户可以说「把刚才那张再改亮一点」：模型把上次返回的 `gallery_uri` 再传给 `edit_photo`。
- 长耗时期间 `aiBusy` 保持，输入框禁用；状态文案见 §5.5。
- 失败时在工具消息里展示原因，助手再用自然语言给一次可执行建议；不自动重试。

### 5.8 独立修图页（P1）

不做进 v1，但预留入口与交互：

```text
┌────────────────────────────────────┐
│ ←  AI 修图                服务 ● 在线 │
├────────────────────────────────────┤
│ [选择照片]  最多再添加 2 张参考图      │
│ ┌────────┐ ┌────────┐ ┌────────┐    │
│ │ 原图   │ │ 参考1  │ │ +      │    │
│ └────────┘ └────────┘ └────────┘    │
│ 指令：把背景换成傍晚海边，保留人物      │
│ [去路人] [换背景] [换风格] [商品图]    │
│ 高级：步数 40   尺寸 按原图            │
│            [开始修图]                │
├────────────────────────────────────┤
│ 结果（左右拖动对比）                  │
│ [保存到相册] [再改一版] [传 Cloudreve] │
└────────────────────────────────────┘
```

- 选图用系统照片选择器 `PickVisualMedia`，不新增相册权限。
- 结果自动保存，失败保留原图与指令，可重试。
- 入口：AI 页空状态/设置卡片；不新增底部导航。

## 6. 端到端时序

```text
用户：把这张照片背景换成傍晚海边
  → 小砂糖判断需要修图
  → edit_photo(uri, instruction, refs="", size=null, seed=null)
      → imageBase64(uri, 1536) 压缩成 JPEG
      → POST /v1/edit（Bearer Token，request_id=UUID）
          → gateway 校验/去 EXIF/排队
          → vLLM-Omni /v1/chat/completions（extra_body: steps/guidance_scale）
          ← data:image/png;base64,…
          ← 网关存 PNG，返回 id + url
      ← App 下载 /v1/results/{id}.png
      ← saveImage → Pictures/ai/ai_*.png + 相册 uri
      ← onPhotos("edit_photo", [uri])，工具消息显示缩略图
  ← 小砂糖：改好了，背景换成傍晚海边了，已保存到相册
```

失败分支：

- 未配置 → 工具 `error: 还没有配置 AI 修图服务`，助手引导设置页。
- 连不上 → `ConnectException`，提示检查 Tailscale/容器，并说明没有出图。
- 401 → 提示检查 Token。
- 429 → 提示模型忙，稍后再试，不自动重试。
- 504 → 提示超时，可降低步数或重试；同一 `request_id` 重试不会重复排队。

## 7. 性能与边界

| 项 | 设计 |
| --- | --- |
| 上传体积 | 1536 边长 JPEG q85，单张约 300–800KB，base64 后 ×1.33；3 张通常 < 3MB |
| 结果体积 | 1024 级 PNG 约 1–3MB，经局域网/Tailscale 下载 |
| 单次耗时 | 目标 60s 内；实际取决于 GPU 与步数，以实测为准，不写死到文案 |
| 并发 | 网关单并发，队列 3；超出返回 429 |
| 超时 | App 300s / 网关 240s / 后端 300s |
| 内存 | App 逐张编码，不一次性解码 3 张原图；工具结果不携带 base64 |
| 幂等 | `request_id` 15 分钟 + 内容哈希 24 小时，重试不重复出图 |
| 冷启动 | 模型首次加载可能数分钟；`/health` 报 `warming_up`，App 文案「模型正在加载」 |
| 断网 | 结果已落盘时可用同一 `request_id` 取回；否则提示重新发起 |
| 相册 | 原图不动，结果新增；Android 10+ 写相册，以下写应用目录并返回 `file://` |

## 8. 隐私与安全

- 图片只发送到家里的网关；不经过 DeepSeek/OpenAI 等服务商，也不经过第三方图床。
- 网关入站前去 EXIF/GPS；日志不记录图片、prompt 原文或 Token。
- 结果只保留 7 天，可配置；手机相册里的结果由用户自己管理。
- Token 只存服务端 `.env` 与手机 DataStore；`allowBackup=false`；不写进文档、日志、截图、提交。
- 8092 只绑内网/容器网络，公网不开放；8090 只通过 Tailscale 访问，ACL 限制到自己的设备。
- 用户必须明确要求才修图；不默认扫描相册、不批量上传；不覆盖、不删除原图。

## 9. 验收标准

1. 家里服务器 `docker compose up -d` 后，`curl /health` 返回 `backend: ok`，`/v1/models` 能看到 Qwen-Image-Edit-2511。
2. 用 `curl` 发一张 1024 图 + 中文指令，150 秒内返回结果图，尺寸与输入比例一致。
3. 手机配置网关地址、Token、模型后，「测试连接」显示在线；Token 错误时显示 401 原因。
4. AI 对话里说「把这张照片背景换成傍晚海边」，`edit_photo` 被调用，结果保存到相册并在对话里显示缩略图。
5. 未配置、服务离线、Token 错误、队列满、超时五种情况都有明确提示，且不会假装成功。
6. 多图合成（2 张）能正确按图1/图2 理解指令；人物身份保持不变。
7. 同一 `request_id` 重试不会产生第二张结果；结果 7 天后被清理，相册不受影响。
8. 网关日志中搜不到 base64、prompt 原文和 Token；图片 EXIF/GPS 已被移除。
9. 不改动原图，不新增 Android 权限；`AndroidManifest.xml` 无变化。
10. 构建通过（`gradlew :app:compileDebugKotlin --offline`），实机验证。

## 10. 版本计划

### P0（本次实现范围）

- 服务端：vLLM-Omni 部署 + `img-edit-gateway`（鉴权、`/health`、`/v1/edit`、结果下载、缓存与清理）。
- 客户端：配置项与 AI 配置卡片、`ImageEditClient`、`edit_photo` 工具、状态文案、system prompt、结果缩略图。
- 文档：`docs/agents/ai.md` 增加工具与配置说明。

### P1

- 独立「AI 修图」页（选图、预设、前后对比、重试、传 Cloudreve）。
- 网关 `/v1/history` 与 App 最近修图列表。
- 「测试连接」里展示显存/队列/最近耗时。
- 兼容端点 `/v1/images/edits`（multipart），方便其他客户端接入。

### P2

- 异步任务：`POST /v1/jobs` + 轮询/SSE 进度，支持锁屏/切后台后回来取结果。
- 批量修图、LoRA 选择、按用户/设备配额。
- 与 Anywear/Decart 试衣流程整合（见 [tryon.md](file:///d:/taoxi_download_for_use/no_use_do/android/docs/agents/tryon.md)）。

## 11. 待确认 / 部署前验证

1. 家里服务器的 GPU 型号与显存；24GB 是否需要 fp8/offload。
2. 当前 vLLM-Omni 版本是否已支持 `Qwen/Qwen-Image-Edit-2511`（跑一次真实请求验证，不只看文档）。
3. 实际支持的最大参考图数与输出尺寸桶；社区资料按 1–3 张、约 1K 级设计。
4. 单张 1024/1536 输入的峰值显存与耗时，用于决定默认步数与是否限制参考图。
5. Tailscale 主机名/端口与 ACL；是否需要 `tailscale serve` 上 HTTPS。
6. KSP 生成的工具 schema 是否支持 `List<String>` 参数；如不支持，`refs` 用逗号分隔字符串（本设计已按逗号分隔）。
7. 服务端代码放本仓库 `server/image-edit/` 还是单独仓库；本设计默认放本仓库，便于版本对齐。
8. 结果保留天数与磁盘上限是否符合家里的备份策略。

## 12. 参考资料

- Qwen 官方发布说明：Qwen-Image-Edit-2511（一致性、多人、LoRA 内置、工业设计、几何推理）：<https://qwen.ai/blog?id=qwen-image-edit-2511>
- 模型权重（Hugging Face）：<https://huggingface.co/Qwen/Qwen-Image-Edit-2511>
- 模型权重（ModelScope）：<https://modelscope.cn/models/Qwen/Qwen-Image-Edit-2511>
- vLLM-Omni 在线图生图服务与请求/响应格式：<https://docs.vllm.ai/projects/vllm-omni/en/latest/user_guide/examples/online_serving/image_to_image/>
- vLLM-Omni 离线多图编辑示例（含 2511）：<https://docs.vllm.ai/projects/vllm-omni/en/latest/user_guide/examples/offline_inference/image_to_image/>
- vLLM Qwen-Image 部署配方（加速、量化、并行）：<https://docs.vllm.ai/projects/recipes/en/latest/Qwen/Qwen-Image.html>
