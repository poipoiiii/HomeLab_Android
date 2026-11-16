# AI 创作（Android 端）需求文档 v1

> 状态：待远程 Android 开发实现（2026-11-16）
> 范围：只改 Android `HomelabApp`；服务端 8094 已由本次提交改成支持 `qwen_edit` + `krea2_t2i`
> 目标：用户上传 1 主图 + 可选 1 参考图，写一句描述；端侧 Agent 判断改图/生图，直接提交家里的 8094 网关，异步出图后自动保存。
> 本次不改 Android 代码、不跑端到端测试；本文是远程实现的唯一口径。

## 1. 一句话描述

新增抽屉页「AI 创作」：用户选照片、写描述 → 手机上的 Agent 判断这是「改图」还是「生图」→ 直接提交 8094 → 任务卡显示排队/加载/生成/保存 → 完成自动存相册，保留历史与重试。

## 2. 已确认口径

| 维度 | v1 结论 |
| --- | --- |
| 入口 | 新建「AI 创作」抽屉页，不改「AI 修图」「AI-Web-Video」 |
| 输入 | 1 主图（可选）+ 1 参考图（可选）+ 一句描述 |
| Agent | 端侧，复用现有小砂糖 AI 服务商/Key |
| 改图 | 主图 + 可选参考图 → `qwen_edit` |
| 生图 | 纯文字 → `krea2_t2i`；生图不读取照片 |
| 确认 | 清晰直接提交；不明确只追问一次；可手动选模式 |
| 后端 | 8094 网关，统一 Token、队列、SQLite、结果 |
| 视频 | 不做；二期 4 秒竖屏 |
| 画质 | Krea2 基础生图；极清/SeedVR2 二期 |
| 隐私 | 照片只到 8094；Agent 不接收照片字节 |

## 3. 页面与导航

- 入口：左侧抽屉新增「AI 创作」，建议放在「AI 修图」/「AI-Web-Video」附近。
- 页面形态：独立页（不是 overlay）；保留全局壁纸，不要在本页重复画背景。
- 顶部：标题「AI 创作」；右侧设置图标，打开现有「AI 修图 → 服务设置」，不新建一套网关配置。
- 返回：沿用 App 现有导航规则；任务在服务端继续，不因返回/关页取消。
- 不使用底部导航；保持现有 Material 3 + 四套配色风格。

## 4. 页面布局

```text
┌ AI 创作 ─────────────────────────── 设置 ┐
│                                            │
│  ┌────────────┐  ┌────────────┐            │
│  │ 主图 +     │  │ 参考图 +   │  （可选）  │
│  └────────────┘  └────────────┘            │
│  参考图仅用于改图，生图不读取               │
│                                            │
│  描述：把背景换成傍晚海边，保留五官和衣服  │
│  [换背景] [去路人] [换装] [海报] [更多]    │
│                                            │
│  判断：自动 · 改图 · 生图                  │
│  [ 开始创作 ]                              │
├ 最近创作 ──────────────────────────────────┤
│  [任务卡] [任务卡] [历史]                  │
└────────────────────────────────────────────┘
```

### 4.1 输入区

- 主图槽：系统照片选择器（Photo Picker），不申请全局相册权限；显示缩略图、右上角删除。
- 参考图槽：可选；文案「参考图（仅改图使用）」；生图时置灰/提示不读取。
- 描述：多行输入，最大 500 字；为空不能提交。
- 示例 chips：换背景 / 去路人 / 换装 / 海报 / 头像；点击填入描述，仍可编辑。
- 判断 chips：`自动`（默认）/ `改图` / `生图`；手动选择时跳过 Agent 判断。
- 开始按钮：主图为空且手动选「改图」时禁用并提示「先选一张主图」。
- 上传前可在客户端把主图最长边缩到 1536、JPEG 质量 90，减少流量；服务端仍会去 EXIF、限 2048。

### 4.2 任务区

