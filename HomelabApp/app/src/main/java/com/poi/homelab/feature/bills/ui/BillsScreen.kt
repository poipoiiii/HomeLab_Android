package com.poi.homelab.feature.bills.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.DarkCard
import com.poi.homelab.core.ui.components.DarkStat
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Canvas
import com.poi.homelab.core.ui.theme.CardSurfaceAlpha
import com.poi.homelab.core.ui.theme.Coral
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.OnDark
import com.poi.homelab.core.ui.theme.OnDarkSoft
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.core.util.money
import com.poi.homelab.core.util.monthLabel
import com.poi.homelab.core.util.plainAmount
import com.poi.homelab.core.util.typeLabel
import com.poi.homelab.feature.bills.BillAccount
import com.poi.homelab.feature.bills.BillOverview
import com.poi.homelab.feature.bills.BillTransaction
import com.poi.homelab.feature.bills.CategoryAmount
import com.poi.homelab.feature.bills.MerchantAmount
import com.poi.homelab.feature.bills.MonthStats
import com.poi.homelab.feature.bills.ui.TransactionRow
import com.poi.homelab.navigation.UiState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 分类扇区配色，跟主题里已有的珊瑚 / 青绿 / 琥珀同一家族。 */
private val categoryPalette = listOf(
    Color(0xFFCC785C),
    Color(0xFF5DB8A6),
    Color(0xFFE8A55A),
    Color(0xFF7C93C3),
    Color(0xFFB07AA1),
    Color(0xFF7FB069),
    Color(0xFFD98B8B),
    Color(0xFF9A8C7A),
)

private const val NO_FILTER = ""
private const val METRIC_EXPENSE = "expense"
private const val METRIC_INCOME = "income"
private const val METRIC_NET = "net"

