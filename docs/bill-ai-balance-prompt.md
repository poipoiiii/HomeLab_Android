# bill-ai 服务端改造提示词：账户余额新鲜度与候选余额核对

> 用途：把本文件整段交给处理 bill-ai 服务端仓库的 AI / 开发同学，让它按下面的需求完成改造。
> 背景：Android 端（HomelabApp）已把通知与短信原文上报到 `POST /api/ingest`，账本页/首页只消费 `/api/overview`。原计划只改服务端，实际已同时落地客户端核对界面，见下方「实施结果」。

---

## 实施结果（2026-10-29）

服务端与客户端均已按本文件落地，接口文档在 `homelab/bill-ai/API.md`。

| 需求 | 状态 | 落地位置 |
| --- | --- | --- |
| 需求一 余额新鲜度 | 已完成 | `homelab/bill-ai/app/store.py`（`accounts.balance_updated_at` / `balance_source`、`account_balance_snapshots` 表） |
| 需求二 候选余额识别 | 已完成 | `homelab/bill-ai/app/balance.py`（规则）+ `app/main.py`（`/api/ingest` 内先落候选） |
| 需求三 核对接口 | 已完成 | `GET /api/balances/candidates`、`POST .../confirm`、`POST .../reject`；`/api/overview` 新增 `pending_balance_candidates` |
| 需求四 截图 OCR 余额页 | 未实现（可选项） | 需要改动 `/api/extract` 的提示词与输出，留作后续单独处理 |
| 需求五 定时任务与风控 | 已完成 | 启动后台任务每天把超过 7 天的 `pending` 置 `rejected`；日志截断并脱敏 |

客户端（`homelab_android/HomelabApp`）改动：

- `BillModels.kt` / `BillRepository.kt`：新增余额候选模型与三个接口；
- `MainViewModel.kt`：刷新账本时同步拉候选，确认/驳回后刷新余额；
- `BillsScreen.kt`：账单页「余额待确认」卡片（余额、偏差、异常标签、原文摘录、确认/驳回），未匹配账户时先弹账户选择；账户卡片显示 `balance_updated_at` / `balance_source`。

实现细节与原始需求的差异：

1. 确认后回写余额沿用现有「调整期初余额」的 `reconcile_account`，金额仍以整数分存储；`balance_updated_at` 取短信的 `observed_at`。
2. 同一账户一次确认多条时只让 `observed_at` 最新的一条决定余额；迟到的旧候选不会覆盖更新的余额。
3. `account_cards` 记录「发送方 + 尾号 → 账户」映射，确认后同尾号短信自动匹配。
4. `raw_excerpt` 最多 80 字，8 位以上连续数字只保留后 4 位；`/api/ingest` 的余额识别异常只写日志，不影响 2xx。
5. 单元测试在 `homelab/bill-ai/tests/test_balance.py`，覆盖金额解析（含千分位）、尾号提取、去重、确认回写、驳回与超期清理。

---

## 你的任务

在 bill-ai 服务端仓库内实现「账户余额的最后更新时间」与「短信/通知候选余额 → 人工核对 → 落库」两条链路。要求严格复用现有代码风格、目录结构、鉴权方式与数据库访问方式，不要引入新框架。

## 现状（客户端已确定的事实，不要改动）

1. `POST /api/ingest` 已存在，请求头 `X-Ingest-Token`，body 为单条 JSON：

```json
{
  "source": "sms",
  "sender": "95588",
  "text": "您尾号1234的储蓄卡10月29日21:10余额人民币12,345.67元",
  "ts": 1761743400,
  "device": "Xiaomi 14"
}
```

   - `source` 取值：`sms` 或通知来源（客户端目前传字符串，按现有实现处理）。
   - `ts` 是 Unix 秒。
   - 客户端会本地排队、失败重发，所以同一条原文可能重复出现。

2. `GET /api/overview?month=yyyy-MM` 已存在，请求头 `Authorization: Bearer <token>`，返回结构里包含：

```json
{
  "month": "2026-10",
  "accounts": [{ "id": 1, "name": "招行储蓄卡", "balance": "12345.67" }],
  "net_worth": "23456.78",
  "month_stats": {},
  "by_category": [],
  "top_merchants": [],
  "recent": []
}
```

3. 客户端 JSON 解析开启 `ignoreUnknownKeys = true`，服务端新增字段不会导致旧版 App 报错；但**不要删除或重命名**现有字段。

## 需求一：余额新鲜度

1. `accounts[]` 每项新增两个字段：

```json
{
  "id": 1,
  "name": "招行储蓄卡",
  "balance": "12345.67",
  "balance_updated_at": "2026-10-29T21:10:00+08:00",
  "balance_source": "sms"
}
```

   - `balance_updated_at`：余额最后一次被确认的时间，ISO8601 带时区；从未确认过则为 `null`。
   - `balance_source`：`manual` / `sms` / `notification` / `ocr` 之一；未知为 `null`。

2. 新增表 `account_balance_snapshots`（字段名可按现有命名习惯调整，语义不变）：

```text
id
account_id        -- 可为空：短信只识别到尾号时先为空
card_tail         -- 卡号后 4 位，仅此 4 位
balance           -- 数值，不要用浮点，按现有金额字段的做法（Decimal / 分）
currency          -- 默认 CNY
source            -- manual / sms / notification / ocr
observed_at       -- 余额对应的时间（短信时间）
status            -- pending / confirmed / rejected
raw_text          -- 原文，列表接口里截断展示
raw_hash          -- 原文哈希，用于幂等
dedup_key         -- 见需求二
created_at
decided_at
```

