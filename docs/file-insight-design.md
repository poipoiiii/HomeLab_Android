# 文件洞察（File Insight）设计稿

> 状态：已确认，v1 已实现（P0 + AI 对话工具；单扫描范围、仅元数据）
> 目标设备：运行本 App 的这台手机（本机），不做跨设备远程扫描。

## 1. 一句话描述

用户一次性授权扫描范围后，点「开始扫描」把本机可访问文件的**元数据**建成本地索引；页面用概览、大文件、重复、类型、全部五个视角展示，并可一键让小砂糖基于统计摘要做清理与隐私分析。

## 2. 目标与非目标

### 目标

- 一键看到本机可访问文件的整体情况：数量、总大小、目录分布、类型构成。
- 快速定位空间大头：最大文件、最占空间的目录、重复文件、缓存/临时文件、长期未动的大文件。
- 接入 AI：把**压缩后的统计摘要**交给当前 AI 服务商，输出可执行的清理建议；AI 对话页也能查询这份索引。
- 索引落本机，重启后秒开；重新扫描前旧结果仍然可用。

### 非目标（v1 明确不做）

- 不读取、不解析文件内容（只读文件名、路径、大小、修改时间、MIME）。
- 不删除、不移动、不重命名、不上传任何文件。
- 不引入 `MANAGE_EXTERNAL_STORAGE`（项目硬性规则）。
- 不做 root / ADB / 服务端代理扫描，不读取其他应用私有目录。
- 不做定时后台全盘扫描（大范围扫描耗电、耗时，v1 只由用户手动触发）。

## 3. Android 能力边界（必须在 UI 里讲清楚）

普通应用在 Android 11+ 无法枚举整机所有文件。具体限制：

| 位置 | 是否可读 | 说明 |
| --- | --- | --- |
| 内部存储共享区（Download、DCIM、Documents、Pictures 等） | 可读 | 需要用户通过 SAF 授权目录树 |
| SD 卡共享区 | 可读 | 用户需在系统选择器里单独选 SD 卡根目录 |
| `Android/data`、`Android/obb` | 不可读 | Android 11+ 系统限制，SAF 也拿不到 |
| 其他应用私有目录 `/data/data/...` | 不可读 | 无权限，也没有可申请的权限 |
| `/system`、`/data` 等系统分区 | 不可读 | 非 root 不可见 |

因此产品文案统一写「**本机可访问文件**」，不要写「手机里的全部文件」。

实现路线：`ActivityResultContracts.OpenDocumentTree` + `takePersistableUriPermission(READ)`，用 `DocumentsContract` 查询目录（比 `DocumentFile.listFiles()` 快得多）。Android 11+ 的系统选择器通常不允许选中内部存储根目录（会提示「无法使用此文件夹」），因此改为**逐个添加文件夹**：DCIM、Download、Documents、Pictures 等，每个文件夹单独授权、合并扫描。`AppSettings.fileTreeUris` 保存全部授权；同一份列表同时服务 `find_files` 和文件洞察。

## 4. 用户流程

```text
首页右下角「快捷」──▶ 文件洞察
设置 → 权限 → 扫描范围 ──▶ 文件洞察
        │
        ▼
未授权：逐个添加文件夹（系统目录选择器，选 DCIM / Download / Documents 等）
        │
        ▼
开始扫描 ──▶ 进度：已扫描 12,340 个文件 · 当前 DCIM/Camera ──▶ 可取消
        │
        ▼
概览（总数 / 总大小 / 分类构成 / 空间大头 / 清理候选）
        │
        ├──▶ 大文件 / 重复文件 / 类型 / 全部（搜索、筛选、排序）
        └──▶ 让小砂糖分析（统计摘要 → Markdown 建议）
```

二次进入：有缓存索引时直接展示上次结果，顶部提示「上次扫描：3 小时前」，按钮变为「重新扫描」。

## 5. 功能清单

### P0（本次实现）

1. 扫描范围：支持添加多个文件夹（DCIM、Download、Documents 等），可单个移除或全部清除；同一个 URI 不会重复添加。
2. 扫描：进度、当前目录、取消、5 万文件上限、跳过受限目录、错误目录计数。
3. 本地索引：gzip JSON 落内部存储；重启后懒加载；扫描失败/取消不覆盖旧索引。
4. 概览：
   - 本机存储已用/总量、剩余空间；
   - 本次范围的文件数、目录数、总大小、扫描耗时、是否截断；
   - 类型构成条（图片 / 视频 / 音频 / 文档 / 压缩包 / 安装包 / 其他）；
   - 空间大头 Top 5（按文件）；
   - 清理候选：重复文件、缓存/临时、超大旧文件（可点击跳到对应列表）。
