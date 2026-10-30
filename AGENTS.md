# AGENTS.md

本文件适用于 `d:\taoxi_download_for_use\no_use_do\android` 下的所有工作。

**使用方式**：先读本文件（硬性规则 + 速览 + 索引），再按任务查 §6 索引，只读需要的那份子文档，不要一次性全读。

## 1. 硬性规则（必须遵守）

### 1.1 禁止向 C 盘写入任何文件
- 所有新建、下载、解压、编译、缓存、日志、临时文件，一律只能落在本目录内。
- 禁止使用 `C:\Users\...\AppData\Local\Temp`、`%TEMP%`、用户主目录、桌面等位置作为工作区。工具默认写 C 盘时（`mktemp`、Python `tempfile`、pip/npm 缓存、反编译默认输出），必须显式改到本目录 `.cache`。
- 读取 C 盘已有文件允许；**写入**禁止。
- 完成后自查：本次是否在 C 盘产生文件或目录；如有，删除并迁移到 `.cache`。

### 1.2 缓存与中间产物统一放 `.cache/`
- 临时文件、解包内容、反编译结果、下载缓存、日志、脚本产物写入 `.cache/`，按任务建子目录（`.cache/apk_unzip/`、`.cache/decompile/`、`.cache/logs/`）。
- `.cache/` 视为可随时删除，不得作为最终交付物。
- 不要在项目根目录堆 `tmp/`、`out/`、`build/`、`分析结果/` 之类的散落目录。

### 1.3 超过 1MB 的文件放 `static/`
- 阈值 1 MB = 1,048,576 字节，单个文件 **大于等于** 该体积时放入 `static/`（APK、SO、DEX、JAR、图片、字体、压缩包、报告、模型、PDF）。
- 小于 1MB 的源码、配置、脚本、说明文档留在根目录或正常业务目录，不要为了"整齐"全塞进 `static/`。写入前确认实际大小。

### 1.4 禁止把产物、缓存、照片、XML 等文件放到安卓手机上
- 严禁通过 `adb push`、`adb shell` 重定向、`adb shell screencap` 落盘、应用内导出等方式，把任何产物、缓存、照片、XML（含截图、日志、配置、导出数据、临时脚本）写到手机存储（`/sdcard`、`/storage/emulated/0`、应用目录等）。
- 需要落地时改为电脑端处理：截图用 `adb exec-out screencap -p >` 直接落回 `.cache/tmp/`，其他文件一律电脑端生成、解析、比对，用完即删。
- 禁止把手机当临时工作区（`adb pull` 后留在手机、手机上生成 XML 再读回电脑）。
- 完成后自查：本次是否在手机存储产生文件；如有，`adb shell rm` 清除并把需要的内容迁回本目录。

## 2. 涉及页面设计时

只要任务涉及 UI / 页面 / 视觉设计（网页、报告页、可视化页、改版样式等），**必须先完整阅读** [SKILL.md](.trae/skills/frontend-design/SKILL.md)（Anthropic 官方 `frontend-design`，已下载到本目录以免写 C 盘）。先读再设计，不要凭经验套模板，遵守其中字体、配色、动效、排版约束。页面图片必须用真实可用的图片地址，禁止占位图或纯色块假装图片。

## 3. 常见错误（重点规避）

