# AGENTS.md

本文件适用于本目录（`d:\taoxi_download_for_use\no_use_do\android`）下的所有工作。任何 Agent 开始任务前必须先读完本文件。

## 1. 硬性规则（必须遵守）

### 1.1 禁止向 C 盘写入任何文件
- 所有新建、下载、解压、编译、缓存、日志、临时文件，一律只能落在本目录内。
- 禁止使用 `C:\Users\...\AppData\Local\Temp`、`%TEMP%`、用户主目录、桌面等位置作为工作区。
  - 如果某个工具默认把产物写到 C 盘（如 `mktemp`、Python `tempfile`、pip/npm 缓存、反编译工具默认输出目录），必须显式把输出路径改到本目录下的 `.cache`。
- 读取 C 盘已有文件是允许的；**写入**禁止。
- 完成后自查：本次任务是否在 C 盘产生过任何文件或目录；如有，删除并迁移到 `.cache`。

### 1.2 缓存与中间产物统一放 `.cache/`
- 所有临时文件、解包内容、反编译结果、下载缓存、日志、脚本运行产物写入 `.cache/`。
- `.cache/` 内容视为可随时删除，不得作为最终交付物。
- 需要在 `.cache/` 内建子目录区分任务，例如 `.cache/apk_unzip/`、`.cache/decompile/`、`.cache/logs/`。
- 不要在项目根目录堆 `tmp/`、`out/`、`build/`、`分析结果/` 之类的散落目录。

### 1.3 超过 1MB 的文件放 `static/`
- 阈值：1 MB = 1,048,576 字节。单个文件 **大于等于** 该体积时，放入 `static/`。
- 适用对象包括但不限于：APK、SO、DEX、JAR、图片、字体、压缩包、导出的报告、模型文件、PDF。
- 小于 1MB 的源码、配置、脚本、说明文档留在根目录或正常业务目录中，不要为了"整齐"把所有文件都塞进 `static/`。
- 写入前应确认实际大小，不要凭感觉判断。
- 目录约定：

```text
android/
├── agents.md            # 本文件
├── base.apk.1           # 原始输入，只读，不要修改或覆盖
├── .cache/              # 临时文件 / 缓存 / 中间产物（可删除）
├── static/              # >= 1MB 的文件
└── .trae/skills/        # 项目级 skill
```

## 2. 涉及页面设计时

- 只要任务涉及 UI / 页面 / 视觉设计（做网页、报告页面、可视化页面、改版样式等），**必须先完整阅读** 项目内 skill：
  - [SKILL.md](.trae/skills/frontend-design/SKILL.md)
  - 该 skill 来自 Anthropic 官方 `frontend-design`（已下载到本目录，避免写入 C 盘）。
- 先读再设计，不要凭经验直接套模板；遵守其中关于字体、配色、动效、排版的具体约束。
- 页面中的图片资源必须使用真实可用的图片地址，禁止使用占位图或纯色块假装图片。

## 3. 常见错误（重点规避）

### 3.1 数据真实性
- 分析 `base.apk.1` 时，包名、版本号、权限、签名、SDK 等信息必须来自实际解析结果，禁止编造或凭经验猜测。
- 结论要能对应到具体证据（文件路径、偏移、解析命令输出）。无法确认的，明确写"未验证"。
- 不要用示例数据冒充真实分析结果。

### 3.2 输入文件保护
- `base.apk.1` 是原始输入，**只读**：不要重命名、覆盖、截断、原地修改，也不要在其旁边生成同名变体。
- 需要解压或改写时，先复制到 `.cache/` 再操作。
- 不要重复下载同一个 APK 或重复解包；先检查 `.cache/` 是否已有可用产物。

### 3.3 文件创建
- 只创建任务确实需要的文件；不要顺手生成 README、CHANGELOG、说明文档、示例脚本。
- 修改已有文件优先于新建文件；新建前先搜索是否已存在相同能力。
- 不要留下未使用的空目录、空文件、备份文件（`*.bak`、`*_old.*`、`copy of ...`）。

### 3.4 环境与命令
- 环境是 Windows + PowerShell：
  - 顺序执行用 `;`，不要用 `&&`、`||`。
  - 不要使用 `rm -rf`、`cp -r`、`sed`、`awk`、`cat`、`grep`、`find` 等类 Unix 命令；用对应工具或 PowerShell 等价写法。
  - 路径统一使用绝对路径或基于本目录的相对路径，注意反斜杠与含空格路径的引号。