- 提交后不弹全屏确认；顶部出现 2–3 秒状态条「已按改图提交 · Qwen-Image-Edit」或「已按生图提交 · Krea2」。
- 任务卡显示：
  - 模式/工作流：`改图 · Qwen-Image-Edit` / `生图 · Krea2`
  - 描述摘要（最多 2 行）
  - 状态/阶段、排队位置、已用时间
  - 进行中：取消
  - 成功：缩略图、预览、保存、再做一张、继续改、删除
  - 失败：原因、重试、删除
- 历史：最近 20 条；按创建时间倒序；可删除；结果过期显示「结果已保留 7 天，重新生成一次」。

## 5. 用户流程

### 5.1 清晰场景（直接提交）

- 有主图 + 改图语义（改/换/去掉/保留/修复/上色/合成/把…变成/背景/衣服/路人/划痕）→ `qwen_edit`。
- 无主图 + 生图语义（画/生成/做一张/海报/头像/插画/壁纸/风格）→ `krea2_t2i`。
- 有主图 + 生图语义 + “参考这张/类似/同款” → 视为不明确，追问一次。
- 直接提交后，任务卡显示 Agent 选择；用户可取消。

### 5.2 不明确场景（只追问一次）

- 例：「参考这张画一张新图」→ 内联问：「改这张图，还是生成一张新图？」两个按钮。
- 例：「帮我弄好看点」→ 问：「想改哪一部分？背景 / 人物 / 光线」。
- 例：无主图 + 「换个背景」→ 问：「先选一张要改的照片，或让我生成一张新图」。
- 最多 1 轮；回答后强制提交，不再追问。

### 5.3 手动/降级

- 用户手动选「改图」：不走 Agent，按改图提示词模板提交；无主图则提示选图。
- 用户手动选「生图」：不走 Agent；如果已上传照片，提示「生图只用文字，不读取照片」，可继续或切回改图。
- Agent 没 Key / 调用失败 / JSON 修复失败：显示手动 chips，不阻塞；提示「这次没判断出来，你选一下」。
- 没配 AI Key：提示「先在 设置 → 小砂糖 → AI 配置 里选一个服务商」，按钮「去设置」。
- 没配 8094 网关：提示「还没配置修图网关」，按钮打开现有「AI 修图 → 服务设置」。

## 6. 端侧 Agent

### 6.1 位置与 Key

- 运行在手机端，复用现有小砂糖 AI 配置（服务商、模型、Key）。
- 不新增 Key、不新增设置页；不把 AI Key 发给 8094。
- 8094 仍用现有 `IMAGE_EDIT_TOKEN`；复用 `AppSettings.imageEditUrl` / `imageEditToken`（设置 → 小砂糖 → AI 修图 → 服务设置），不要再存一份。
- Agent 输入只包含：
  - 用户描述原文；
  - `has_main` / `has_ref`；
  - 用户当前选择（自动/改图/生图）；
  - 允许的工作流枚举。
- Agent 不接收照片字节、缩略图、相册路径、EXIF；照片只在提交时发给 8094。

### 6.2 输出协议

只输出一个 JSON 对象；建议用 ADK `LlmAgent` + `InMemoryRunner`，按现有 `AgentTools` 的 JSON 解析方式处理。

```json
{
  "status": "plan",
  "message": "这句话更像改图，我会保留人物五官和构图。",
  "plan": {
    "mode": "edit",
    "workflow": "qwen_edit",
    "prompt": "把背景换成傍晚海边，保留人物五官、发型、姿势和衣服不变",
    "aspect": "3:4",
    "size": "1024x1024",
    "steps": 30,
    "seed": null,
    "negative_prompt": ""
  }
}
```

追问：

```json
{
  "status": "ask",
  "message": "你是想改这张图，还是参考它生成一张新图？",
  "plan": null
}
```

约束：

- `mode` 与 `workflow` 必须匹配：`edit -> qwen_edit`，`generate -> krea2_t2i`。
- `krea2_t2i` 的 `size` 只能从：`1024x1024` / `832x1216` / `1216x832` / `768x1344` / `1344x768` 选。
- `qwen_edit` 的 `size` 可省略，服务端按主图比例选。
- 模型输出非 JSON：自动修复一次；仍失败退回手动 chips。
- 模型不能直接提交任务；提交动作由 ViewModel 完成。