- **数据真实性**：分析 `base.apk.1` 时，包名、版本号、权限、签名、SDK 必须来自实际解析结果，禁止编造或凭经验猜测；结论要能对应证据（文件路径、偏移、命令输出），无法确认的写"未验证"；不要用示例数据冒充真实结果。
- **输入文件保护**：`base.apk.1` 只读——不要重命名、覆盖、截断、原地修改，也不要在旁边生成同名变体；要解压先复制到 `.cache/`。不要重复下载/重复解包，先看 `.cache/` 是否已有产物。
- **文件创建**：只创建任务确实需要的文件，不顺手生成 README / CHANGELOG / 说明文档 / 示例脚本；改已有文件优先于新建；不留下空目录、空文件、`*.bak`、`*_old.*`、`copy of ...`。
- **环境与命令**：Windows + PowerShell——顺序执行用 `;`，不用 `&&`、`||`；不用 `rm -rf`、`cp -r`、`sed`、`awk`、`cat`、`grep`、`find`；路径用绝对路径或本目录相对路径，注意反斜杠与含空格路径引号。不要留下长期运行的进程（服务器、监听器），用完停止并确认端口释放；不用交互式命令，改用非交互参数。
- **版本控制**：未经明确要求，不要 `git commit`、`git push`、`git reset --hard`、强制推送或删除分支；不要把 `.cache/`、`static/` 大文件、密钥、`*.keystore`、签名文件提交进仓库。
- **修改范围**：只改与任务直接相关的代码/文件，不顺手重构、不升级无关依赖、不调整无关格式；改前先读文件；不引入无用兼容层、开关、抽象或"以防万一"的兜底逻辑。
- **重命名 / 批量替换**：包名重命名必须覆盖源码目录、`package`/`import`、`namespace`、`applicationId`、`proguard-rules.pro`、安装/启动脚本、预览或常量里的旧标识符；只做标识符级替换，禁止无边界字面替换（把 `rong` 全量换成 `poi` 会破坏 `Strong`、`wrong`）；改 `applicationId` 等于换应用，必须卸载旧包，否则应用详情仍显示旧包名，安装脚本也可能拉起旧应用。
- **交付前自查**：文件位置符合 §1；无临时文件、调试输出、注释掉的死代码；无编造数据或未注明来源的结论；涉及页面设计已先读 `frontend-design` skill。

## 4. 项目速览（HomelabApp）

- 包名 / `namespace` / `applicationId`：`com.poi.homelab`，源码 `HomelabApp/app/src/main/java/com/poi/homelab`；应用名 `poi's home`。
- 源码按功能分包：`core/`（容器、设置、工具、通用 UI）、`feature/<功能>/`（`ai` `bills` `calendar` `homarr` `home` `lab` `photos` `settings` `notify` `automation` `files`）、`navigation/`、`preview/`；新增代码按功能放 `feature/<功能>/`，不要恢复平铺的 `data/`、`ui/screens/`。
- 不要写死真实实例地址 / tailnet 域名 / 内网 IP / Token / API Key（`allowBackup=false`，Key 只存本机 DataStore，不进日志、文档、提交或截图）。
- AI 走 Google ADK for Kotlin（`LlmAgent` + `InMemoryRunner`），工具用 KSP 生成的 `@Tool`，不要恢复手写 SSE / tool loop / JSON Schema。
- 壁纸由 `AppRoot.WallpaperBackdrop` 全局铺底，不要在单页里再画；导航是左侧抽屉，没有底部导航。
- 构建用项目内工具链（命令见 §5）；compileSdk 必须用 `compileSdk { version = release(37) }` 新 DSL。

## 5. 构建（完整说明见 build.md）

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

安装到手机走 `apply-to-phone.ps1`（构建 + `installDebug` + 启动，不是热更新）。构建报错、Live Edit、Run ≠ Build、adb 异常都先看 [build.md](docs/agents/build.md)。

## 6. 子文档索引（按任务只读对应文件）

| 任务涉及 | 必读 |
| --- | --- |
| UI / 导航 / 首页 / 壁纸 / 设置页 / Homarr / 应用内浏览器 / 预览 | [project.md](docs/agents/project.md) |
| AI / 对话 / 工具 / prompt / 模型 / Markdown / 日历 / 权限与隐私约束 | [ai.md](docs/agents/ai.md) |
| 照片 / Cloudreve 上传 / 自动同步 | [photos.md](docs/agents/photos.md) |
| 通知短信采集 / 账单导入 / 余额待确认 | [bills.md](docs/agents/bills.md) |
| 无障碍自动化 / 开屏跳过 / 跳转守卫 | [automation.md](docs/agents/automation.md) |
| 文件洞察 | [files.md](docs/agents/files.md) |
| 构建 / 安装 / Gradle / Android Studio / adb / Live Edit / 排错 | [build.md](docs/agents/build.md) |

## 7. 维护约定

改动某个功能后，同步更新对应子文档（不要往本文件里堆功能细节）。只有新增全局硬性规则、改变目录约定或新增子文档时，才修改本文件。