5. 文件列表：
   - 分类标签筛选；
   - 文件名关键词搜索；
   - 排序：大小降序 / 修改时间降序 / 名称升序；
   - 每次 200 条渐进加载。
6. 一键 AI 分析：摘要 ≤ 6000 字符，只发文件名与统计，不发文件内容；Markdown 结果展示；失败可重试。
7. 隐私提示：说明数据只留本机、分析时发送什么、不删除文件。

### P1（确认后再排）

- AI 工具：`file_insight_summary`、`search_indexed_files`，让对话页能直接问「我手机里什么最占空间」。
- 多扫描范围：内部存储 + SD 卡（索引记录 `rootName`）。
- 与上次扫描对比：新增/删除/增长 Top。

### P2（暂不做）

- 导出 CSV、打开文件、按内容分析、跨设备。

## 6. UI 设计

### 6.1 视觉原则

沿用现有设计系统，不引入新配色、新字体：

- 页面背景沿用全局壁纸 + 半透明 `CreamCard`、`DarkCard`；
- 核心数字放在一张深色卡（`DarkCard` + `DarkStat`），其余信息用奶油色卡片；
- **唯一的强视觉元素是类型构成横条**：用 `Box` 按比例分宽度，配 4–6 个分类色（复用 `Coral`、`Teal`、`Amber`、`Success` 等主题色），不引图表库；
- 文件类型图标不新增图片资源：用 36dp 圆角方块 + 扩展名文字（JPG / MP4 / PDF / ZIP / APK），颜色按分类区分；
- 动效克制：扫描进度条、结果列表淡入即可。

### 6.2 页面结构

```text
┌────────────────────────────────────────┐
│ ←  文件洞察                     扫描 ⟳  │
├────────────────────────────────────────┤
│ 扫描范围卡                              │
│  已添加 2 个文件夹                       │
│  DCIM                            移除    │
│  Download                        移除    │
│  上次扫描：今天 14:32 · 12,340 个文件    │
│  [添加文件夹]          [开始扫描]        │
├────────────────────────────────────────┤
│ （扫描中）已扫描 12,340 个文件…          │
│  ▓▓▓▓▓▓▓▓▓░░░░░░  取消                  │
├────────────────────────────────────────┤
│ 深色总览卡                              │
│  已用 128.4 GB / 256 GB    剩余 127.6 GB │
│  31,204 个文件 · 2,418 个目录 · 86 秒    │
├────────────────────────────────────────┤
│ 类型构成                                │
│  ████████░░░░░░░░░░░                    │
│  图片 42% · 视频 31% · 文档 12% …        │
├────────────────────────────────────────┤
│ 清理候选                                │
│  重复文件 1.8 GB · 23 组          查看 › │
│  缓存/临时 640 MB                 查看 › │
│  超大旧文件 4.2 GB                查看 › │
├────────────────────────────────────────┤
│ 空间大头                                │
│  1  video_2024.mp4      4.2 GB   DCIM/  │
│  2  backup.zip          3.8 GB   Download│
├────────────────────────────────────────┤
│ 让小砂糖分析          [开始分析]         │
│  只发送统计与文件名，不发文件内容        │
├────────────────────────────────────────┤
│ [概览] [大文件] [重复] [类型] [全部]     │
│  搜索框   [全部类型 ▾] [按大小 ▾]        │
│  ┌ 文件行（扩展名块 / 名称 / 路径 / 大小）│
│  │ …                                     │
│  └ 加载更多                             │
└────────────────────────────────────────┘
```

### 6.3 关键状态文案

| 状态 | 文案 / 行为 |
| --- | --- |
| 未授权 | 「还没有选择扫描范围。选『内部存储』可以覆盖整机可访问文件；Android/data 等系统目录无法读取。」按钮「选择扫描范围」 |
| 授权失效 | 「这个目录的授权已失效，请重新选择。」按钮「重新选择」 |
| 扫描中 | 「已扫描 N 个文件 · 当前：DCIM/Camera」，进度条 + 「取消」 |
| 扫描完成但有截断 | 「已达到 5 万个文件上限，结果不完整。可以缩小扫描范围或重新选择子目录。」 |
| 没有结果 | 「这个范围里没有找到文件。」 |
| 无索引时点分析 | 按钮禁用，提示「先扫描一次，再让小砂糖分析」 |
| AI 失败 | 「分析没有完成：<原因>」，按钮「重试」 |
| 未配置 AI | 「还没有可用的 AI 服务商，去设置 → 小砂糖 配置。」 |