### 6.3 提示词规则

- 改图：保留「人物五官、发型、姿势、构图、衣服」约束；命中现有 `IMAGE_EDIT_PRESETS` 时复用 `composeImageEditPrompt()` 的场景模板。
- 生图：整理成 Krea2 可读的短提示词；不编造用户没说的细节；比例推断不出用 `3:4`（`832x1216`）。
- Krea2 的 negative 默认空，CFG 固定 1.0。
- 提示词不要堆「8K / 大师作品 / 电影级」等空泛词。

## 7. 服务端契约

### 7.1 地址与鉴权

- Base URL：`AppSettings.imageEditUrl`（`SettingsStore` / `AppContainer.settings`），例如 `http://100.x.y.z:8094`；不要带 `/v1`。
- Token：`AppSettings.imageEditToken`，`Authorization: Bearer <token>`。
- 结果/缩略图 URL 由 Coil 直接加载时用 `?token=`（和现有修图页一致）。
- AI 服务商 Key 只用于 Agent，不用于 8094。

### 7.2 能力发现

`GET /v1/workflows`（可选，页面首次打开/设置页调用一次）：

```json
{
  "workflows": [
    {
      "id": "qwen_edit",
      "mode": "edit",
      "name": "改图",
      "model": "Qwen-Image-Edit-2511",
      "min_refs": 1,
      "max_refs": 3,
      "sizes": ["1024x1024", "1328x1328", "1536x1024", "832x1216", "1216x832"],
      "default_size": null,
      "steps": { "default": 40, "min": 20, "max": 60 },
      "est_cold_seconds": 240,
      "est_warm_seconds": 240
    },
    {
      "id": "krea2_t2i",
      "mode": "generate",
      "name": "生图",
      "model": "Krea2-Moody-Mix",
      "min_refs": 0,
      "max_refs": 0,
      "sizes": ["1024x1024", "832x1216", "1216x832", "768x1344", "1344x768"],
      "default_size": "832x1216",
      "steps": { "default": 10, "min": 6, "max": 20 },
      "est_cold_seconds": 60,
      "est_warm_seconds": 15
    }
  ]
}
```

- 如果接口 404（服务端未升级），新页面应提示「服务端版本过旧，请先升级 8094」，不要退回只做改图。

### 7.3 创建任务

`POST /v1/jobs`，返回 202：

改图：

```json
{
  "workflow": "qwen_edit",
  "mode": "edit",
  "prompt": "把背景换成傍晚海边，保留五官和衣服",
  "label": "海边背景",
  "images": [
    { "mime": "image/jpeg", "b64": "<base64>" },
    { "mime": "image/jpeg", "b64": "<reference base64>" }
  ],
  "size": "1024x1024",
  "steps": 30,
  "guidance_scale": 1.0,
  "negative_prompt": "",
  "seed": null,
  "request_id": "creative-<uuid>"
}
```

生图：

```json
{
  "workflow": "krea2_t2i",
  "mode": "generate",
  "prompt": "雨夜咖啡馆，35mm 胶片，暖色室内光",
  "label": "雨夜咖啡馆",
  "images": [],
  "size": "832x1216",
  "steps": 10,
  "guidance_scale": 1.0,
  "seed": null,
  "request_id": "creative-<uuid>"
}
```

要点：

- `images`：改图 1–2 张（新页面 UI 限制 2；服务端允许 3）；生图必须为空。
- `label` ≤40 字；`prompt` ≤2000 字。
- `request_id` 用于 15 分钟幂等；同一请求重试返回原 job，不要每次重试换 ID。
- 上传前客户端可把最长边缩到 1536；服务端会去掉 EXIF、限制 2048。
- 生图 `size` 必须在 5 个预设里；`steps` 6–20，默认 10。

返回：

