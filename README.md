# poi's home · HomeLab Android

自用的家庭实验室 Android 客户端：把 Homarr 看板、账本、日历、待办、文件/照片、容器运维和一个会调用工具的 AI 助手「小砂糖」放进一个 App。

Kotlin + Jetpack Compose 单模块工程，包名 `com.poi.homelab`，应用名 `poi's home`。

## 功能

- **首页**：Homarr 看板网格（原生渲染 app / clock / iframe 磁贴，其余降级为「在 Homarr 中查看」）、Lab Glance（本月收支 + 容器状态）、今日清单、最近流水。
- **账本**：月度概览、分类/商户统计、流水筛选；支持多选截图或 CSV/XLSX 导入，先由 bill-ai 服务识图提取，再在手机端核对、编辑并整批提交。
- **日历**：旅行 / 重要事项的月视图，可增删改；同时作为 AI 的私人日历工具后端。
- **小砂糖（AI）**：基于 Google ADK for Kotlin，支持 DeepSeek / OpenAI / Moonshot / 通义千问 / 硅基流动 / Ollama / 自定义 OpenAI 兼容地址；支持对话持久化、识图、生图、追问建议，以及工具调用（见下）。
- **服务与下载**：通过 MCP 调用 Lab Tools，查看健康快照、容器列表、启停/重启、日志与磁盘占用；提交下载任务并查看结果。
- **记账采集**：通知监听 + 银行短信解析，写入本地 Outbox 后重试上报到账本服务。
- **照片**：相册搜索/识别/生图结果展示；最近照片上传到 Cloudreve（WebDAV），可用 JobScheduler 每天自动同步最近 3 天。
- **设置**：连接（Lab Tools / 账本 / Homarr / Cloudreve）、小砂糖配置、权限入口（无障碍自动化 / 使用情况访问 / 相册）、主题与全局壁纸、详细/简略模式。

### AI 工具集

`@Tool` 函数由 KSP 在编译期生成 schema，覆盖：系统与存储信息、应用列表、使用时长/存储排行、SAF 目录文件搜索、Homelab 健康与账本流水、照片查找/识别/生图/上传、私人日历读写，以及无障碍界面自动化（读当前窗口、点击、输入、滚动、等待）。

写操作只有四类：把生成的图片存进相册、增删改私人日历、按明确要求操作其他应用界面、把指定的照片上传到 Cloudreve；其余查询默认只读。

## 技术栈

| 项 | 版本 / 说明 |
| --- | --- |
| 语言 / UI | Kotlin、Jetpack Compose（Material 3）、Coroutines |
| 网络 / 序列化 | OkHttp、kotlinx-serialization |
| 本地存储 | DataStore Preferences、SharedPreferences（采集与跳转守卫） |
| AI | `com.google.adk:google-adk-kotlin-core-android`、`integrations-openai` 1.4.0，KSP 生成工具 schema |
| 图片 | Coil（含 SVG） |
| SDK | minSdk 26、targetSdk 35、compileSdk 37（buildTools 37.0.0）、JDK 17 |
| 构建 | Gradle 8.13、AGP 8.13.0、Kotlin 2.3.21、KSP 2.3.12 |

## 目录结构

```
HomelabApp/
└── app/src/main/java/com/poi/homelab/
    ├── HomelabApplication.kt / MainActivity.kt
    ├── core/           AppContainer、settings/（SettingsStore）、util/、ui/（theme、通用组件、应用内浏览器）
    ├── feature/
    │   ├── ai/         小砂糖：AiAgent、AiClient、AgentTools、DeviceTools、ChatStore、ui/
    │   ├── bills/      账本与账单导入
    │   ├── calendar/   私人日历
    │   ├── homarr/     Homarr 看板与磁贴
    │   ├── home/       首页、待办、每日清单
    │   ├── lab/        MCP 客户端与容器/下载
    │   ├── photos/     Cloudreve WebDAV 与照片自动同步
    │   ├── settings/   设置页
    │   ├── notify/     通知 / 短信采集
    │   └── automation/ 无障碍自动化与跳转守卫
    ├── navigation/     AppRoot（导航骨架）、MainViewModel（全局 UiState）
    └── preview/        Compose 预览
```

新增代码按「功能优先」放进对应 `feature/<功能>/`，界面放该功能下的 `ui/`。

## 构建与安装

前置：JDK 21、Android SDK（platform `android-37.0`、build-tools `37.0.0`）、已开启 USB 调试的设备。

1. 在 `HomelabApp/local.properties` 写入 `sdk.dir=<Android SDK 路径>`（该文件不入库）。
2. 构建：

   ```powershell
   cd HomelabApp
   .\gradlew.bat :app:assembleDebug
   ```

3. 一键构建、安装并启动（Windows）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\apply-to-phone.ps1
   ```

   脚本会依次调用 `gradlew installDebug`、`adb shell am force-stop com.poi.homelab`、`adb shell am start -n com.poi.homelab/.MainActivity`。

也可以用 Android Studio 打开 `HomelabApp/` 目录，把 Gradle JDK 指到 JDK 21 后直接 Run。

## 首次配置

所有地址与密钥都不写死在代码里，需要在设置页填写，只保存在本机 DataStore：

- **连接**：Lab Tools（MCP 地址）、账本服务地址与 Token、Homarr 地址 / API Key / 看板名、Cloudreve WebDAV 地址 / 账号 / 密码 / 目录。
- **小砂糖**：选择服务商并填入 API Key；识图模型、生图模型可单独配置。
- **权限**：界面自动化需在 系统设置 → 无障碍 手动开启；使用时长排行需授予「使用情况访问」；照片上传需相册权限。
- **外观**：主题、全局壁纸、是否在首页显示账本区。

可选：在 `local.properties` 加 `homarr.url=https://<你的 Homarr 地址>`，构建期会注入为 Homarr 默认值（未手动保存过时预填）。仓库本身不包含任何实例地址。

## 依赖的后端

| 服务 | 用途 | 接口 |
| --- | --- | --- |
| Lab Tools（MCP） | 容器与下载运维 | `health_snapshot`、`list_services`、`start_service`、`stop_service`、`restart_service`、`service_logs`、`disk_usage`、`download_media` |
| bill-ai | 账本与账单导入 | `/api/overview`、`/api/transactions`、`/api/meta`、`/api/extract`、`/api/commit`、`/api/ingest` |
| Homarr | 看板与磁贴 | `/api/boards`、`/api/apps`、tRPC `board.getBoardByName` |
| Cloudreve | 照片备份 | WebDAV（PROPFIND / MKCOL / HEAD / PUT） |

## 隐私

- `allowBackup=false`，API Key、账本 Token、WebDAV 密码只存本机 DataStore。
- 通知监听、短信接收、无障碍、使用情况访问都需要用户手动授权，可随时关闭。
- AI 对话会把你的提问、选中的照片、以及界面自动化时的屏幕文本发送给你所配置的 AI 服务商。
- 仓库不含真实实例地址、内网 IP 与任何密钥；`local.properties`、`.cache/`、keystore、APK 均被 `.gitignore` 排除。
