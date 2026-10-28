package com.poi.homelab.feature.bills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

/* -------------------------------- bill-ai -------------------------------- */

@Serializable
data class BillAccount(
    val id: Int = 0,
    val name: String = "",
    val balance: String = "0.00",
)

@Serializable
data class MonthStats(
    @SerialName("out") val expense: String = "0.00",
    @SerialName("in") val income: String = "0.00",
    val net: String = "0.00",
    @SerialName("count_out") val countOut: Int = 0,
    @SerialName("daily_avg") val dailyAvg: String = "0.00",
    @SerialName("active_days") val activeDays: Int = 0,
)

@Serializable
data class CategoryAmount(
    val name: String = "",
    val amount: String = "0.00",
    val count: Int = 0,
)

@Serializable
data class MerchantAmount(
    val name: String = "",
    val amount: String = "0.00",
    val count: Int = 0,
)

@Serializable
data class BillTransaction(
    val id: Long = 0,
    val date: String = "",
    val time: String = "",
    val type: String = "",
    val amount: String = "0.00",
    @SerialName("amount_cents") val amountCents: Long = 0,
    val account: String = "",
    @SerialName("to_account") val toAccount: String = "",
    val category: String = "",
    val merchant: String = "",
    val description: String = "",
    val note: String = "",
    @SerialName("external_id") val externalId: String? = null,
)

@Serializable
data class BillSums(
    @SerialName("out") val expense: String = "0.00",
    @SerialName("in") val income: String = "0.00",
    val count: Int = 0,
)

@Serializable
data class TransactionPage(
    val items: List<BillTransaction> = emptyList(),
    val total: Int = 0,
    val sums: BillSums = BillSums(),
)

@Serializable
data class BillOverview(
    val month: String = "",
    val accounts: List<BillAccount> = emptyList(),
    @SerialName("net_worth") val netWorth: String = "0.00",
    @SerialName("month_stats") val monthStats: MonthStats = MonthStats(),
    @SerialName("by_category") val byCategory: List<CategoryAmount> = emptyList(),
    @SerialName("top_merchants") val topMerchants: List<MerchantAmount> = emptyList(),
    val recent: List<BillTransaction> = emptyList(),
)

@Serializable
data class Insight(
    val title: String = "",
    val detail: String = "",
)

@Serializable
data class BillAnalysis(
    val summary: String = "",
    val highlights: List<Insight> = emptyList(),
    val risks: List<Insight> = emptyList(),
    val suggestions: List<Insight> = emptyList(),
    @SerialName("category_note") val categoryNote: String = "",
)

@Serializable
data class AnalysisResponse(
    val range: JsonObject? = null,
    val stats: JsonObject? = null,
    val analysis: BillAnalysis = BillAnalysis(),
    val cached: Boolean = false,
)

/* ---------------------------- bill import ---------------------------- */

@Serializable
data class BillMetaOption(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class BillMeta(
    val assets: List<BillMetaOption> = emptyList(),
    val expenses: List<BillMetaOption> = emptyList(),
    val revenues: List<BillMetaOption> = emptyList(),
    val categories: List<BillMetaOption> = emptyList(),
)

/** `/api/extract` 与 `/api/commit` 共用的 11 字段记录。 */
@Serializable
data class BillImportItem(
    val type: String = "withdrawal",
    val date: String = "",
    val time: String = "",
    val amount: String = "0.00",
    val merchant: String = "",
    val description: String = "",
    val account: String = "",
    @SerialName("to_account") val toAccount: String = "",
    val category: String = "",
    val confidence: Double = 1.0,
    @SerialName("order_id") val orderId: String = "",
    /** 只在本机核对页使用，不参与 /api/commit 序列化。 */
    @Transient val sourceUri: String? = null,
)

@Serializable
data class BillImportStats(
    val rows: Int = 0,
    val included: Int = 0,
    @SerialName("skipped_status") val skippedStatus: Int = 0,
    @SerialName("skipped_internal") val skippedInternal: Int = 0,
    @SerialName("skipped_other") val skippedOther: Int = 0,
    @SerialName("skipped_duplicate") val skippedDuplicate: Int = 0,
    @SerialName("total_out") val totalOut: String = "0.00",
    @SerialName("total_in") val totalIn: String = "0.00",
)

@Serializable
data class BillExtractResponse(
    val items: List<BillImportItem> = emptyList(),
    val stats: BillImportStats? = null,
    val source: String = "",
)

@Serializable
data class BillCommitRequest(
    val items: List<BillImportItem>,
)

@Serializable
data class BillCommitDetail(
    val status: String = "",
    val merchant: String = "",
    val amount: String = "",
    @SerialName("group_id") val groupId: String = "",
    val error: String = "",
)

@Serializable
data class BillCommitResult(
    val committed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val details: List<BillCommitDetail> = emptyList(),
)