```json
{
  "id": "job_20261116_ab12cd34",
  "status": "queued",
  "queue_position": 0,
  "created_at": 1760000000,
  "deduplicated": false,
  "cached": false
}
```

### 7.4 查询/取消/删除

- `GET /v1/jobs/{id}`：轮询 1.5–2 秒；`status` 为终态（succeeded/failed/canceled）后停止。
- `DELETE /v1/jobs/{id}`：queued/running 时取消；已结束彻底删除（记录+结果+缩略图）。
- `GET /v1/jobs?status=all|active|succeeded|failed|canceled&limit=20&cursor=`：历史列表。
- 任务对象关键字段：

```json
{
  "id": "job_...",
  "workflow": "krea2_t2i",
  "mode": "generate",
  "media_type": "image",
  "label": "雨夜咖啡馆",
  "status": "running",
  "stage": "sampling",
  "queue_position": 0,
  "created_at": 1760000000,
  "started_at": 1760000001,
  "finished_at": null,
  "elapsed_ms": null,
  "input_count": 0,
  "input_thumbs": [],
  "width": 832,
  "height": 1216,
  "bytes": null,
  "thumb_url": null,
  "result_url": null,
  "cached": false,
  "error": null
}
```

### 7.5 结果与缩略图

- 原图：`GET /v1/results/{id}.png?token=...`（Coil 用 `?token=`）。
- 缩略图：`GET /v1/results/{id}.png?max_side=320&token=...`。
- 输入缩略图：`GET /v1/jobs/{id}/inputs/{i}.jpg?token=...`。
- 结果保留 7 天；404 `result_expired` 时提示重新生成。
- 结果 URL 返回后由 App 下载并保存到相册；不要只把 URL 存起来。

### 7.6 错误码

| HTTP | 场景 | 客户端处理 |
| --- | --- | --- |
| 401 | Token 错/没带 | 提示检查服务设置；不要重试刷屏 |
| 404 `result_expired` | 结果已清理 | 提示重新生成 |
| 409 `job_finished` | 取消已结束任务 | 刷新任务状态 |
| 429 | 队列满 | 提示稍后再试；保留草稿 |
| 422 | 字段/尺寸/步骤不合法 | 显示服务端 message；回退到安全默认值 |
| 503 | 服务端未配置 Token | 提示检查服务端 `.env` |
| 网络错误 | Tailscale/网关离线 | 顶部离线提示；可重试 |

## 8. 任务状态与 UI 映射

| status | stage | 展示 |
| --- | --- | --- |
| `queued` | `queued` | 排队中 · 前面 N 个 |
| `running` | `waiting_gpu` | 等待 GPU · 前面有视频任务 |
| `running` | `loading` | 加载模型 · 首次约 45 秒–4 分钟 |
| `running` | `sampling` | 生成中 · 已用 X 秒 |
| `running` | `decoding` | 解码中 · 已用 X 秒 |
| `running` | `finalizing` | 保存结果 |
| `succeeded` | `done` | 已完成 · 已保存到相册 |
| `cancel_requested` | — | 正在取消 |
| `canceled` | `done` | 已取消 |
| `failed` | `done` | 失败 + error.message |

- 不显示假扩散百分比；只显示阶段、排队位置、已用时间。
- 首次冷启动/模型切换慢是正常；文案要提前说明，不要让用户以为卡死。
- `elapsed_ms` 只在终态有值；进行中用 `now - started_at` 计算。

## 9. 结果、保存与历史

- 成功后自动保存到相册（沿用现有修图页的自动保存设置；不新增重复开关）。
- 同一结果只保存一次（按 job_id 去重，跨进程也去重）。
- 任务卡缩略图用 `thumb_url` + `?token=`；点开全屏预览，可保存/分享。
- 「再做一张」：保留描述/比例，清空结果，重新提交；seed 默认随机。
- 「继续改」：把结果图作为新的主图，回到输入区；参考图保留。
- 历史最近 20 条；删除二次确认；已保存相册副本不受影响。
- 结果过期：显示占位和「重新生成」，不要显示破图。

## 10. 设置与隐私

