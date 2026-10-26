package com.rong.homelab.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/* ------------------------------- lab-tools ------------------------------- */

@Serializable
data class LabSummary(
    val total: Int = 0,
    val running: Int = 0,
    val stopped: Int = 0,
    val unhealthy: Int = 0,
    val attention: Int = 0,
    @SerialName("checked_at") val checkedAt: String = "",
)

@Serializable
data class DiskSection(
    val count: Int = 0,
    val size: String = "",
    @SerialName("unused_size") val unusedSize: String = "",
)

@Serializable
data class DiskInfo(
    val images: DiskSection = DiskSection(),
    val containers: DiskSection = DiskSection(),
    val volumes: DiskSection = DiskSection(),
    @SerialName("build_cache") val buildCache: DiskSection = DiskSection(),
    @SerialName("total_reclaimable") val totalReclaimable: String = "",
)

@Serializable
data class ContainerInfo(
    val name: String = "",
    val state: String = "",
    val health: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val restarts: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    val ports: List<String> = emptyList(),
    @SerialName("error_count") val errorCount: Int = 0,
    val errors: List<String> = emptyList(),
    val image: String? = null,
    @SerialName("compose_project") val composeProject: String? = null,
)

@Serializable
data class HealthSnapshot(
    val summary: LabSummary = LabSummary(),
    val disk: DiskInfo = DiskInfo(),
    val attention: List<ContainerInfo> = emptyList(),
    val containers: List<ContainerInfo> = emptyList(),
)

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