- 不要启动长期运行的进程（本地服务器、监听器）后置之不理；用完必须停止，并确认端口释放。
- 不要依赖交互式命令（需要人工输入 `y`/密码的命令），改用非交互参数。

### 3.5 版本控制
- 未经明确要求，不要 `git commit`、`git push`、`git reset --hard`、强制推送或删除分支。
- 不要把 `.cache/`、`static/` 里的大文件、密钥、`*.keystore`、签名文件提交进仓库。

### 3.6 修改范围
- 只改与任务直接相关的代码/文件，不做顺手重构、不升级无关依赖、不调整无关格式。
- 修改前先读文件，不要基于猜测改代码。
- 不引入无用的兼容层、开关、抽象；不写"以防万一"的兜底逻辑。

### 3.7 交付前自查
- 文件位置是否符合第 1 节（无 C 盘写入、缓存在 `.cache`、大文件在 `static`）。
- 是否有临时文件、调试输出、注释掉的死代码残留。
- 是否有编造的数据或未注明来源的结论。
- 涉及页面设计时是否已先阅读 `frontend-design` skill。

### 3.8 重命名 / 批量替换
- 包名重命名必须覆盖：源码目录、`package`/`import`、`namespace`、`applicationId`、`proguard-rules.pro`、安装/启动脚本、预览或常量里的旧标识符。
- 只做"包名/标识符"级替换，禁止无边界字面替换；例如把 `rong` 全量替换成 `poi` 会破坏 `Strong`、`wrong` 等英文单词。
- 改 `applicationId` 等于换了一个应用：手机上旧包不会自动消失。必须卸载旧包，否则应用详情仍显示旧包名，安装脚本也可能继续拉起旧应用。

## 4. 项目固定事实（HomelabApp）

