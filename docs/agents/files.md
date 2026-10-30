# 文件洞察

改 `feature/files/` 前先读本文件。

- `FileScanner` 用 `DocumentsContract` 按目录批量遍历 SAF 目录树（`AppSettings.fileTreeUris` 可添加多个文件夹，因为 Android 11+ 不允许直接授权内部存储根目录；最多 5 万文件 / 2 万目录 / 深度 32，跳过 `Android/data`、`Android/obb`、回收站）。
- `FileIndexStore` 把元数据以 gzip JSON 存 `filesDir/files/index.json.gz`。
- `FileInsights` 计算类型构成、占空间目录、重复文件、缓存/临时、超大旧文件、疑似敏感文件名；`FileInsightRepository` 负责扫描、加载、筛选和 AI 摘要。
- 页面 `feature/files/ui/FileInsightScreen.kt` 从首页右下角快捷面板或设置「扫描范围」进入，只读元数据，不删除、不移动、不上传。
- 一键分析走 `AiAgent.analyzeFileInsight`（直连 `ChatCompletions`，不进入聊天会话，摘要 ≤ 6000 字符）；对话工具是 `file_insight_summary` / `search_indexed_files`。
- 不新增权限，不使用 `MANAGE_EXTERNAL_STORAGE`，设计见 `docs/file-insight-design.md`。
- 只读约束：只读元数据（文件名、路径、大小、修改时间、类型），不打开文件、不上传内容、不删除/移动；`file_insight_summary` / `search_indexed_files` 只查本地索引。页面内 AI 分析只在用户点「开始分析」时发送统计摘要与文件名，摘要 ≤ 6000 字符。
