# AI 创作（图片）设计稿 v1

> 状态：口径已确认，设计评审中（2026-11-16）
> 范围：Android `poi's home` + 家里 8094 网关 + ComfyUI
> 结论：v1 只做图片；新建「AI 创作」页；端侧 Agent 复用现有 AI Key；改图用 Qwen-Image-Edit，生图用 Krea2 基础文生图；视频二期再做 4 秒竖屏。

## 1. 一句话描述

用户在「AI 创作」页上传 1 张主图、可选 1 张参考图，写一句描述；手机上的 Agent 判断这是「改图」还是「生图」，自动选工作流并整理提示词，直接提交给家里的 8094 网关；网关排队调用 ComfyUI 出图，App 轮询任务、自动保存到相册，并保留历史与重试入口。

## 2. 目标与非目标

### 目标

- 新增抽屉入口「AI 创作」，独立页面，不改现有「AI 修图」和「AI-Web-Video」。
- 支持两条链路：
  - 改图：主图 + 可选参考图 → Qwen-Image-Edit-2511。
  - 生图：纯文字 → Krea2-Moody 基础文生图。
- 端侧 Agent：复用「设置 → 小砂糖 → AI 配置」里的服务商和 Key，不新增 Key；Agent 只看文字和图片数量，不看照片内容。
- 明确时直接提交；不明确时最多追问一次；失败/没 Key 时退回手动「改图 / 生图」。
- 异步任务：排队、等待 GPU、加载模型、生成、解码、保存；切后台/关页任务继续，回来自动同步。
- 结果自动保存到相册；支持预览、再做一张、继续改、删除、重试。
- 扩展 8094 网关，旧修图接口保持兼容；一个 GPU 队列，不与视频任务抢卡。

### 非目标

- v1 不做视频；二期再做 4 秒竖屏 MiniMax H3。
- v1 不做参考图生图/图生图；生图只读文字。参考图只参与改图。
- v1 不做 Krea2 双采极清 / SeedVR2 放大；只用基础生图。
- 不做批量 >2 张、多用户、云端同步、模型/LoRA 管理。
- 不把模型跑在手机上，不直连 ComfyUI，不向第三方 AI 发送照片。
- 不显示假的扩散步百分比；只显示阶段、排队位置和已用时间。

## 3. 已确认口径

| 维度 | v1 结论 |
| --- | --- |
| 入口 | 新建「AI 创作」抽屉页 |
| 输入 | 1 主图（可选）+ 1 参考图（可选）+ 一句描述 |
| Agent | 端侧，复用现有 AI 服务商/Key；只看文字 + 图片数量 |
| 改图 | 有主图且描述是修改/保留 → `qwen_edit` |
| 生图 | 无主图或明确说“画一张/生成一张” → `krea2_t2i` |
| 确认 | 清晰直接提交；不明确只追问一次；任务卡可取消 |
| 后端 | 扩展现有 8094 网关，统一 Token、SQLite、队列、结果 |
| 视频 | 二期，4 秒竖屏 |
| 画质 | Krea2 基础生图；极清/SeedVR2 二期 |
| 隐私 | 照片只到 8094；Agent 不接收照片字节 |

## 4. 用户流程

### 4.1 首页布局

