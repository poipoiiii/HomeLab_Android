# AI 与对话（小砂糖）

改 AI、工具、prompt、对话记录、Markdown、日历工具前先读本文件。

## 架构

- 使用 Google ADK for Kotlin / ADK for Android（`com.google.adk:google-adk-kotlin-core-android` + `google-adk-kotlin-integrations-openai` 1.4.0）。`feature/ai/AiAgent.kt` 负责组装 `LlmAgent`、`InMemoryRunner`、`InMemorySessionService`，会话、流式事件、工具调用循环全部交给 ADK；不要恢复手写 SSE / tool loop。
- 模型：`AiProvider` 支持 DeepSeek、OpenAI、Moonshot、通义千问、硅基流动、Ollama 和自定义 OpenAI 兼容地址，由 ADK 的 `ChatCompletions`（`models.openai`）调用；baseUrl 统一走 `AiProvider.openAiBaseUrl()`。
- AI 配置独立在 `feature/ai/ui/AiSettingsScreen.kt`（AI 页右上角「配置」和设置页入口），AI 页只负责对话；不要把服务商选择、Key 输入塞回对话页。

## 形象与人格

- 名称「小砂糖」，头像源图 `static/pictures/3.jpg`，资源 `res/drawable-nodpi/ai_avatar.jpg`；AI 页标题、抽屉导航和系统 prompt 统一使用这个名字。
- 人格以头像角色「松阪砂糖」的气质为灵感：轻二次元、语气软、带一点轻微占有欲（偶尔“只准问我一个人哦”），但不做病娇、不威胁、不道德绑架；自然口语、少列表，避免 AI 腔。
- 追问建议：每次回答后 `AiAgent.suggestFollowUps` 用同一服务商生成 3 个用户可能继续问的短问题，AI 页以可点击行展示；生成失败时用本地兜底，不阻塞主回答。

## 工具集

- `feature/ai/AgentTools.kt`：成员函数加 `@Tool` / `@Param`，KSP（`google-adk-kotlin-processor`）编译期生成 schema 与 `generatedTools()`；不要手写 JSON Schema，也不要恢复 `AiAgent` 里的手写循环。
- 包含系统资源、应用列表、使用时长/存储排行、SAF 文件搜索、Homelab 健康、账本流水，以及 `find_photos` / `list_recent_photos` / `upload_recent_photos` / `describe_photo` / `generate_image`。
- `get_recent_expenses` 支持 `month`（yyyy-MM，默认本月）、`type`（expense/income/transfer）、`minAmount`（整数元）、`limit`（默认 30，最大 200）、`sort`（date/amount）；内部先拉 500 条再本地筛选，避免默认页大小截掉大额记录。
- 工具调用策略：由 ADK 自动处理 `tool_calls`（`ChatCompletions` 原生函数调用）+ 系统 prompt 约束，不要用关键词/正则硬编码“这句话该不该调工具”；闲聊、常识、写作必须允许直答。
- `AgentTools` 的 `onPhotos` 回调把找到/识别的照片 uri 挂到工具消息上，AI 页显示缩略图并可点开大图。

## 识图与生图

- `describe_photo` 用 ADK `ChatCompletions` + `Part.inlineData`（`Blob`）；`generate_image` 仍走 `AiClient.generateImage`，因为 ADK 不覆盖 `/v1/images/generations`。`AiProvider.visionModel` / `imageModel` 单独配置；DeepSeek 未填识图模型时默认 `deepseek-flash`。
- `generate_image` 有成本：只有用户明确要求出图才调用；只要提示词就停在 `describe_photo`。

## 家里修图（Qwen-Image-Edit-2511）

- 上手文档：手机端 [image-edit-android.md](../image-edit-android.md)，服务端 [server/image-edit/README.md](../../server/image-edit/README.md)；接口与实测数据见 [image-edit-design.md](../image-edit-design.md)。
- `feature/imageedit/`：`ImageEditClient`（HTTP）→ `ImageEditRepository`（压缩、提交、轮询、保存相册、Cloudreve）→ `ImageEditStore`（`saved_job_ids`，避免重复写相册）。服务端在 `server/image-edit/gateway`（FastAPI + SQLite job，异步 job + 队列），设计见 `docs/image-edit-design.md`。
- 工具：`edit_photo`（1–3 张参考图，等结果并保存）、`batch_edit_photos`（2–8 张，只提交）、`list_image_edit_jobs`（只读）、`save_image_edit_result`（补存）。不要用 `generate_image` 假装修图。
- 场景库：`feature/imageedit/ImageEditPresets.kt` 的 `IMAGE_EDIT_PRESETS` 是唯一来源（10 个场景：background / remove / outfit / portrait / body / style / light / product / restore / compose），每个场景有 `whenToUse` 和带 `{change}` 占位的 `template`。`edit_photo` / `batch_edit_photos` 的 `scenario` 参数由 `composeImageEditPrompt()` 套模板，AI 只传 20 字内的具体要求；场景不匹配时留空、自己写完整指令。系统提示里的场景表与这里必须同步，新增场景要同时改两处。
- 独立页 `feature/imageedit/ui/ImageEditScreen.kt`（修图/任务两个 tab）与 `ImageEditSettingsScreen.kt`（地址、Token、模型、步数、最长边、自动保存、测试连接）；入口在设置页「小砂糖」分组、首页快捷面板、AI 页空状态，`AppRoot` 用 `state.imageEdit.open / settingsOpen` 做全屏 overlay。
- 配置项 `imageEditUrl / imageEditToken / imageEditModel / imageEditSteps / imageEditMaxSide / imageEditAutoSave`，Token 只存本机 DataStore；图片只发给家里的网关，不经过对话服务商。
- Coil 加载结果/输入缩略图时带不上 `Authorization` 头，`ImageEditRepository.thumbUrl` 会把 `?token=` 拼进 URL；服务端两个图片接口都接受 query token。