- 复用现有：
  - AI 服务商/Key：设置 → 小砂糖 → AI 配置。
  - 8094 地址/Token：设置 → 小砂糖 → AI 修图 → 服务设置（`AppSettings.imageEditUrl` / `imageEditToken`）。
- 不新增 Key、不新增网关地址、不复制 Token。
- 照片只发 8094；Agent 请求不包含照片字节/缩略图/EXIF。
- 日志只记录 `job_id`、`workflow`、`status/stage`、`elapsed`、`prompt_len`；不打印 prompt 原文、base64、Token。
- Token/Key 只存本机 DataStore；不写文档、提交、截图。
- 不覆盖原图；结果只新增到相册。

## 11. 代码落点建议

```text
feature/creative/
├─ CreativeModels.kt        请求/任务/结果 DTO
├─ CreativeClient.kt        复用 OkHttp/Json；不要把 Token 存两份
├─ CreativeRepository.kt    提交、轮询、取消、历史、结果下载
├─ CreativePrompt.kt        Agent 指令与改图/生图提示词模板
├─ CreativeAgent.kt         端侧判断、JSON 修复、手动降级
├─ CreativeViewModel.kt     页面状态、任务同步、自动保存
└─ ui/
   ├─ CreativeScreen.kt
   ├─ CreativeTaskCard.kt
   └─ CreativeHistory.kt
```

- 复用 `AppSettings.imageEditUrl` / `imageEditToken`、`ImageEditClient` 的 HTTP/错误处理模式、`ImageEditRepository` 的轮询/自动保存逻辑；不要改现有修图页的行为。
- 导航在 `AppRoot` 增加抽屉入口；`MainViewModel` 如需跨页状态按现有模式处理。
- Agent 用现有 ADK `LlmAgent` + `InMemoryRunner`，或复用现有 AI 客户端；不要手写新的 SSE/tool loop。
- 图片选择用系统 Photo Picker；保存用 MediaStore；参考现有 `feature/imageedit` 实现。

## 12. 调试方案（Android）

### 12.1 先手动、后 Agent

1. 先用「手动 → 改图」跑通：选主图 → 写描述 → 提交 → 任务卡 → 结果 → 自动保存。
2. 再用「手动 → 生图」跑通：不选图 → 写描述 → 提交 → 结果。
3. 最后接 Agent：先用固定样例验证判断，再打开页面自动判断。

这样能把协议问题、UI 问题、Agent 问题分开。

### 12.2 服务端联调（不接 GPU）

服务端支持 `BACKEND=mock`，不加载模型，返回占位图：

```bash
# 在 WSL/服务端临时起一个 mock 网关（不要改生产 .env）
BACKEND=mock IMAGE_EDIT_TOKEN=debug-token \
  python3 -m uvicorn app:app --host 127.0.0.1 --port 8094
```

手机/模拟器把 8094 地址指到这台机器（Tailscale IP 或 `10.0.2.2`），Token 用 `debug-token`；先验证上传、提交、轮询、预览、自动保存，再接真实 ComfyUI。

### 12.3 curl 对照

```bash
# 生图
curl -s -X POST http://127.0.0.1:8094/v1/jobs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"workflow":"krea2_t2i","mode":"generate","prompt":"雨夜咖啡馆","images":[],"size":"832x1216","steps":10,"request_id":"debug-1"}'

# 查任务
curl -s http://127.0.0.1:8094/v1/jobs/<id> -H "Authorization: Bearer $TOKEN"

# 能力
curl -s http://127.0.0.1:8094/v1/workflows -H "Authorization: Bearer $TOKEN"
```

App 里提交后，如果 UI 不对，先用同样的 JSON 走 curl，判断是客户端还是服务端问题。

### 12.4 Agent 调试

- 准备 10 条固定描述：
  - 明确改图：换背景、去路人、换衣服、老照片修复。
  - 明确生图：画一张赛博朋克城市、做一张海报、生成头像。
  - 模糊：参考这张画一张、帮我弄好看点、没有主图说“换个背景”。