```text
┌ AI 创作 ──────────────────────────── 设置 ┐
│                                            │
│  ┌────────────┐  ┌────────────┐            │
│  │ 主图 +     │  │ 参考图 +   │  （可选）  │
│  └────────────┘  └────────────┘            │
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

- 主图槽：系统照片选择器；不申请相册权限。
- 参考图槽：标注「仅改图使用」；生图不会读取。
- 判断 chip：默认「自动」；用户可强制「改图 / 生图」，Agent 不再判断。
- 描述为空时不能提交；主图为空且选择「改图」时提示先选主图。

### 4.2 提交路径

1. 用户点「开始创作」。
2. 若判断为「自动」且描述明确：
   - 有主图 + 改图语义 → 直接提交 `qwen_edit`。
   - 无主图 + 生图语义 → 直接提交 `krea2_t2i`。
   - 提交后不弹全屏确认；顶部出现 2–3 秒状态条「已按改图提交 · Qwen-Image-Edit」，下面出现任务卡。
3. 若描述不明确：
   - 内联追问一次：「你是想改这张图，还是参考它生成一张新图？」两个按钮。
   - 用户选完立即提交；不出现第二次追问。
4. 若没配 AI Key：
   - 显示「需要先在 设置 → 小砂糖 → AI 配置 里填服务商」，按钮「去设置」。
5. 若 Agent 调用失败/返回非 JSON：
   - 自动降级为手动判断 chip，并提示「这次没判断出来，你选一下」。
   - 不阻塞用户。
6. 若选「生图」但已经上传了照片：
   - 提示「生图只用文字，不读取照片」；用户可切回改图或继续生图。

### 4.3 任务卡

```text
┌ 改图 · Qwen-Image-Edit ────────── 取消 ┐
│ 把背景换成傍晚海边，保留五官和衣服     │
│ 排队中 · 前面还有 2 个 · 已用 12 秒     │
└────────────────────────────────────────┘
```

阶段：

| stage | 展示 | 说明 |
| --- | --- | --- |
| `queued` | 排队中 · 前面还有 N 个 | 网关队列 |
| `waiting_gpu` | 等待 GPU · 前面有视频任务 | ComfyUI 队列/外部任务 |
| `loading` | 加载模型 · 首次约 45 秒–4 分钟 | Krea2 冷启动约 45 秒；Qwen 编辑约 3–5 分钟 |
| `sampling` | 生成中 · 已用 X 秒 | 不显示假百分比 |
| `decoding` | 解码/保存 | 最后阶段 |
| `succeeded` | 已完成 · 已保存到相册 | 可预览/分享/再做一张 |
| `failed` | 失败 + 原因 | 可重试/删除 |
| `canceled` | 已取消 | 可删除 |

### 4.4 结果与历史

- 完成后自动保存到相册（沿用现有修图页的自动保存设置；不新增重复开关）。
- 任务卡显示缩略图；点开全屏预览，可保存/分享。
- 「再做一张」：保留描述和比例，清空结果，重新提交；seed 默认随机。
- 「继续改」：把结果图作为新的主图，回到输入区；参考图保留。
- 历史：最近 20 条；状态、缩略图、时间、删除；`result_expired` 提示重新生成。
- 删除二次确认；已保存到相册的副本不受影响。

## 5. Agent 设计

### 5.1 位置与密钥

- 运行在手机端，复用「设置 → 小砂糖 → AI 配置」的服务商、模型和 Key。
- 不新增 Key，不把 AI Key 发给 8094；8094 仍用原来的 `IMAGE_EDIT_TOKEN`。
- 网关地址/Token 复用现有「AI 修图 → 服务设置」，不新增一套配置。
- Agent 请求只包含：
  - 用户描述原文；
  - `has_main` / `has_ref`（布尔）；
  - 页面当前选择（自动/改图/生图）；
  - 允许的输出枚举（`qwen_edit` / `krea2_t2i`）。
- 不包含照片字节、缩略图、相册路径或 EXIF。

### 5.2 输出协议

只输出一个 JSON 对象：

```json
{
  "status": "plan",
  "message": "这句话更像改图，我会保留人物五官和构图。",
  "plan": {
    "mode": "edit",
    "workflow": "qwen_edit",
    "prompt": "把背景换成傍晚海边，保留人物五官、发型、姿势和衣服不变",
    "aspect": "3:4",
    "size": "832x1216",
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

- `mode` 与 `workflow` 必须匹配：`edit -> qwen_edit`，`generate -> krea2_t2i`。
- `size` 必须来自当前工作流允许的比例/尺寸。
- 模型输出非 JSON 时，App 自动修复一次；仍失败则退回手动 chip。
- 模型不能直接提交任务；提交动作由 App 完成。

### 5.3 判断规则

| 输入 | 行为 |
| --- | --- |
| 有主图 + 改图词（改/换/去掉/保留/修复/上色/合成/把…变成/背景/衣服/路人/划痕） | 直接 `qwen_edit` |
| 无主图 + 生图词（画/生成/做一张/海报/头像/插画/壁纸/风格） | 直接 `krea2_t2i` |
| 有主图 + 生图词 + “参考这张/类似/同款” | 追问一次：改图 / 生成新图 |
| 有主图 + 描述太泛（“帮我弄好看点”） | 追问一次：想改哪一部分 |
| 无主图 + 改图词（“换个背景”） | 追问：先选一张照片，或让我生成一张新图 |
| 已选「改图」但无主图 | 提示选图，不提交 |
| 已选「生图」但有照片 | 提示“生图不读取照片”，可继续或切回改图 |
| Agent 失败/没 Key | 显示手动 chip，不阻塞 |

- 最多追问 1 轮；用户回答后强制出方案。
- 追问文案要短，最多 2 个选项，避免像表单。
- Agent 的 `message` 只在任务卡/状态条里用一句话展示，不做长篇解释。

### 5.4 提示词整理

- 改图：保留「人物五官、发型、姿势、构图、衣服」等约束；如果命中现有 `IMAGE_EDIT_PRESETS`，复用 `composeImageEditPrompt()` 的场景模板。
- 生图：把描述整理成 Krea2 可读的短提示词；不编造用户没说的细节；比例/风格从描述推断，推断不出用默认 3:4。
- 不把用户原文无条件拼长；避免“电影感/8K/大师作品”等空泛堆砌。
- Krea2 的负面提示词默认空；CFG 固定 1.0。

## 6. 网关设计（8094 扩展）

### 6.1 兼容策略

- 现有 `/v1/jobs`、`/v1/jobs/batch`、`/v1/edit`、结果/缩略图 URL 全部保留。
- 旧客户端不传 `workflow` → 默认 `qwen_edit`，行为与现在一致。
- 新客户端传 `workflow` + `mode`；`images` 对 `krea2_t2i` 允许为空。
- 旧的「AI 修图」页面继续工作，不改接口。

### 6.2 请求字段

在现有 `JobRequest` 上增量增加：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `workflow` | `qwen_edit` / `krea2_t2i` | 默认 `qwen_edit` |
| `mode` | `edit` / `generate` | 与 workflow 匹配；用于 UI |
| `images` | 0–2 张（新页面） | `qwen_edit` 至少 1 张；`krea2_t2i` 必须 0 张 |
| `size` | `宽x高` | 由工作流允许的预设校验 |
| `steps` | 整数 | `qwen_edit` 20–60；`krea2_t2i` 6–20，默认 10 |
| `aspect` | 可选 | UI 展示用；服务端以 `size` 为准 |

示例：

```json
{
  "workflow": "krea2_t2i",
  "mode": "generate",
  "prompt": "雨夜咖啡馆，窗边的人，35mm 胶片，暖色室内光",
  "label": "雨夜咖啡馆",
  "images": [],
  "size": "832x1216",
  "steps": 10,
  "seed": null,
  "request_id": "creative-..."
}
```

### 6.3 工作流注册表

| id | mode | 模型/节点 | 输入 | 默认参数 | 预计耗时 |
| --- | --- | --- | --- | --- | --- |
| `qwen_edit` | edit | Qwen-Image-Edit-2511 Q4_K + Qwen2.5-VL + Qwen VAE | 1–3 张参考图 | steps 24–40，cfg 1.0 | 约 4 分钟；首次加载 3–5 分钟 |
| `krea2_t2i` | generate | `Krea2-Moody-Mix-premium_int8_convrot.safetensors` + `qwen3vl_4b_fp8_scaled.safetensors`（type `krea2`）+ `qwen_image_vae.safetensors` | 纯文字 | steps 10，cfg 1.0，euler_ancestral / beta | **冷启动 45.1s；热 5.0s**（832×1216，2026-11-16 实测） |

Krea2 允许尺寸（均为 64 的倍数）：

- 1:1 `1024x1024`
- 3:4 `832x1216`
- 4:3 `1216x832`
- 9:16 `768x1344`
- 16:9 `1344x768`

v1 不接 Krea2 的 `双采极清流` / `SeedVR2`；需要时再做成 `krea2_hq` 工作流。

### 6.4 任务表与状态

现有 job 表增量列（SQLite 迁移，旧行给默认值）：

```sql
ALTER TABLE jobs ADD COLUMN workflow TEXT NOT NULL DEFAULT 'qwen_edit';
ALTER TABLE jobs ADD COLUMN mode TEXT NOT NULL DEFAULT 'edit';
ALTER TABLE jobs ADD COLUMN media_type TEXT NOT NULL DEFAULT 'image';
ALTER TABLE jobs ADD COLUMN params_json TEXT NOT NULL DEFAULT '{}';
```

- `JobOut` 增加 `workflow`、`mode`、`media_type`。
- 状态机沿用：`queued → running → succeeded/failed/canceled`；`cancel_requested` 尽力中止。
- `stage` 增加：`waiting_gpu`、`loading`、`sampling`、`decoding`。
- 结果仍是 PNG；`media_type=image`。视频二期加 `video/mp4`。

### 6.5 队列与 GPU 协调

- 网关 `MAX_CONCURRENCY=1`，所有图片任务串行。
- 提交 ComfyUI 前先查 `GET /queue`：
  - 如果 ComfyUI 里有外部任务（例如 ref2va 视频脚本）在跑，网关任务显示 `waiting_gpu`，不抢卡。
  - ComfyUI 队列清空后再提交。
- 记录 `last_workflow`：
  - `qwen_edit` 和 `krea2_t2i` 是不同模型族，24GB 显存不能同时常驻。
  - 切换工作流时调用 ComfyUI `/free`（`unload_models` + `free_memory`），再提交；Krea2 冷启动约 45 秒，Qwen 编辑约 3–5 分钟。
- `img-edit-comfy-idle.timer` 的 30 分钟闲置判断必须把新任务算作活动：
  - 检查网关 `queued/running` 任务、ComfyUI `/queue`、输出目录；
  - 有任务时不能 `systemctl stop comfyui`。
- 当前视频任务测试时被中断过；接入网关后，不建议再直接向 8188 提交脚本任务，避免绕过队列。

### 6.6 健康检查与能力发现

- `GET /health` 保留现有 `status/model/backend/queue/gpu_vram_used_mb/detail`，增加：
  - `workflows`: 可用工作流列表；
  - `comfy_queue`: `running/pending`；
  - `loaded_workflow`: 最近加载的工作流；
  - `gpu_busy`: 是否有外部任务。
- 新增 `GET /v1/workflows`：供 App 设置页/诊断展示工作流、尺寸、预计耗时；不要求客户端每次请求都调。
- 错误码沿用现有风格，新增：
  - `workflow_not_supported`
  - `images_not_allowed`
  - `images_required`
  - `gpu_busy`（可选，主要用状态而不是错误）
  - `model_switch_failed`
  - `comfy_timeout`

### 6.7 超时与容量

- `qwen_edit`：沿用 `COMFY_TIMEOUT=900`，job 超时至少 900s。
- `krea2_t2i`：冷启动 45s、热 5s，给 300s 超时足够；模型切换后仍以 300s 上限。
- 现有 `JOB_TIMEOUT_SECONDS=1200` 需要支持按工作流覆盖；不要因为 Krea2 缩短 Qwen 的超时。
- 结果 TTL / 容量沿用：结果 7 天、元数据 30 天、`RESULT_MAX_GB=20`。
- 视频二期需要更大的结果容量和 `media_type=video`，不在 v1 范围。

## 7. 实测数据

2026-11-16，在同一台 WSL + RX 7900 XTX 24GB 上：

| 工作流 | 尺寸 | 步数 | 冷启动（含加载） | 热运行 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Krea2 基础生图 | 832×1216 | 10 | **45.1s** | **5.0s** | 轮询粒度 5s，热运行实际可能更短 |
| Qwen-Image-Edit-2511 | 1088×1088 | 30 | 3–5 分钟 | 约 4 分钟 | 来自现有服务端文档与实测 |
| MiniMax H3 ref2va | 432×768 / 192 帧 | 12 | — | 27 分钟后被中断 | 视频二期，必须按长任务设计 |

Krea2 基准图：`.cache/creative/krea2-bench-1.png`（832×1216，10 步）。

## 8. 错误与提示文案

| 场景 | 提示 |
| --- | --- |
| 没配 AI Key | 「先在 设置 → 小砂糖 → AI 配置 里选一个服务商」+「去设置」 |
| 没配网关 | 「还没配置修图网关」+「去设置」 |
| 401 | 「Token 不对，检查 8094 的 IMAGE_EDIT_TOKEN」 |
| 服务离线 | 「家里的服务没连上，检查 Tailscale / 网关」 |
| ComfyUI 休眠 | 「正在唤醒模型，首次约 45 秒–4 分钟」 |
| 队列满 429 | 「前面任务多，稍后再试」 |
| 等待视频任务 | 「GPU 正在跑视频，前面还有 1 个任务」 |
| 结果过期 | 「结果已保留 7 天，重新生成一次」 |
| Agent 失败 | 「这次没判断出来，你选一下改图/生图」 |
| 生图带照片 | 「生图只用文字，不读取照片」 |

## 9. 安全与隐私

- AI Key 只在手机 DataStore；不上传 8094，不进日志。
- 照片只上传到 8094；Agent 只接收文字 + 图片数量。
- 网关日志只记录 job_id、workflow、状态、耗时、字节数；不记录 prompt、照片、Token。
- 沿用 `allowBackup=false`；Token 不写进文档/提交/截图。
- 不暴露 8188 到公网；App 只访问 8094。
- 结果只新增到相册，不覆盖原图；删除任务不影响已保存的相册副本。

## 10. 代码落点（尚未实现）

### Android

```text
feature/creative/
├─ CreativeModels.kt       请求/任务/结果 DTO
├─ CreativeRepository.kt   复用 8094 HTTP + 轮询
├─ CreativeAgent.kt        端侧判断 + JSON 修复 + 手动降级
├─ CreativePrompt.kt       判断规则与提示词模板
├─ CreativeViewModel.kt    页面状态、任务同步、自动保存
└─ ui/CreativeScreen.kt    上传、描述、任务卡、历史
```

- 复用 `ImageEditPrefs` 的网关地址/Token 和 `ImageEditRepository` 的轮询/保存模式。
- 复用现有 AI 配置（`SettingsStore` / AI 服务商）；不新增 Key 设置。
- 导航在 `AppRoot` 增加抽屉入口；页面为独立页，不是 overlay。
- 任务轮询、自动保存、MediaStore 逻辑参考 `feature/imageedit`。

### 网关

```text
server/image-edit/gateway/
├─ schemas.py      增加 workflow/mode/media_type 校验
├─ jobs.py         按 workflow 分派、阶段、超时
├─ backend.py      增加 Krea2 工作流构建与模型切换
├─ workflows.py    工作流注册表（可选新文件）
├─ storage.py      增量列与查询
└─ app.py          /v1/workflows、health 扩展
```

## 11. 验收标准

- 旧「AI 修图」页面与接口回归通过：1–3 张图、批量、取消、结果、401/429 不变。
- 新页面能上传 1 主图 + 1 参考图；改图任务成功。
- 无主图时能生图；Krea2 冷启动 ≤ 90s、热运行 ≤ 15s（以实测为准）。
- Agent 对 10 条样例描述判断正确；模糊描述只追问一次。
- 没 Key/Agent 失败时能手动选改图/生图，不阻塞。
- 任务切后台/关页继续跑，回来能同步并自动保存。
- 队列与视频任务串行，不抢卡、不 OOM；`/health` 能看出 GPU 忙/加载中。
- 删除/重试/结果过期提示正确；相册副本不被删除。
- 隐私检查：照片不出现在 AI 请求、日志、文档或提交中。

## 12. 分期

| 阶段 | 内容 |
| --- | --- |
| P0 | 口径确认 + Krea2 基准（已完成） |
| P1 | 8094 网关扩展：workflow、Krea2、队列、迁移、curl 测试 |
| P2 | Android「AI 创作」页：手动模式 + 任务卡 + 自动保存 |
| P3 | 端侧 Agent：判断、追问、降级、提示词模板 |
| P4 | 参考图/继续改/历史/分享打磨 |
| P5 | 视频：MiniMax H3 4 秒竖屏，独立长任务 UX |

## 13. 风险与待确认

- Krea2 和 Qwen 编辑不能同时常驻；切换时 `/free` 的时机和失败回退需要实测。
- Krea2 热运行 5s、冷启动 45s；如果先跑 Qwen 再切回 Krea2，首次会重新加载。
- Qwen 编辑约 4 分钟，远慢于 Krea2；UI 不要把两者写成同一个预计时间。
- 当前 ComfyUI 里有一次 ref2va 视频任务被中断；接入网关后要避免再绕过队列。
- 1 主图 + 1 参考图的“参考图”只用于改图；生图不读取照片。若你希望参考图参与生图，需要另接 Krea2 图生图/参考工作流。
- 现有 `JobRequest.images` 是 `min_length=1`；改成允许空时要确保旧客户端和批量接口不受影响。
- SQLite 迁移前先备份 `jobs.sqlite`；旧 job 的 `workflow` 默认 `qwen_edit`。