### 6.4 入口

- 首页右下角快捷面板（现有 `QuickPanelFab`）新增一行「文件洞察」——一键进入。
- 设置 → 权限 → 「扫描范围」卡片（由现有「可搜索目录」改造）：显示当前范围 + 「打开文件洞察」按钮。
- 不新增底部导航 tab，避免主入口变拥挤；页面用全屏 overlay，和 `BillImportScreen` 一致。

## 7. 技术方案

### 7.1 新增 / 改动文件

新增 `feature/files/`：

| 文件 | 职责 |
| --- | --- |
| `FileModels.kt` | `FileEntry`、`FileCategory`、`FileIndexSnapshot`、`FileInsight`、`DuplicateGroup`、`FileSort`、`FileListFilter` |
| `FileScanner.kt` | SAF 目录树遍历：`DocumentsContract` 查询 + 进度 + 取消 + 上限 + 跳过规则 |
| `FileIndexStore.kt` | 索引读写：`filesDir/files/index.json.gz`，原子写入 |
| `FileInsightRepository.kt` | 对外的扫描/加载/查询/摘要入口，缓存在内存 |
| `FileInsights.kt` | 纯计算：分类构成、Top 目录、大文件、重复、缓存、旧大文件、敏感文件名 |
| `ui/FileInsightScreen.kt` | 页面与状态展示 |

改动：

| 文件 | 改动 |
| --- | --- |
| `core/AppContainer.kt` | 新增 `val files = FileInsightRepository(appContext)` |
| `navigation/MainViewModel.kt` | 新增 `FileUiState`、扫描/取消/筛选/分析动作；AI 工具构造；system prompt 增加文件规则 |
| `navigation/AppRoot.kt` | `fileInsightOpen` 全屏 overlay；传给 HomeScreen 打开回调 |
| `feature/home/ui/HomeScreen.kt` | 快捷面板新增「文件洞察」 |
| `feature/settings/ui/SettingsScreen.kt` | 「可搜索目录」卡片改为「扫描范围」+ 打开文件洞察 |
| `feature/ai/AgentTools.kt` | P1：`file_insight_summary`、`search_indexed_files` |
| `feature/ai/AiAgent.kt` | 新增 `analyzeFileInsight(provider, brief)`（直连 ChatCompletions，不进聊天会话） |
| `core/util/Format.kt` | `fileSize` 增加 GB/TB 分支（现在只到 MB） |
| `preview/ScreenPreviews.kt` | 用假数据预览页面（`isPreview` 跳过系统选择器） |

`AndroidManifest.xml` 不新增任何权限。

### 7.2 扫描实现

核心是不用 `DocumentFile.listFiles()`（每个文件一次 IPC，5 万文件会非常慢），而是：

```kotlin
val treeUri = Uri.parse(fileTreeUri)
val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

fun children(parentDocId: String): Uri =
    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

val projection = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)
```

遍历策略：

- `ArrayDeque` 深度优先，每个目录一次 `ContentResolver.query`，一次拿完子项；
- 记录相对路径 `parentPath + name`，只存相对路径，不存 URI；
- 上限：`MAX_FILES = 50_000`、`MAX_DIRS = 20_000`、`MAX_DEPTH = 32`；
- 跳过目录名：`Android/data`、`Android/obb`、`.thumbnails`、`cache`、`caches`、`tmp`、`temp`、`LOST.DIR`、`$RECYCLE.BIN`（扫描阶段只跳过其中的 `Android/data`、`Android/obb` 这类不可读项；`cache/tmp` 仍要扫，因为它们是清理候选）；
- 每个目录 `try/catch`，失败计入 `skippedDirs`，不中断整体；
- 每 200 个文件或每 150ms `yield()` + 上报进度；
- 协程 `ensureActive()` 支持取消；取消/异常时不写索引文件，旧索引保持可用。

### 7.3 数据模型