## 对话记录与 Markdown

- `ChatMessage` 定义在 `feature/ai/AiModels.kt`，由 `feature/ai/ChatStore.kt` 持久化（DataStore 文件 `homelab_chat`，`AppContainer.chat`，最多 200 条）；ADK 的 `InMemorySessionService` 只在内存中，进程重启后首次发送会用已保存记录重建会话。清空对话时要同时清 DataStore 并调用 `AiAgent.clearHistory()` 删除 ADK 会话。
- Markdown 用自研轻量渲染 `feature/ai/ui/Markdown.kt`（支持表格、```mermaid flowchart 的简化流程图，由内置绘图器渲染，不引入 WebView 或 CDN）；ADK 之外没有引入其他 AI / Markdown 框架。

## 已知陷阱

- ADK 的 `Instruction` 会把 `{...}` 当成 session state 占位符（`A[开始] --> B{判断}` 会报 `Context variable not found: 判断`）。系统 prompt 里不要写字面花括号，需要说明格式时用文字描述（例如“判断节点用花括号包住文字”）。

## 权限与只读约束

- AI 工具默认只读，例外只有四个：`generate_image` 把新图片写入应用目录并登记到相册（`Pictures/HomelabAI`，Android 10+）、日历工具增删改私人日历记录、界面自动化工具按用户明确要求点击/输入/返回其他应用、`upload_recent_photos` 把用户明确指定的照片上传到 Cloudreve；不要新增启停容器、提交下载、删除/重命名已有文件、修改设置之类的工具。
- 相册权限：`READ_MEDIA_IMAGES`（Android 13+）/ `READ_EXTERNAL_STORAGE`（maxSdk 32），运行时入口在设置页「相册」卡片；不要引入 `MANAGE_EXTERNAL_STORAGE`。`find_photos` 无权限时返回 `need_permission`，属于正常状态而不是异常。
- 文件搜索走 SAF 授权目录（`OpenDocumentTree` + `DocumentFile`），只读；不要默认引入 `MANAGE_EXTERNAL_STORAGE`。
- `QUERY_ALL_PACKAGES` 用于列应用；`PACKAGE_USAGE_STATS` 是特殊权限，需要 AppOps / 系统设置页授权，不是运行时弹窗。
- Android 限制：普通应用读不到其他应用的 PSS 内存，也没有可申请的权限，不能承诺“开权限就能看内存”。`get_top_memory_apps` 只能用“系统内存 + 使用时长/存储占用排行”替代；使用时长/存储排行需要用户在系统设置里授予 `PACKAGE_USAGE_STATS`。入口在设置页「权限 → 使用情况访问」按钮，系统路径是 设置 → 应用 → 特殊应用权限 → 使用情况访问（部分机型在 设置 → 安全与隐私 → 隐私 → 使用情况访问）；它不在应用详情页的权限列表里，也不解锁内存数据。工具返回 `usage_access:false` 就是没授权。
- API Key 只存本机 DataStore，且 `allowBackup=false`；不要把 Key / Token 写进日志、文档、提交或截图。`.cache` 下的截图可能包含 Key，用完立即删除。

## 私人日历

- `AgentTools` 提供 `list_calendar_entries` / `save_calendar_entry` / `create_calendar_entries` / `delete_calendar_entries`，统一走 `CalendarRepository`（DataStore `homelab_calendar`）。日期必须是 `yyyy-MM-dd`，`type` 只能是 `travel` / `important` / `anniversary` / `todo`；法定节假日不得编造未公布年份的放假/调休安排；批量写入按 `(date,title)` 去重。
- 日历页支持左右滑动换月、快捷类型直接新建、待办勾选，以及只读展示账本月度流水（`MainViewModel.loadCalendarBills`，与账单页 `billMonth` 相互独立）。
- `CalendarEntry.time`（HH:mm）+ `reminder` 由 `feature/calendar/CalendarReminder.kt` 用 AlarmManager 排本地通知，开机/覆盖安装后由 `CalendarReminderBootReceiver` 重排。