- 包名 / `namespace` / `applicationId`：`com.poi.homelab`；源码目录 `HomelabApp/app/src/main/java/com/poi/homelab`。
- 应用名：`poi's home`（`res/values/strings.xml`）。
- 图标与头像：源图 `static/pictures/2.jpeg`；启动图标在 `res/mipmap-*/ic_launcher*.png` + `mipmap-anydpi-v26/ic_launcher.xml`；界面头像 `res/drawable-nodpi/app_avatar.png`，用于主页标题左侧和启动动效。
- 默认 Home 地址：`DEFAULT_LAB_URL = ""`（`data/SettingsStore.kt`），不写死真实实例地址；用户在设置页「连接」填写后存本机 DataStore。不要把真实 tailnet 域名、内网 IP、Token 提交进仓库或文档。
- AI：使用 Google ADK for Kotlin / ADK for Android（`com.google.adk:google-adk-kotlin-core-android` + `google-adk-kotlin-integrations-openai` 1.4.0）。`data/AiAgent.kt` 负责组装 `LlmAgent`、`InMemoryRunner`、`InMemorySessionService`，会话、流式事件、工具调用循环全部交给 ADK；不要恢复手写 SSE / tool loop。
- AI 形象：名称为「小砂糖」，头像源图 `static/pictures/3.jpg`，资源为 `res/drawable-nodpi/ai_avatar.jpg`；AI 页标题、底部导航和系统 prompt 统一使用这个名字。人格以头像角色「松阪砂糖」的气质为灵感：轻二次元、语气软、带一点轻微占有欲（偶尔“只准问我一个人哦”），但不做病娇、不威胁、不道德绑架；自然口语、少列表，避免 AI 腔。
- 追问建议：每次回答后 `AiAgent.suggestFollowUps` 用同一服务商生成 3 个用户可能继续问的短问题，AI 页以可点击行展示；生成失败时用本地兜底，不阻塞主回答。
- 模型：`AiProvider` 仍支持 DeepSeek、OpenAI、Moonshot、通义千问、硅基流动、Ollama 和自定义 OpenAI 兼容地址，由 ADK 的 `ChatCompletions`（`models.openai`）调用；baseUrl 统一走 `AiProvider.openAiBaseUrl()`。
- AI 工具集（`data/AgentTools.kt`）：成员函数加 `@Tool` / `@Param`，KSP（`google-adk-kotlin-processor`）编译期生成 schema 与 `generatedTools()`；不要手写 JSON Schema。包含系统资源、应用列表、使用时长/存储排行、SAF 文件搜索、Homelab 健康、账本流水，以及 `find_photos` / `list_recent_photos` / `upload_recent_photos` / `describe_photo` / `generate_image`。
- Cloudreve 照片上传：设置页「连接 → Cloudreve（WebDAV）」保存 `cloudreveUrl` / `cloudreveUser` / `cloudrevePassword` / `cloudreveDir`（默认 `手机照片`），DataStore 键前缀 `cloudreve_`。`data/CloudreveRepository.kt` 只做 PROPFIND 测连接、MKCOL 逐级建目录、HEAD 查重、PUT 上传；站点根地址会自动补 `/dav/`。`upload_recent_photos` 通过 MediaStore 取最近 N 天照片（`DATE_ADDED`，默认 3 天，单次最多 200 张，返回 `truncated` 时按 `next_offset` 继续），同名文件跳过，不删除任何文件；`list_recent_photos` 用于上传前确认数量和范围。相册权限仍是 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`。
- 界面自动化（`automation/UiAutomation.kt`）：`UiAutomationService`（无障碍）+ `automation_status` / `open_app` / `list_screen` / `click_ui` / `input_text` / `press_key` / `scroll_ui` / `wait_for_ui`；只能读当前窗口节点，不监听事件流。用户需在 设置 → 无障碍 手动开启，设置页「权限 → 界面自动化」有状态与跳转入口。
- 识图与生图：`describe_photo` 用 ADK `ChatCompletions` + `Part.inlineData`（`Blob`）；`generate_image` 仍走 `AiClient.generateImage`，因为 ADK 不覆盖 `/v1/images/generations`。`AiProvider.visionModel` / `imageModel` 单独配置；DeepSeek 未填识图模型时默认 `deepseek-flash`。
- 对话记录：`ChatMessage` 定义在 `data/AiModels.kt`，由 `data/ChatStore.kt` 持久化（`AppContainer.chat`，最多 200 条）；ADK 会话只在内存中，进程重启后首次发送会用已保存记录重建会话。
- Markdown：仍用自研轻量渲染 `ui/components/Markdown.kt`（支持 ```mermaid flowchart 的简化流程图）；ADK 之外没有引入其他 AI / Markdown 框架。
- 启动动效：`ui/components/LaunchSplash.kt`，由 `MainActivity` 包裹主界面。启动页支持全屏「轻触跳过」，3 秒后自动结束；遮罩淡出时用 `contentAlpha`（650ms）让主界面内容从透明缓缓浮现。
- 首页壁纸：资源 `res/drawable-nodpi/home_wallpaper.webp`（源 `static/pictures/4.jpeg`，文件实际是 WebP）。是否显示与参数存 `AppSettings.showWallpaper` / `wallpaperOpacity`（0.05..0.9，默认 0.22）/ `wallpaperDim`（0..0.8，默认 0.5）。壁纸是全局的：由 `AppRoot.WallpaperBackdrop` 铺在所有页面（含 AI 配置页）之后，`Scaffold` 用透明底色；不要再在单个页面里画壁纸。卡片与底部导航统一用 `CardSurfaceAlpha`（0.78，`ui/theme/Color.kt`）透出背景。设置页「外观与首页」卡片提供开关与两个滑杆；`showLedgerOnHome` 控制首页是否展示账本区。
- 设置页分组：`外观与首页`（主题 + 介绍模式同卡、全局壁纸、显示账本）→ `连接`（Lab Tools / 账本 / Homarr / Cloudreve / Token / 设备名 + 保存、测试并排）→ `小砂糖`（AI 配置、可搜索目录）→ `权限`（自动化 / 使用情况 / 相册）→ `关于`。新增设置项按这个分组放，别再平铺新卡片。
- 首页结构：`ui/screens/HomeScreen.kt` 依次是 header → 「今天」清单 → Homarr 看板（未配置时回退快捷入口）→ `LabGlanceCard`（本月支出/收入/结余 + 容器状态合并成一张深色卡，`UiState.healthError` 单独承载失败原因）→ 「最近流水」（受 `showLedgerOnHome` 控制）。
- Homarr 看板：`data/HomarrRepository.kt` + `HomarrModels.kt` + `ui/components/HomarrBoardGrid.kt`。设置页「连接」填 `homarrUrl` / `homarrApiKey` / `homarrBoard`（看板名留空时取 `isHome` 或列表第一个）。仓库不写死实例地址：未手动保存过时用 `local.properties` 的 `homarr.url` 构建期注入 `BuildConfig.DEFAULT_HOMARR_URL`，保存后以 DataStore 为准（`homarr_url_touched` 区分「没配过」与「主动清空」）。`/api/boards`、`/api/apps` 是 REST + `ApiKey` 头；看板内容没有 REST GET，只能读 tRPC `board.getBoardByName`（query 带 `batch=1` 信封）。网格只原生还原 app / clock / iframe，其他 widget 降级成「在 Homarr 中查看」，空白行折叠。
- 应用内浏览器：`ui/screens/InAppBrowserScreen.kt`，由 `AppRoot` 的 `browserUrl` 状态以全屏 overlay 盖在 `Scaffold` 之上（含底部导航），磁贴和 iframe 不再把用户丢给系统浏览器。iframe 磁贴在网格内用 `InlinePage` 内嵌预览：`isEnabled = false` 不抢外层滑动手势，`LocalInspectionMode` 下跳过 WebView，点底栏「打开」才进完整浏览器。WebView 需 `@SuppressLint("SetJavaScriptEnabled")`，`onRelease` 里 `stopLoading` + `loadUrl("about:blank")` + `destroy`。
- Todo：列表存 `data/TodoStore.kt`（DataStore `homelab_todo`，带 `initialized` 标记，未初始化时回填默认 3 项）；勾选状态仍由 `ChecklistStore` 按天保存。编辑用 `ui/components/TodoEditorSheet.kt`（增删改 + 保存），勾选不会再改动全局背景色；首页卡片标题为「今天」。
- 快捷入口：`ui/components/QuickEntryPager.kt`，`HorizontalPager` 左右滑动切换卡片，配 `graphicsLayer` 缩放/淡出与动态圆点；只在没配置 Homarr 时作首页兜底，不要再恢复已删除的静态 `StackDeck`。
- 设置页介绍模式：`AppSettings.settingsDetailed`（默认 true）控制「详细 / 简略」；简略模式下 `SettingHint` 全部隐藏且不留空行，只保留标题、输入框与开关。