- 只记录 `mode/workflow/message` 和 `prompt_len`，不记录 prompt 原文/照片。
- 预览页/调试页可展示 Agent 的 JSON，方便核对；不要在生产 UI 里展示长解释。
- JSON 修复失败时，确认会退回手动 chips，而不是卡在 loading。

### 12.5 日志与隐私

- 建议 Logcat tag：`Creative`、`CreativeAgent`、`CreativeRepo`。
- 只打：job_id、workflow、status、stage、queue_position、elapsed、prompt_len。
- 不打：Token、AI Key、prompt 原文、base64、照片路径。
- `adb logcat -s Creative CreativeAgent CreativeRepo`；不要把 logcat 落盘到手机（项目规则）。

### 12.6 错误与边界模拟

| 场景 | 模拟方式 | 预期 |
| --- | --- | --- |
| 401 | 改错 Token | 提示检查服务设置，不无限重试 |
| 离线 | 关 Tailscale/停网关 | 顶部离线提示，保留草稿 |
| 429 | 连续提交超过队列 | 提示稍后再试，草稿不丢 |
| 结果过期 | 用旧 job 的 result URL | 显示「重新生成」 |
| Agent 没 Key | 清空 AI 配置 | 手动 chips 可用 |
| 服务端未升级 | `/v1/workflows` 404 | 提示升级 8094 |
| 冷启动/切模型 | 先改图再生图 | 任务显示加载模型，不误判卡死 |

### 12.7 生命周期与保存

- 提交后立即关页/切后台，再回来：任务应还在，状态能同步。
- 杀进程后重开：从 `/v1/jobs?status=active` 恢复；已完成但未保存的结果要补存。
- 旋转/深色模式/四套配色：输入、任务卡、历史不应丢状态。
- 自动保存只保存一次；手动保存不应重复新增。
- 相册写入失败要有提示，不能静默失败。

### 12.8 Compose 预览

- 给空状态、加载、追问、任务卡（各阶段）、成功、失败、历史写 Preview。
- 预览时跳过 Photo Picker / 权限 launcher（`isPreview=true`），否则会崩。
- 预览用真实比例的假数据，不要用空白占位。

## 13. 验收标准

- 手动改图：1 主图 + 1 参考图 → 成功出图、自动保存。
- 手动生图：无图 → 成功出图、自动保存。
- Agent 清晰场景直接提交；模糊场景只追问一次；失败能手动降级。
- 任务卡阶段/排队/耗时/取消/重试/删除正确。
- 关页/后台/杀进程后任务能恢复；已完成结果能补存。
- 旧「AI 修图」页面和接口不受影响。
- 没有照片、prompt、Token 出现在 AI 请求或日志里。
- 401/404/429/422/离线/服务端未升级都有可操作提示。
- 生图尺寸/步数符合服务端枚举；改图 1–2 张图。

## 14. 非目标与二期

- v1 不做视频；二期 4 秒竖屏 MiniMax H3。
- 不做参考图生图/图生图；生图只读文字。
- 不做 Krea2 双采极清 / SeedVR2 放大。
- 不做批量 >2 张、多用户、云端同步、模型/LoRA 管理。
- 不把模型跑在手机上，不直连 8188。

## 15. 本次服务端改动摘要

- `schemas.py`：`workflow` / `mode` / `images` 允许为空（生图）/ 按工作流校验 steps、size。
- `workflows.py`：`qwen_edit`、`krea2_t2i` 注册表。
- `backend.py`：Krea2 工作流、ComfyUI 队列等待、模型切换 `/free`、阶段回调。
- `jobs.py`：按工作流分派、参数/缓存键、阶段、超时。
- `storage.py`：`workflow` / `mode` / `media_type` / `params_json` 增量列。
- `app.py`：`/v1/workflows`、health 扩展、批量只允许 `qwen_edit`。
- `README.md` / `.env.example`：Krea2 配置、接口示例、调试方案。

服务端代码已改，但**没有重启线上网关、没有跑端到端测试**；部署/调试步骤见 `server/image-edit/README.md` 第 11 节。