```kotlin
@Serializable
data class FileEntry(
    val path: String,       // 相对所属文件夹，如 "Camera/IMG_0001.jpg"
    val name: String,
    val size: Long,
    val modified: Long,
    val mime: String,
    val root: String,       // 所属授权文件夹名称，如 "DCIM"
)

@Serializable
data class FileIndexSnapshot(
    val version: Int = 2,
    val roots: List<String>,   // 本次扫描的文件夹名称
    val deviceName: String,
    val scannedAt: Long,
    val durationMs: Long,
    val totalFiles: Int,
    val totalDirs: Int,
    val totalBytes: Long,
    val truncated: Boolean,
    val skippedDirs: List<String>,
    val entries: List<FileEntry>,
)
```

分类不落盘，读时按 MIME + 扩展名推导，省索引体积：

```kotlin
enum class FileCategory { Image, Video, Audio, Document, Archive, Apk, Other }
```

### 7.4 索引持久化

- 路径：`context.filesDir/files/index.json.gz`（应用私有目录，无需权限，`allowBackup=false`）。
- 序列化：kotlinx.serialization JSON → `GZIPOutputStream`；先写 `.tmp`，成功后 `renameTo` 覆盖，保证原子性。
- 体积估算：5 万条 × 约 120 字节 ≈ 6 MB 原始 JSON，gzip 后约 1–2 MB。
- 加载：首次打开页面或 AI 工具调用时懒加载，内存里保留 `FileIndexSnapshot`；`rootUri` 与当前设置不一致时视为过期并提示重新扫描。
- 清除范围时同时删除索引文件（在现有「清除」动作里补一步）。

### 7.5 统计规则

| 指标 | 规则 |
| --- | --- |
| 总大小 | `entries.sumOf { size }`（目录大小不重复计入） |
| 类型构成 | 按分类聚合 count / bytes，构成条按 bytes 占比 |
| 空间大头 | 文件 size 降序 Top 50，页面展示 Top 5 |
| 占空间目录 | 相对路径第一段聚合 bytes 降序 Top 10 |
| 重复文件 | 按 `(name.lowercase(), size)` 分组，`size >= 1MB` 且 `count >= 2`；可回收 = `(count-1) * size`；最多展示 50 组 |
| 缓存/临时 | 路径或文件名包含 `.thumbnails`、`cache`、`caches`、`tmp`、`temp`、`log`，且 `size >= 1MB`；单独标注「按名称推测」 |
| 超大旧文件 | `size >= 100MB` 且 `modified` 早于 12 个月前 |
| 敏感文件名 | 名称包含 身份证 / 证件 / 护照 / 银行卡 / 密码 / 密钥 / 合同 / 简历 / 体检；**只做文件名提示，不读内容** |
| 存储空间 | `StatFs(Environment.getDataDirectory())` 取已用/总量/剩余 |

### 7.6 AI 分析

**一键分析（页面内）**

- 摘要构造（纯文本，≤ 6000 字符）：
  - 设备名、Android 版本、扫描范围与时间、是否截断；
  - 总量：文件数、目录数、总大小、剩余空间；
  - 类型构成表；
  - 最大 15 个文件（名称、大小、相对路径）；
  - 重复组 Top 10（文件名、张数、可回收）；
  - 缓存/临时 Top 10、超大旧文件数量与总大小；
  - 占空间目录 Top 10；
  - 敏感文件名命中数量与最多 10 个名称。
- 调用方式：`AiAgent.analyzeFileInsight(provider, brief)`，内部直接用 `ChatCompletions.generateContent(stream = false)`，超时 120s，temperature 0.3；**不写入聊天会话**，不调用工具。
- 输出约束：结论 → 空间去向 → 可清理（按收益排序，标注风险）→ 别动/谨慎 → 隐私提醒；不允许编造摘要里没有的文件名；截断时必须说明结果不完整。
- 展示：底部弹层（约 85% 高），复用现有 `MarkdownText` 渲染；支持「重新分析」「复制」；失败展示原因并保留重试。
- 明确征得同意：按钮下小字「只会把统计和文件名发送给 <服务商名>，不会发送文件内容」。

**对话工具（P1）**

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `file_insight_summary` | 无 | 缓存索引的概览、类型构成、Top 10 大文件、重复组、缓存/旧文件统计、扫描时间；无索引时返回提示「去首页快捷面板 → 文件洞察扫描一次」 |
| `search_indexed_files` | `query`、`ext`、`category`、`min_size_mb`、`sort`、`limit`（默认 20，最大 50） | 命中的文件名、路径、大小、修改时间；只查内存索引，不触发磁盘扫描 |