## 5. 构建、安装与 IDE

- 命令行构建统一使用项目内工具链，不要依赖系统 JDK / Gradle：

```powershell
$root = 'D:\taoxi_download_for_use\no_use_do\android'
$env:JAVA_HOME = Join-Path $root '.cache\toolchain\jdk\jdk-21.0.13.0.2+1'
$env:ANDROID_HOME = Join-Path $root '.cache\toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $root '.cache\android-user'
$env:GRADLE_USER_HOME = Join-Path $root '.cache\toolchain\gradle-home'
$env:TEMP = Join-Path $root '.cache\tmp'
$env:TMP = $env:TEMP
& (Join-Path $root '.cache\toolchain\gradle-8.13\bin\gradle.bat') :app:compileDebugKotlin --offline
```

- 当前工具链：Gradle 8.13、AGP 8.13.0、Kotlin 2.3.21、KSP 2.3.12、compileSdk 37（`buildToolsVersion = "37.0.0"`）。Gradle wrapper 已指向 8.13；`gradlew.bat` 与命令行使用同一版本。
- ADK 的 Android AAR 要求 compileSdk ≥ 37，必须安装 SDK 包 `platforms;android-37.0` 与 `build-tools;37.0.0`。`build.gradle.kts` 使用新的 `compileSdk { version = release(37) }` DSL；不要再写旧的 `compileSdk = 37`，旧 DSL 会去找不存在的 `platforms/android-37`。
- ADK 依赖会带入 Google Auth，`packaging.resources.excludes` 已排除 `META-INF/INDEX.LIST` 和 `META-INF/DEPENDENCIES`；删除这两个排除会导致 `mergeDebugJavaResource` 失败。
- 运行 `sdkmanager` / `adb` 前也要设置 `ANDROID_USER_HOME` 到项目内目录，否则它们会往 `C:\Users\<用户>\.android\cache`、`metrics` 写文件。