3. `accounts.balance` 只由「最近一条 `confirmed` 快照」或手工编辑回写；`pending` / `rejected` 快照一律不影响余额。
4. 回写余额时同步更新该账户的 `balance_updated_at` 与 `balance_source`。

## 需求二：候选余额识别

1. 在 `/api/ingest` 的成功流程里增加一步余额识别（可异步，不要拉长接口响应，也不要影响现有记账解析）。
2. 识别规则：
   - 命中关键词组合：`余额` / `可用余额` / `账户余额` / `剩余`，且带金额（支持千分位、`元`、`¥`）。
   - 卡标识：`尾号1234`、`尾号 1234`、`****1234` 等，统一取后 4 位。
   - 来源过滤与模板库按发送号码 / 包名维护，规则集中放在一个可配置的模块里，不要把正则散落在 handler 中。
3. 账户匹配：
   - 先按 `sender` + `card_tail` 匹配已有账户的映射（可新增 `account_cards` 表或在账户表加尾号字段，按现有设计选一种）。
   - 匹配不到账户时仍写 `pending` 快照，只存 `card_tail`，等人工指定。
4. 幂等与去重：`dedup_key` 建议用 `(card_tail 或 account_id, balance, observed_at 取 5 分钟窗口)`；入库前先查 `raw_hash` / `dedup_key`，重复直接返回成功，不新增记录。客户端重发必须安全。
5. 同一账户同一条信息不要产生多条候选；金额相同的重复短信合并。
6. 候选一律 `pending`，绝不直接改 `accounts.balance`。

## 需求三：核对接口

1. 列表：

```http
GET /api/balances/candidates?status=pending&limit=50
Authorization: Bearer <token>
```

返回：

```json
{
  "items": [
    {
      "id": 12,
      "account_id": 1,
      "account_name": "招行储蓄卡",
      "card_tail": "1234",
      "balance": "12345.67",
      "currency": "CNY",
      "source": "sms",
      "observed_at": "2026-10-29T21:10:00+08:00",
      "created_at": "2026-10-29T21:10:05+08:00",
      "raw_excerpt": "您尾号1234的储蓄卡…余额人民币12,345.67元",
      "current_balance": "12000.00",
      "delta": "345.67",
      "anomaly": false
    }
  ],
  "pending_count": 1
}
```

   - `raw_excerpt` 截断到 80 字，列表接口不得返回完整卡号（只有尾号）。
   - `delta` = 候选余额 - 当前余额；`anomaly` 在偏差超过 50% 时为 true（阈值可配置）。

2. 确认（支持批量）：

```http
POST /api/balances/candidates/confirm
{ "ids": [12, 13], "account_id": 1 }
```

   - `account_id` 可选：仅用于把「只有尾号」的候选指定到某个账户。
   - 成功后：写 `confirmed`、更新 `accounts.balance` / `balance_updated_at` / `balance_source`，返回每条的结果。

3. 驳回（支持批量）：

```http
POST /api/balances/candidates/reject
{ "ids": [12] }
```

4. 在 `GET /api/overview` 顶层增加 `pending_balance_candidates`（整数，默认 0），便于首页显示「N 条余额待确认」。

## 需求四：截图 OCR 路径（可选但推荐）

`POST /api/extract` 增加一种余额页输出（建议用 `kind: "balance"` 区分现有流水输出）：识别出的账户、余额、时间写成 `pending` 快照（`source = "ocr"`），走同一套核对接口，不直接落库、不写 `accounts.balance`。

## 需求五：定时任务与风控

1. 每天清理一次：`pending` 超过 7 天自动置 `rejected`，并记录原因。
2. 金额解析失败、尾号缺失的记录写入服务端日志即可，不要返回给客户端错误。
3. 不记录完整卡号、不记录身份证号；`raw_text` 只用于核对，日志中截断。

## 验收标准

1. 用下面的请求连续发两次，第二次不新增候选记录：

```bash
curl -X POST "$BASE/api/ingest" \
  -H "X-Ingest-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"source":"sms","sender":"95588","text":"您尾号1234的储蓄卡10月29日21:10余额人民币12,345.67元","ts":1761743400,"device":"test"}'
```

2. 候选列表能查到该记录，`balance` 正确、`card_tail` 为 `1234`、`status` 为 `pending`。
3. 调用 confirm 后 `/api/overview` 的对应账户 `balance` 变为 `12345.67`，`balance_updated_at` / `balance_source` 已更新。
4. 旧版客户端仅依赖 `id/name/balance` 时一切正常；新增字段均为可选。
5. 提供对应的单元测试：金额解析（含千分位）、尾号提取、去重、确认回写。
6. 在 README 或接口文档中补充上述字段与三个新接口。

## 不要做的事

1. 不要让 `/api/ingest` 因为余额识别失败而返回非 2xx。
2. 不要改动现有流水解析、`/api/extract` 现有输出、`/api/commit` 行为。
3. 不要用浮点数存金额。
4. 不要新增客户端接口以外的鉴权方式：`/api/ingest` 继续只认 `X-Ingest-Token`，其余接口继续用 Bearer。
5. 不要尝试登录银行/支付 App 或抓取页面。