不提供「对话里触发全盘扫描」的工具：扫描可能几十秒，放在工具调用里会卡住对话。用户问文件问题时，没索引就引导去页面扫描。

System prompt 增加一段：

```text
文件与存储：
- 用户问“手机里什么最占空间/有没有重复文件/大文件/缓存”时，先调用 file_insight_summary。
- 没有索引时说明需要先打开 首页快捷面板 → 文件洞察 扫描一次，不要假装已经扫描。
- 需要具体文件时用 search_indexed_files；不要用 find_files 做全盘遍历。
- 只能读取元数据，不能删除或移动文件；建议里要区分“可以删”与“先确认”。
```

### 7.7 ViewModel 状态

```kotlin
data class FileUiState(
    val indexMeta: FileIndexSnapshot? = null,   // 只保留元信息，条目在仓库里
    val insight: FileInsight? = null,
    val scanning: Boolean = false,
    val scannedFiles: Int = 0,
    val currentDir: String = "",
    val scanError: String? = null,
    val query: String = "",
    val category: FileCategory? = null,
    val sort: FileSort = FileSort.SizeDesc,
    val rows: List<FileEntry> = emptyList(),
    val totalMatches: Int = 0,
    val shown: Int = 0,
    val analysis: String? = null,
    val analysisLoading: Boolean = false,
    val analysisError: String? = null,
)
```

- `FileInsightRepository` 持有完整 `entries`，ViewModel 只把当前筛选后的前 200 条放进 state，避免每次 `copy` 复制 5 万条。
- 扫描 Job 可取消；页面关闭不取消（用户可能误触返回），但扫描结束才写索引。
- 筛选/搜索在 IO 线程计算，结果回主线程更新。

### 7.8 性能与边界

- 5 万文件、2 万目录的扫描在内部存储上预计几十秒；进度条 + 当前目录保证“活着”的感觉。
- 单个目录几万文件时一次 query 可能占用较多内存；按 cursor 顺序读，不额外复制。
- `query` 返回 `null` 或抛 `SecurityException`：该目录记为跳过，继续扫。
- 授权被系统回收：加载索引不受影响（索引已落盘），重新扫描前提示重新授权。
- 扫描过程中 App 被杀：`.tmp` 文件残留，下次启动清理；旧索引不受影响。
- 不做后台定时扫描，避免耗电；P1 如需可评估前台服务。

## 8. 隐私与安全

- 只读元数据，不打开文件流，不做 OCR / 文本提取。
- 索引只存应用私有目录，不进入备份（`allowBackup=false` 已配置），不写日志。
- AI 分析是显式点击触发；发送内容为统计摘要 + 文件名/路径，不含文件内容；按钮下明确写出服务商名称。
- 不新增任何权限，不使用 `MANAGE_EXTERNAL_STORAGE`。
- 不做删除/移动/重命名，AI 建议只是建议，由用户自己处理。

## 9. 验收标准

1. 选择「内部存储」后能扫描到 DCIM、Download、Documents 等目录；`Android/data` 不出现在结果里且不报错。
2. 1 万个以上文件时页面不 ANR，进度持续更新，取消后旧索引仍可打开。
3. 杀掉进程重进，索引秒开，显示上次扫描时间与截断状态。
4. 大文件 / 重复 / 缓存 / 旧文件筛选结果与规则一致；搜索和排序正确。
5. 一键分析能在 120 秒内返回并渲染 Markdown；未配置 AI、失败、超时都有明确提示和重试。
6. `AndroidManifest.xml` 无新增权限；构建通过；实机截图验证。
7. 不向 C 盘写任何文件；索引位于应用私有目录。

## 10. 已确认的默认值

1. **扫描范围**：多文件夹，逐个添加 DCIM、Download、Documents 等；Android 11+ 无法直接授权内部存储根目录。
2. **入口**：首页右下角快捷面板 + 设置 → 权限 → 扫描范围；不新增底部导航 tab。
3. **AI 深度**：页面内一键分析 + AI 对话工具（`file_insight_summary` / `search_indexed_files`）。
4. **内容分析**：只分析元数据，不读取文件内容；文本/图片内容分析留到后续版本。