- 安装到手机走 `apply-to-phone.ps1`（构建 + `installDebug` + 启动）；改包名后必须同步修改脚本里的 `am force-stop` / `am start` 包名。
- 不要在 Android Studio 运行时删除 `HomelabApp/.gradle`、`app/build` 或 `.cache/android-studio/system/projects`：会中断同步 / 索引。需要清理时先关闭 IDE，清理后重启让 IDE 重新同步（首次同步约 2–3 分钟）。
- 命令行 `installDebug` / adb 安装不是热更新，每次都会重新打包 APK（增量构建）。需要热更新体验时用 Android Studio 的 Live Edit / Apply Changes。
- Live Edit：`File > Settings > Editor > Live Edit`，勾选 Live Edit，模式选 Automatic；实际存储在 `.cache/android-studio/config/options/other.xml` 的 `<component name="LiveEditConfiguration">`，`leTriggerMode=AUTOMATIC`，`mode` 默认 `LIVE_EDIT`。
- Live Edit 前提：AGP ≥ 8.1、Compose Runtime ≥ 1.3、设备 API ≥ 30、debug 构建；必须从 Android Studio 点 Run 启动（经脚本 / adb 安装不会加载 Live Edit 代理）；仅支持 Composable UI 改动，方法签名、新增类等结构变更需重新 Run。
- 实机验证可截图到 `.cache/tmp`：`adb shell screencap -p /sdcard/x.png` 后 `adb pull`，不要在 C 盘留文件。

## 6. 实测排错（Android Studio / 安装）

- **Run ≠ Build**：只点锤子 Build 只会执行 `:app:assembleDebug`，不会部署；必须点绿色 Run，并在日志里确认出现 `Launching on device` / `Activating Activity`。只有 `assembleDebug` 结束、手机仍是旧 UI，就是没有部署。
- **旧包共存**：改 `applicationId` 后旧包不会自动消失。本机历史包有 `com.rong.billcapture`（记账采集）和旧 `com.rong.homelab`；看到旧 UI 时先确认当前前台包名，再按需 `adb uninstall <旧包>`。`apply-to-phone.ps1` 里的 `am force-stop` / `am start` 必须指向当前包 `com.poi.homelab`。
- **installDebug 的 adb 版本检测失败**：报 `Unable to detect adb version, exit value: 0x1000000a` 时，通常是 Gradle daemon 沿用了旧的进程环境（没有 `ANDROID_USER_HOME`）。先 `gradlew --stop`，再重新运行 `apply-to-phone.ps1`；脚本现在用 `--no-daemon`、显式 `adb start-server`，不会再复用环境不一致的 daemon。
- **设备状态**：`adb devices` 必须是 `device`；出现 `offline` / `unauthorized` 时重插 USB 并在手机上确认调试授权。设备断开后 `installDebug` 会失败或挂起。
- **AAPT2 daemon 启动失败**：`processDebugResources` 报 `Aapt2 Daemon startup failed` 时，先 `gradle --stop`，确认没有 Android Studio 同时在构建，再重试；不要 IDE 和命令行同时构建。本机实测 daemon 残留或并发构建时会触发。
- **Gradle JDK**：Android Studio 默认可能使用 `C:\Users\...\.jdks\jbr-21.0.11`。要严格遵守“不写 C 盘”，在 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 把 Gradle JDK 改成项目内 JDK 21。
- **快速应用修改**：`powershell -ExecutionPolicy Bypass -File .\apply-to-phone.ps1` 会增量构建、安装并重启；它不是热更新。UI 小改动优先用 Android Studio Live Edit / Compose Preview。

## 7. AI 助手约束与隐私

