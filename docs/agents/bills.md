# 记账采集与账单导入

改 `notify/`、账单导入、余额确认前先读本文件。

## 采集链路（`notify/`）

从 `homelab/bill-ai/android` 迁入。`NotificationCaptureService`（`BIND_NOTIFICATION_LISTENER_SERVICE`，需手动授予通知使用权）+ `SmsReceiver`（`RECEIVE_SMS`）→ `CapturedFilter` 本地预过滤 → `Outbox` 落队列 → `Uploader` 以 `POST {billUrl}/api/ingest`、请求头 `X-Ingest-Token` 上报；`CaptureFlushReceiver` 每 30 分钟补发一次，`CaptureBootReceiver` 在开机/覆盖安装后重排 alarm。

服务器地址与凭证不单独配置：`MainViewModel` 把 `AppSettings.billUrl` / `token` 镜像进 `NotifyPrefs`（SharedPreferences），因为服务与广播在回调里读不到 DataStore。设置页「权限 → 通知与短信采集」是唯一入口。

## 账单导入

`feature/bills/ui/BillImportScreen.kt` + `BillRepository.meta/extract/commit`：

- 账单页「导入」支持多选截图（单张 ≤ 8 MB）或 CSV/XLSX；选截图前可先指定「截图付款账户」，本次截图的 `items.account` 会统一换成这个中文账户名，CSV/XLSX 仍保留文件解析出的账户。
- `POST /api/extract`（multipart 字段 `file`，无鉴权，服务端识图最长 180s，客户端 `uploadClient` readTimeout 200s）→ 客户端按 `order_id` / 字段指纹去重并核对编辑（账户/分类候选来自 `GET /api/meta` 的中文名）→ `POST /api/commit` 整批写入，返回 committed/skipped/failed，失败项保留可再次提交。
- 截图批量卡显示原图缩略图，核对行/编辑页可打开全屏缩放查看；`BillImportItem.sourceUri` 是 `@Transient` 本机字段，不进入 commit 序列化。
- 提交后按成功项里每个账户的最大日期写入 `BillImportHistory`（SharedPreferences `bill_import_history`），导入页显示各账户「上次同步到」，下次截图从该日期之后开始。
- 提取后按待核对记录所在月份拉 `GET /api/transactions?month=`（limit 500）做云端比对：日期相同、金额相同、时间差 ≤ 2 小时会在记录上标「云端疑似已有」。
- `/api/extract` 与 `/api/commit` 不发送 `X-Ingest-Token`，只有 `/api/ingest` 用；预览不落库，核对状态只在客户端。

## 余额待确认

`feature/bills/ui/BillsScreen.kt` 的 `BalanceCandidatesCard`：`/api/ingest` 识别出的短信/通知余额以 `pending` 候选存在服务端，账单页「余额待确认」卡片可逐条确认或驳回；`accountId == null` 的候选先弹账户选择再确认。`BillAccount` 带 `balance_updated_at` / `balance_source`，`BillOverview` 带 `pending_balance_candidates`；接口见 `homelab/bill-ai/API.md`，客户端只在账单页刷新时拉 `GET /api/balances/candidates`。