private val IncomeGreen = Color(0xFF5DB872)
private val IncomeGreenStrong = Color(0xFF2F7A44)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    state: UiState,
    onSelectMonth: (String) -> Unit,
    onRefresh: () -> Unit,
    onAnalysis: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val overview = state.overview
    val months = remember { buildMonths(state.billMonth) }
    val monthKeys = remember(months) { months.map { it.toString() } }
    val currentIndex = monthKeys.indexOf(state.billMonth)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var typeFilter by rememberSaveable { mutableStateOf("all") }
    var categoryFilter by rememberSaveable { mutableStateOf(NO_FILTER) }
    var accountFilter by rememberSaveable { mutableStateOf(NO_FILTER) }
    var minAmount by rememberSaveable { mutableStateOf(0f) }
    var sortMode by rememberSaveable { mutableStateOf("time") }
    var dailyView by rememberSaveable { mutableStateOf("chart") }
    var dailyMetric by rememberSaveable { mutableStateOf(METRIC_EXPENSE) }
    var flowExpanded by rememberSaveable { mutableStateOf(false) }
    var showAllMerchants by rememberSaveable { mutableStateOf(false) }
    var analysisConfirm by rememberSaveable { mutableStateOf(false) }
    var dayDetail by rememberSaveable { mutableStateOf<String?>(null) }

    val accounts = remember(overview, state.transactions) {
        (overview?.accounts?.map { it.name }.orEmpty() + state.transactions.map { it.account })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
    val categories = remember(overview) {
        overview?.byCategory?.map { it.name }.orEmpty()
    }

    // 换月后，上个月选中的分类 / 账户可能不存在了，自动取消以免筛出空列表
    LaunchedEffect(overview?.month) {
        if (categoryFilter.isNotBlank() && categories.isNotEmpty() &&
            categoryFilter !in categories
        ) {
            categoryFilter = NO_FILTER
        }
        if (accountFilter.isNotBlank() && accounts.isNotEmpty() && accountFilter !in accounts) {
            accountFilter = NO_FILTER
        }
    }
    LaunchedEffect(state.billMonth) {
        dayDetail = null
    }

    val filtered = remember(
        state.transactions,
        typeFilter,
        categoryFilter,
        accountFilter,
        minAmount,
        sortMode,
    ) {
        state.transactions
            .filter { typeFilter == "all" || it.type == typeFilter }
            .filter { categoryFilter.isBlank() || it.category == categoryFilter }
            .filter { accountFilter.isBlank() || it.account == accountFilter }
            .filter { plainAmount(it.amount) >= minAmount }
            .sortedWith(
                when (sortMode) {
                    "amountDesc" -> compareByDescending { plainAmount(it.amount) }
                    "amountAsc" -> compareBy { plainAmount(it.amount) }
                    else -> compareByDescending<BillTransaction> { it.date }.thenByDescending { it.time }
                },
            )
    }

    // 每日图表只看分类 / 账户，保持稳定引用，避免抽屉开关时重播动画
    val dailyTransactions = remember(state.transactions, categoryFilter, accountFilter) {
        state.transactions.filter { txn ->
            (categoryFilter.isBlank() || txn.category == categoryFilter) &&
                (accountFilter.isBlank() || txn.account == accountFilter)
        }
    }
    val dailyDays = remember(state.billMonth) {
        runCatching { YearMonth.parse(state.billMonth).lengthOfMonth() }.getOrDefault(31)
    }
    val dailySeries = remember(dailyTransactions, dailyDays) {
        buildDailySeries(dailyTransactions, dailyDays)
    }
    val dailyValues = remember(dailySeries, dailyMetric) {
        dailySeries.values(dailyMetric)
    }
    val groupedTransactions = remember(filtered) { filtered.groupBy { it.date } }
    val detailDate = dayDetail
    val detailRecords = remember(detailDate, dailyTransactions, dailyMetric) {
        if (detailDate == null) {
            emptyList()
        } else {
            dailyTransactions
                .filter { txn ->
                    txn.date == detailDate && when (dailyMetric) {
                        METRIC_INCOME -> txn.type == "income"
                        METRIC_NET -> txn.type == "income" || txn.type == "expense"
                        else -> txn.type == "expense"
                    }
                }
                .sortedBy { it.time }
        }
    }

    val filterCount = listOf(
        typeFilter != "all",
        categoryFilter.isNotBlank(),
        accountFilter.isNotBlank(),
        minAmount > 0f,
    ).count { it }
    val isFiltered = filterCount > 0
    val dirty = isFiltered || sortMode != "time"

    fun resetFilters() {
        typeFilter = "all"
        categoryFilter = NO_FILTER
        accountFilter = NO_FILTER
        minAmount = 0f
        sortMode = "time"
    }

    if (state.settings.billUrl.isBlank()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                BillsHeader(
                    filterCount = 0,
                    filterEnabled = false,
                    importEnabled = false,
                    onFilter = {},
                    onImport = onOpenImport,
                    onRefresh = onRefresh,
                )
            }
            item {
                CreamCard {
                    Text("还没有配置账本地址", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "到设置里填写 bill-ai 地址，例如 http://100.x.x.x:8091。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            BillFilterDrawer(
                subtitle = "${monthLabel(state.billMonth)} · 点分类图例也能快速筛选",
                accounts = accounts,
                categories = categories,
                typeFilter = typeFilter,
                categoryFilter = categoryFilter,
                accountFilter = accountFilter,
                minAmount = minAmount,
                sortMode = sortMode,
                resultCount = filtered.size,
                dirty = dirty,
                onType = { typeFilter = it },
                onCategory = { categoryFilter = it },
                onAccount = { accountFilter = it },
                onMinAmount = { minAmount = it },
                onSort = { sortMode = it },
                onReset = ::resetFilters,
                onClose = { scope.launch { drawerState.close() } },
                onApply = {
                    flowExpanded = true
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    BillsHeader(
                        filterCount = filterCount,
                        filterEnabled = true,
                        importEnabled = state.settings.billUrl.isNotBlank(),
                        onFilter = { scope.launch { drawerState.open() } },
                        onImport = onOpenImport,
                        onRefresh = onRefresh,
                    )
                }

                item {
                    CreamCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "账期",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                MonthPicker(
                                    current = state.billMonth,
                                    months = months,
                                    onSelect = onSelectMonth,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MonthStepButton(
                                    label = "‹",
                                    enabled = currentIndex > 0,
                                    onClick = {
                                        if (currentIndex > 0) onSelectMonth(monthKeys[currentIndex - 1])
                                    },
                                )
                                MonthStepButton(
                                    label = "›",
                                    enabled = currentIndex >= 0 && currentIndex < monthKeys.lastIndex,
                                    onClick = {
                                        if (currentIndex >= 0 && currentIndex < monthKeys.lastIndex) {
                                            onSelectMonth(monthKeys[currentIndex + 1])
                                        }
                                    },
                                )
                            }
                        }
                        if (overview != null) {
                            ThinDivider(Modifier.padding(top = 14.dp, bottom = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MiniStat(
                                    "支出笔数",
                                    "${overview.monthStats.countOut} 笔",
                                    Modifier.weight(1f),
                                )
                                MiniStat(
                                    "日均",
                                    money(overview.monthStats.dailyAvg),
                                    Modifier.weight(1f),
                                )
                                MiniStat(
                                    "活跃天数",
                                    "${overview.monthStats.activeDays} 天",
                                    Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                when {
                    state.loadingBills && overview == null -> item {
                        CreamCard { LoadingRow("正在读取账本…") }
                    }
                    overview == null -> item {
                        CreamCard {
                            EmptyState("这个月还没有数据")
                            InlineAction("重试", onRefresh)
                        }
                    }
                    else -> {
                        val hasDailyData = dailySeries.expense.any { it > 0f } ||
                            dailySeries.income.any { it > 0f }
                        if (hasDailyData) {
                            val dailyScope = listOf(categoryFilter, accountFilter)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            item {
                                SectionHeader(
                                    title = "每日收支",
                                    subtitle = if (dailyScope.isBlank()) {
                                        "点某一天查看当天明细"
                                    } else {
                                        "当前筛选：$dailyScope"
                                    },
                                    action = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Pill(
                                                "柱状",
                                                dailyView == "chart",
                                                onClick = { dailyView = "chart" },
                                            )
                                            Pill(
                                                "日历",
                                                dailyView == "calendar",
                                                onClick = { dailyView = "calendar" },
                                            )
                                        }
                                    },
                                )
                            }
                            item {
                                CreamCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Pill(
                                            "支出",
                                            dailyMetric == METRIC_EXPENSE,
                                            onClick = { dailyMetric = METRIC_EXPENSE },
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Pill(
                                            "收入",
                                            dailyMetric == METRIC_INCOME,
                                            onClick = { dailyMetric = METRIC_INCOME },
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Pill(
                                            "净值",
                                            dailyMetric == METRIC_NET,
                                            onClick = { dailyMetric = METRIC_NET },
                                        )
                                    }
                                    ThinDivider(Modifier.padding(top = 12.dp, bottom = 12.dp))
                                    if (dailyView == "calendar") {
                                        DailyExpenseCalendar(
                                            month = state.billMonth,
                                            values = dailyValues,
                                            metric = dailyMetric,
                                            months = months,
                                            onSelectMonth = onSelectMonth,
                                            onSelectDay = { day ->
                                                dayDetail = dateOfDay(state.billMonth, day)
                                            },
                                        )
                                    } else {
                                        DailyExpenseChart(
                                            values = dailyValues,
                                            days = dailyDays,
                                            metric = dailyMetric,
                                            onSelectDay = { day ->
                                                dayDetail = dateOfDay(state.billMonth, day)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            NetWorthCard(overview)
                        }

                        if (overview.byCategory.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "支出分布",
                                    subtitle = "点扇区或下方分类，筛选流水",
                                )
                            }
                            item {
                                CreamCard {
                                    CategoryDonut(
                                        categories = overview.byCategory,
                                        selected = categoryFilter,
                                        onSelect = { name ->
                                            categoryFilter = if (categoryFilter == name) NO_FILTER else name
                                        },
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    val total = overview.byCategory.sumOf { plainAmount(it.amount) }
                                    overview.byCategory.forEachIndexed { index, category ->
                                        CategoryLegendRow(
                                            category = category,
                                            total = total,
                                            color = categoryPalette[index % categoryPalette.size],
                                            selected = category.name == categoryFilter,
                                            onClick = {
                                                categoryFilter = if (categoryFilter == category.name) {
                                                    NO_FILTER
                                                } else {
                                                    category.name
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (overview.accounts.isNotEmpty()) {
                            item {
                                SectionHeader("账户")
                            }
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    overview.accounts.forEach { account ->
                                        AccountChip(account)
                                    }
                                }
                            }
                        }

                        if (overview.topMerchants.isNotEmpty()) {
                            val merchants = if (showAllMerchants) {
                                overview.topMerchants
                            } else {
                                overview.topMerchants.take(5)
                            }
                            item {
                                SectionHeader(
                                    title = "常去商户",
                                    subtitle = "按本月消费金额 · ${overview.topMerchants.size} 家",
                                    action = {
                                        if (overview.topMerchants.size > 5) {
                                            InlineAction(
                                                text = if (showAllMerchants) {
                                                    "收起"
                                                } else {
                                                    "查看全部 ${overview.topMerchants.size}"
                                                },
                                                onClick = { showAllMerchants = !showAllMerchants },
                                            )
                                        }
                                    },
                                )
                            }
                            item {
                                CreamCard {
                                    MerchantRanking(merchants)
                                }
                            }
                        }

                        item {
                            PrimaryButton(
                                text = if (state.loadingAnalysis) "正在分析…" else "AI 财务分析",
                                onClick = { analysisConfirm = true },
                                loading = state.loadingAnalysis,
                            )
                        }

                        item {
                            SectionHeader(
                                title = "流水明细",
                                subtitle = if (!flowExpanded) {
                                    if (isFiltered) {
                                        "筛选后 ${filtered.size} 笔 · 已折叠"
                                    } else {
                                        "本月 ${filtered.size} 笔 · 已折叠"
                                    }
                                } else if (isFiltered) {
                                    "筛选后 ${filtered.size} 笔 · 共 ${state.transactions.size} 笔"
                                } else {
                                    "本月 ${filtered.size} 笔 · 按日期分组"
                                },
                                action = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isFiltered) {
                                            InlineAction("清除筛选", ::resetFilters)
                                        }
                                        InlineAction(
                                            text = if (flowExpanded) "收起" else "展开",
                                            onClick = { flowExpanded = !flowExpanded },
                                        )
                                    }
                                },
                            )
                        }

                        if (flowExpanded) {
                            if (isFiltered) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (typeFilter != "all") Tag(typeLabel(typeFilter))
                                        if (categoryFilter.isNotBlank()) Tag(categoryFilter)
                                        if (accountFilter.isNotBlank()) Tag(accountFilter)
                                        if (minAmount > 0f) Tag("≥ ${money(minAmount.toDouble())}")
                                        if (sortMode != "time") Tag(sortLabel(sortMode))
                                    }
                                }
                            }

                            if (filtered.isEmpty()) {
                                item {
                                    CreamCard {
                                        EmptyState(
                                            if (isFiltered) "没有符合条件的流水" else "这个月还没有流水",
                                        )
                                        if (isFiltered) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.Center,
                                            ) {
                                                InlineAction("清除筛选", ::resetFilters)
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(
                                    items = groupedTransactions.entries.toList(),
                                    key = { it.key.ifBlank { "unknown" } },
                                ) { entry ->
                                    TransactionDayCard(date = entry.key, items = entry.value)
                                }
                            }
                        }
                    }
                }
            }

            if (drawerState.currentValue == DrawerValue.Closed) {
                FilterTab(
                    count = filterCount,
                    onOpen = { scope.launch { drawerState.open() } },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }

    if (analysisConfirm) {
        AlertDialog(
            onDismissRequest = { analysisConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("AI 财务分析") },
            text = {
                Text(
                    "会把最近 90 天的收支汇总发给账本服务生成分析，可能需要几十秒。要现在开始吗？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        analysisConfirm = false
                        onAnalysis()
                    },
                ) {
                    Text("开始分析")
                }
            },
            dismissButton = {
                TextButton(onClick = { analysisConfirm = false }) { Text("取消") }
            },
        )
    }

    if (detailDate != null) {
        ModalBottomSheet(
            onDismissRequest = { dayDetail = null },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DayDetailSheet(
                date = detailDate,
                records = detailRecords,
                metric = dailyMetric,
                onClose = { dayDetail = null },
            )
        }
    }
}

private fun sortLabel(mode: String): String = when (mode) {
    "amountDesc" -> "金额从高到低"
    "amountAsc" -> "金额从低到高"
    else -> "按时间"
}

private fun buildMonths(current: String): List<YearMonth> {
    val now = YearMonth.now()
    val range = (-24..3).map { now.plusMonths(it.toLong()) }.toMutableList()
    val parsed = runCatching { YearMonth.parse(current) }.getOrNull()
    if (parsed != null && parsed !in range) range += parsed
    return range.sorted()
}

@Composable
private fun BillsHeader(
    filterCount: Int,
    filterEnabled: Boolean,
    importEnabled: Boolean,
    onFilter: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionHeader(
        title = "账单",
        subtitle = "poi的账单",
        action = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (filterEnabled) {
                    InlineAction(
                        text = if (filterCount > 0) "筛选 $filterCount" else "筛选",
                        onClick = onFilter,
                        color = if (filterCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                InlineAction("导入", onImport, enabled = importEnabled)
                InlineAction("刷新", onRefresh)
            }
        },
    )
}

@Composable
private fun MonthPicker(
    current: String,
    months: List<YearMonth>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(monthLabel(current), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "▾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 380.dp),
        ) {
            months.asReversed().forEach { month ->
                val key = month.toString()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = monthLabel(key),
                            color = if (key == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (key == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun MonthStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(38.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.background else Color.Transparent,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.4f),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted,
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NetWorthCard(overview: BillOverview) {
    val income = plainAmount(overview.monthStats.income)
    val net = plainAmount(overview.monthStats.net)
    val savings = if (income > 0) {
        " · 结余率 ${(net / income * 100).roundToInt()}%"
    } else {
        ""
    }

    DarkCard {
        Text("净资产", style = MaterialTheme.typography.labelMedium, color = OnDarkSoft)
        Text(
            money(overview.netWorth),
            style = MaterialTheme.typography.displaySmall,
            color = OnDark,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        HorizontalDivider(color = Color(0xFF3A3733))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DarkStat(
                "支出",
                money(overview.monthStats.expense),
                Modifier.weight(1f),
                Color(0xFFE7A48C),
            )
            DarkStat(
                "收入",
                money(overview.monthStats.income),
                Modifier.weight(1f),
                Color(0xFF8FD9A3),
            )
            DarkStat("结余", money(overview.monthStats.net), Modifier.weight(1f))
        }
        Text(
            text = "日均 ${money(overview.monthStats.dailyAvg)} · " +
                "活跃 ${overview.monthStats.activeDays} 天$savings",
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkSoft,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun CategoryDonut(
    categories: List<CategoryAmount>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val values = categories.map { plainAmount(it.amount) }
    val total = values.sum()
    if (total <= 0.0) return

    val progress = remember(categories) { Animatable(0f) }
    LaunchedEffect(categories) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val selectedIndex = categories.indexOfFirst { it.name == selected }
    val centerTitle = if (selectedIndex >= 0) categories[selectedIndex].name else "本月支出"
    val centerAmount = if (selectedIndex >= 0) categories[selectedIndex].amount else null

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(188.dp)
                .pointerInput(categories) {
                    detectTapGestures { tap ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = tap.x - centerX
                        val dy = tap.y - centerY
                        val radius = hypot(dx, dy)
                        val stroke = 26.dp.toPx()
                        val inner = minOf(size.width, size.height) / 2f - stroke
                        val outer = minOf(size.width, size.height) / 2f + 8.dp.toPx()
                        if (radius < inner || radius > outer) return@detectTapGestures
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                        if (angle < 0f) angle += 360f
                        var acc = 0f
                        categories.forEachIndexed { index, category ->
                            val sweep = (values[index] / total * 360.0).toFloat()
                            if (angle >= acc && angle < acc + sweep) {
                                onSelect(category.name)
                                return@detectTapGestures
                            }
                            acc += sweep
                        }
                    }
                },
        ) {
            val stroke = 26.dp.toPx()
            val ring = minOf(size.width, size.height) - stroke
            val topLeft = Offset((size.width - ring) / 2f, (size.height - ring) / 2f)
            val arcSize = Size(ring, ring)
            val gap = 2.4f
            var start = -90f
            values.forEachIndexed { index, value ->
                val sweep = (value / total * 360.0).toFloat()
                val active = selected.isBlank() || categories[index].name == selected
                val color = categoryPalette[index % categoryPalette.size]
                    .copy(alpha = if (active) 1f else 0.26f)
                if (sweep > gap) {
                    drawArc(
                        color = color,
                        startAngle = start + gap / 2f,
                        sweepAngle = (sweep - gap) * progress.value,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(
                            width = if (categories[index].name == selected) {
                                stroke + 4.dp.toPx()
                            } else {
                                stroke
                            },
                            cap = StrokeCap.Round,
                        ),
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (centerAmount != null) money(centerAmount) else money(total),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = if (selectedIndex >= 0) {
                    "${categories[selectedIndex].count} 笔"
                } else {
                    "${categories.sumOf { it.count }} 笔"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun CategoryLegendRow(
    category: CategoryAmount,
    total: Double,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = category.name.ifBlank { "未分类" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 9.dp),
        )
        Text(
            text = percentOf(plainAmount(category.amount), total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = money(category.amount),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun percentOf(value: Double, total: Double): String {
    if (total <= 0.0) return "0%"
    val percent = value / total * 100
    return if (percent > 0 && percent < 1) "<1%" else "${percent.roundToInt()}%"
}

private class DailySeries(val expense: FloatArray, val income: FloatArray) {
    fun values(metric: String): FloatArray = when (metric) {
        METRIC_INCOME -> income
        METRIC_NET -> FloatArray(expense.size) { income[it] - expense[it] }
        else -> expense
    }
}

private fun buildDailySeries(transactions: List<BillTransaction>, days: Int): DailySeries {
    val expense = FloatArray(days)
    val income = FloatArray(days)
    transactions.forEach { txn ->
        val day = txn.date.takeLast(2).toIntOrNull() ?: return@forEach
        if (day !in 1..days) return@forEach
        val value = plainAmount(txn.amount).toFloat()
        when (txn.type) {
            "income" -> income[day - 1] += value
            "expense" -> expense[day - 1] += value
        }
    }
    return DailySeries(expense, income)
}

private fun metricLabel(metric: String): String = when (metric) {
    METRIC_INCOME -> "收入"
    METRIC_NET -> "净值"
    else -> "支出"
}

private fun dateOfDay(month: String, day: Int): String =
    runCatching { YearMonth.parse(month).atDay(day).toString() }.getOrDefault("")

private fun dayLabel(date: String): String = runCatching {
    val day = LocalDate.parse(date)
    "${day.monthValue}月${day.dayOfMonth}日 ${weekdayLabel(day.dayOfWeek)}"
}.getOrDefault(date.ifBlank { "当天明细" })

private fun weekdayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

@Composable
private fun DailyExpenseChart(
    values: FloatArray,
    days: Int,
    metric: String,
    onSelectDay: (Int) -> Unit,
) {
    val maxPos = (values.maxOrNull() ?: 0f).coerceAtLeast(0f)
    val maxNeg = (-(values.minOrNull() ?: 0f)).coerceAtLeast(0f)
    if (maxPos <= 0f && maxNeg <= 0f) {
        EmptyState("当前筛选下没有${metricLabel(metric)}记录")
        return
    }
    val isNet = metric == METRIC_NET
    val activeDays = values.count { kotlin.math.abs(it) > 0.005f }
    val peakIndex = if (isNet) -1 else values.indexOfFirst { it == maxPos }

    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    val primary = MaterialTheme.colorScheme.primary
    val positiveColor = if (metric == METRIC_EXPENSE) primary else IncomeGreen
    val negativeColor = Coral
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(values) {
                    detectTapGestures { tap ->
                        val gap = 2.dp.toPx()
                        val barWidth = ((size.width - gap * (days - 1)) / days).coerceAtLeast(1f)
                        val index = (tap.x / (barWidth + gap)).toInt().coerceIn(0, days - 1)
                        if (kotlin.math.abs(values[index]) > 0.005f) onSelectDay(index + 1)
                    }
                },
        ) {
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (days - 1)) / days).coerceAtLeast(1f)
            val chartHeight = size.height - 4.dp.toPx()
            val range = maxPos + maxNeg
            val zeroY = if (isNet && range > 0f) {
                chartHeight * (maxPos / range)
            } else {
                chartHeight
            }
            val positiveSpace = zeroY
            val negativeSpace = chartHeight - zeroY

            drawLine(
                color = baselineColor.copy(alpha = 0.55f),
                start = Offset(0f, zeroY),
                end = Offset(size.width, zeroY),
                strokeWidth = 1.dp.toPx(),
            )

            if (!isNet && activeDays > 0 && maxPos > 0f) {
                val average = values.sum() / activeDays
                val averageY = zeroY - positiveSpace * (average / maxPos)
                drawLine(
                    color = averageColor.copy(alpha = 0.45f),
                    start = Offset(0f, averageY),
                    end = Offset(size.width, averageY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())),
                )
            }

            values.forEachIndexed { index, value ->
                if (kotlin.math.abs(value) < 0.005f) return@forEachIndexed
                val positive = value > 0f
                val reference = if (positive) maxPos else maxNeg
                if (reference <= 0f) return@forEachIndexed
                val space = if (positive) positiveSpace else negativeSpace
                val height = space * (kotlin.math.abs(value) / reference) * progress.value
                if (height <= 0.4f) return@forEachIndexed
                val color = when {
                    positive && index == peakIndex -> {
                        if (metric == METRIC_EXPENSE) Coral else IncomeGreenStrong
                    }
                    positive -> positiveColor.copy(alpha = if (isNet) 0.82f else 0.72f)
                    else -> negativeColor.copy(alpha = 0.82f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * (barWidth + gap), if (positive) zeroY - height else zeroY),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f, minOf(barWidth / 2f, height / 2f)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("1日", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text("${days}日", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        val summary = if (isNet) {
            buildString {
                append("最高 +").append(money(maxPos.toDouble()))
                if (maxNeg > 0f) append(" · 最低 -").append(money(maxNeg.toDouble()))
                append(" · 有记录 ").append(activeDays).append(" 天")
            }
        } else {
            "峰值 ${money(maxPos.toDouble())} · 有${metricLabel(metric)} $activeDays 天"
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DailyExpenseCalendar(
    month: String,
    values: FloatArray,
    metric: String,
    months: List<YearMonth>,
    onSelectMonth: (String) -> Unit,
    onSelectDay: (Int) -> Unit,
) {
    val yearMonth = remember(month) {
        runCatching { YearMonth.parse(month) }.getOrElse { YearMonth.now() }
    }
    val days = values.size
    // 周一作为一周第一天
    val leading = yearMonth.atDay(1).dayOfWeek.value - 1
    val rows = (leading + days + 6) / 7
    val peak = values.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
    val today = remember { LocalDate.now() }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthPicker(current = month, months = months, onSelect = onSelectMonth)
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    peak <= 0f -> "本月没有${metricLabel(metric)}记录"
                    metric == METRIC_NET -> "单日最高 ${money(peak.toDouble())}"
                    else -> "峰值 ${money(peak.toDouble())}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                for (col in 0 until 7) {
                    val day = row * 7 + col - leading + 1
                    Box(Modifier.weight(1f).height(50.dp).padding(horizontal = 2.dp)) {
                        if (day in 1..days) {
                            val amount = values[day - 1]
                            DayExpenseCell(
                                day = day,
                                amount = amount,
                                peak = peak,
                                metric = metric,
                                isToday = yearMonth.year == today.year &&
                                    yearMonth.monthValue == today.monthValue &&
                                    today.dayOfMonth == day,
                                onClick = if (kotlin.math.abs(amount) > 0.005f) {
                                    { onSelectDay(day) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayExpenseCell(
    day: Int,
    amount: Float,
    peak: Float,
    metric: String,
    isToday: Boolean,
    onClick: (() -> Unit)?,
) {
    val primary = MaterialTheme.colorScheme.primary
    val hasValue = kotlin.math.abs(amount) > 0.005f
    val intensity = if (peak <= 0f) 0f else (kotlin.math.abs(amount) / peak).coerceIn(0f, 1f)
    val tint = when {
        !hasValue -> null
        metric == METRIC_NET -> if (amount > 0f) IncomeGreen else Coral
        metric == METRIC_INCOME -> IncomeGreen
        else -> primary
    }
    val background = if (tint != null) {
        tint.copy(alpha = 0.10f + 0.38f * intensity)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(background)
            .then(
                if (isToday) {
                    Modifier.border(1.dp, primary.copy(alpha = 0.65f), shape)
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$day",
            style = MaterialTheme.typography.labelMedium,
            color = if (hasValue) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (hasValue && tint != null) {
            Text(
                text = if (metric == METRIC_NET) signedCompactAmount(amount) else compactAmount(amount),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = tint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private fun compactAmount(value: Float): String =
    if (value >= 10000f) "¥${(value / 10000f).roundToInt()}万" else "¥${value.roundToInt()}"

private fun signedCompactAmount(value: Float): String = when {
    value >= 10000f -> "+¥${(value / 10000f).roundToInt()}万"
    value > 0f -> "+${compactAmount(value)}"
    value <= -10000f -> "-¥${(-value / 10000f).roundToInt()}万"
    else -> "-${compactAmount(-value)}"
}

@Composable
private fun DayDetailSheet(
    date: String,
    records: List<BillTransaction>,
    metric: String,
    onClose: () -> Unit,
) {
    val expense = records.filter { it.type == "expense" }.sumOf { plainAmount(it.amount) }
    val income = records.filter { it.type == "income" }.sumOf { plainAmount(it.amount) }
    val subtitle = when (metric) {
        METRIC_INCOME -> "${records.size} 笔收入 · ${money(income)}"
        METRIC_NET -> "收入 ${money(income)} · 支出 ${money(expense)}"
        else -> "${records.size} 笔消费 · ${money(expense)}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dayLabel(date), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            InlineAction("关闭", onClose)
        }
        ThinDivider(Modifier.padding(vertical = 12.dp))
        if (records.isEmpty()) {
            EmptyState(
                if (metric == METRIC_NET) "这一天没有收支记录" else "这一天没有${metricLabel(metric)}记录",
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                records.forEachIndexed { index, txn ->
                    TransactionRow(txn)
                    if (index != records.lastIndex) ThinDivider()
                }
            }
        }
    }
}

@Composable
private fun TransactionDayCard(date: String, items: List<BillTransaction>) {
    val expense = items.filter { it.type == "expense" }.sumOf { plainAmount(it.amount) }
    val income = items.filter { it.type == "income" }.sumOf { plainAmount(it.amount) }

    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(dayLabel(date), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${items.size} 笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (expense > 0) {
                    Text(
                        text = "支出 ${money(expense)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (income > 0) {
                    Text(
                        text = "收入 ${money(income)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessStrong,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
        ThinDivider(Modifier.padding(top = 10.dp, bottom = 2.dp))
        items.forEachIndexed { index, txn ->
            TransactionRow(txn)
            if (index != items.lastIndex) ThinDivider()
        }
    }
}

@Composable
private fun MerchantRanking(merchants: List<MerchantAmount>) {
    merchants.forEachIndexed { index, merchant ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = merchant.name.ifBlank { "未知商户" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${merchant.count} 笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(text = money(merchant.amount), style = MaterialTheme.typography.titleSmall)
        }
        if (index != merchants.lastIndex) ThinDivider()
    }
}

@Composable
private fun BillFilterDrawer(
    subtitle: String,
    accounts: List<String>,
    categories: List<String>,
    typeFilter: String,
    categoryFilter: String,
    accountFilter: String,
    minAmount: Float,
    sortMode: String,
    resultCount: Int,
    dirty: Boolean,
    onType: (String) -> Unit,
    onCategory: (String) -> Unit,
    onAccount: (String) -> Unit,
    onMinAmount: (Float) -> Unit,
    onSort: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onApply: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(340.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "筛选流水",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (dirty) InlineAction("清空", onReset)
                InlineAction("收起", onClose)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            DrawerSection("类型") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "all" to "全部",
                        "expense" to "支出",
                        "income" to "收入",
                        "transfer" to "转账",
                    ).forEach { (key, label) ->
                        Pill(text = label, selected = typeFilter == key, onClick = { onType(key) })
                    }
                }
            }

            DrawerSection("排序") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "time" to "最新",
                        "amountDesc" to "金额↓",
                        "amountAsc" to "金额↑",
                    ).forEach { (key, label) ->
                        Pill(text = label, selected = sortMode == key, onClick = { onSort(key) })
                    }
                }
            }

            DrawerSection("账户") {
                DrawerPillRow(accounts, accountFilter, onAccount)
            }

            DrawerSection("分类") {
                DrawerPillRow(categories, categoryFilter, onCategory)
            }

            DrawerSection("最低金额") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (minAmount <= 0f) "不限" else "≥ ${money(minAmount.toDouble())}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "最高 5000",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Slider(
                    value = minAmount,
                    onValueChange = onMinAmount,
                    valueRange = 0f..5000f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(26.dp))
            PrimaryButton(text = "查看 $resultCount 笔", onClick = onApply)
            Text(
                text = "只影响当前页面的流水列表，不改动云端账本。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun DrawerSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun DrawerPillRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(text = "全部", selected = selected.isBlank(), onClick = { onSelect(NO_FILTER) })
        options.forEach { option ->
            Pill(text = option, selected = selected == option, onClick = { onSelect(option) })
        }
    }
}

/** 左侧「筛选」标签，点一下或往右拖都能拉出筛选项。 */
@Composable
private fun FilterTab(
    count: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(26.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "筛",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "选",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count > 0) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountChip(account: BillAccount) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = money(account.balance),
                style = MaterialTheme.typography.titleMedium,
                color = SuccessStrong,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