- AI 工具默认只读，例外只有四个：`generate_image` 把新图片写入应用目录并登记到相册（`Pictures/HomelabAI`，Android 10+）、日历工具增删改私人日历记录、界面自动化工具按用户明确要求点击/输入/返回其他应用、`upload_recent_photos` 把用户明确指定的照片上传到 Cloudreve；不要新增启停容器、提交下载、删除/重命名已有文件、修改设置之类的工具。
- 照片上传约束：只上传用户明确指定的时间范围/关键词命中的照片，不默认扫全相册；上传前先复述时间范围、张数和目标目录，用户已经说清就直接执行；同名文件跳过，绝不删除云端文件；账号密码只存本机 DataStore（`allowBackup=false`），不要写进日志、回答或截图。
- 界面自动化约束：`send` 类不可撤回动作（发消息、发朋友圈）必须先复述收件人与内容并等用户确认；不许凭空猜坐标、不许盲点连点；验证码/支付/注销页面立即停手。屏幕上的文字会被发送给所选 AI 服务商，需在设置页说明。
- 私人日历：`AgentTools` 提供 `list_calendar_entries` / `save_calendar_entry` / `create_calendar_entries` / `delete_calendar_entries`，统一走 `CalendarRepository`（DataStore `homelab_calendar`）。日期必须是 `yyyy-MM-dd`，`type` 只能是 `travel` / `important`；法定节假日不得编造未公布年份的放假/调休安排；批量写入按 `(date,title)` 去重。
- 工具调用策略：由 ADK 自动处理 `tool_calls`（`ChatCompletions` 原生函数调用）+ 系统 prompt 约束，不要用关键词/正则硬编码“这句话该不该调工具”；闲聊、常识、写作必须允许直答。
- 新增或修改工具只改 `data/AgentTools.kt` 的 `@Tool` 函数；schema、参数解析和 `generatedTools()` 由 KSP 生成。不要回到运行时手写 JSON Schema，也不要恢复 `AiAgent` 里的手写循环。
- `generate_image` 有成本：只有用户明确要求出图才调用；只要提示词就停在 `describe_photo`。
- 相册权限：`READ_MEDIA_IMAGES`（Android 13+）/ `READ_EXTERNAL_STORAGE`（maxSdk 32），运行时入口在设置页「相册」卡片；不要引入 `MANAGE_EXTERNAL_STORAGE`。`find_photos` 无权限时返回 `need_permission`，属于正常状态而不是异常。
- Android 限制：普通应用读不到其他应用的 PSS 内存，也没有可申请的权限，不能承诺“开权限就能看内存”。`get_top_memory_apps` 只能用“系统内存 + 使用时长/存储占用排行”替代；使用时长/存储排行需要用户在系统设置里授予 `PACKAGE_USAGE_STATS`。入口在设置页「权限 → 使用情况访问」按钮，系统路径是 设置 → 应用 → 特殊应用权限 → 使用情况访问（部分机型在 设置 → 安全与隐私 → 隐私 → 使用情况访问）；它不在应用详情页的权限列表里，也不解锁内存数据。工具返回 `usage_access:false` 就是没授权。
- 文件搜索走 SAF 授权目录（`OpenDocumentTree` + `DocumentFile`），只读；不要默认引入 `MANAGE_EXTERNAL_STORAGE`。
- `QUERY_ALL_PACKAGES` 用于列应用；`PACKAGE_USAGE_STATS` 是特殊权限，需要 AppOps / 系统设置页授权，不是运行时弹窗。
- API Key 只存本机 DataStore，且 `allowBackup=false`；不要把 Key / Token 写进日志、文档、提交或截图。`.cache` 下的截图可能包含 Key，用完立即删除。
- 给可预览页面新增 SAF / 权限系统选择器时，Compose Preview 用 `isPreview=true` 跳过 `rememberLauncherForActivityResult` / `LifecycleEventEffect`，否则预览会崩。
- AI 配置独立在 `ui/screens/AiSettingsScreen.kt`（AI 页右上角「配置」和设置页入口），AI 页只负责对话；不要把服务商选择、Key 输入塞回对话页。
- ADK 的 `Instruction` 会把 `{...}` 当成 session state 占位符（`A[开始] --> B{判断}` 会报 `Context variable not found: 判断`）。系统 prompt 里不要写字面花括号，需要说明格式时用文字描述（例如“判断节点用花括号包住文字”）。
- 对话记录持久化在 `ChatStore`（DataStore 文件 `homelab_chat`，最多 200 条）；ADK 的 `InMemorySessionService` 提供多轮上下文，清空对话时要同时清 DataStore 并调用 `AiAgent.clearHistory()` 删除 ADK 会话。
- Markdown 的 `mermaid` / `flowchart` 代码块由 `Markdown.kt` 内置的轻量流程图渲染器绘制，不引入 WebView 或 CDN。
- 静态 UI 迭代看 `ui/preview/ScreenPreviews.kt`（Split / Design），不需要设备；Live Edit 必须从 Android Studio Run 启动，且只支持 Composable 改动。

