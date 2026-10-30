package com.poi.homelab.feature.market.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Amber
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.core.ui.theme.Teal
import com.poi.homelab.core.util.money
import com.poi.homelab.feature.market.MarketEvent
import com.poi.homelab.feature.market.MarketNews
import com.poi.homelab.feature.market.MarketQuote
import com.poi.homelab.feature.market.MarketScorer
import com.poi.homelab.feature.market.MarketSearchResult
import com.poi.homelab.feature.market.MarketSymbol
import com.poi.homelab.feature.market.NewsFeedback
import com.poi.homelab.feature.market.categoryLabel
import com.poi.homelab.feature.market.findMarketSymbol
import com.poi.homelab.feature.market.pinnedMarketSymbolIds
import com.poi.homelab.navigation.UiState
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.delay

private val marketPriceFormat = DecimalFormat("#,##0.00")

@Composable
fun MarketScreen(
    state: UiState,
    selectedSymbolId: String?,
    onSelectSymbol: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onRefreshQuotes: () -> Unit,
    onRefreshExtras: () -> Unit,
    onLoadBars: (String) -> Unit,
    onLoadIntraday: (String) -> Unit,
    onAnalyze: (String) -> Unit,
    onRateNews: (String, Boolean) -> Unit,
    onPreviewSymbol: (String) -> Unit,
    onSetRefreshSeconds: (Int) -> Unit,
    onSearch: suspend (String) -> List<MarketSearchResult>,
    onSaveSymbols: (List<MarketSymbol>, Boolean) -> Unit,
    onSavePinnedSymbols: (List<MarketSymbol>, Boolean) -> Unit,
    onResetSymbols: () -> Unit,
) {
    val symbol = findMarketSymbol(
        state.settings.marketPinnedSymbols,
        state.settings.marketSymbols,
        selectedSymbolId,
    )
    if (symbol != null) {
        MarketDetailScreen(
            state = state,
            symbol = symbol,
            onBack = onCloseDetail,
            onRefreshQuotes = onRefreshQuotes,
            onRefreshExtras = onRefreshExtras,
            onLoadBars = onLoadBars,
            onLoadIntraday = onLoadIntraday,
            onAnalyze = onAnalyze,
            onRateNews = onRateNews,
        )
    } else {
        MarketListScreen(
            state = state,
            onSelectSymbol = onSelectSymbol,
            onPreviewSymbol = onPreviewSymbol,
            onRefreshQuotes = onRefreshQuotes,
            onRefreshExtras = onRefreshExtras,
            onRateNews = onRateNews,
            onSetRefreshSeconds = onSetRefreshSeconds,
            onSearch = onSearch,
            onSaveSymbols = onSaveSymbols,
            onSavePinnedSymbols = onSavePinnedSymbols,
            onResetSymbols = onResetSymbols,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketListScreen(
    state: UiState,
    onSelectSymbol: (String) -> Unit,
    onPreviewSymbol: (String) -> Unit,
    onRefreshQuotes: () -> Unit,
    onRefreshExtras: () -> Unit,
    onRateNews: (String, Boolean) -> Unit,
    onSetRefreshSeconds: (Int) -> Unit,
    onSearch: suspend (String) -> List<MarketSearchResult>,
    onSaveSymbols: (List<MarketSymbol>, Boolean) -> Unit,
    onSavePinnedSymbols: (List<MarketSymbol>, Boolean) -> Unit,
    onResetSymbols: () -> Unit,
) {
    val quotes = state.marketQuotes
    val market = state.market
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var recordsOpen by rememberSaveable { mutableStateOf(false) }
    var refreshOpen by rememberSaveable { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val inspection = LocalInspectionMode.current
    val refreshMs = state.settings.marketRefreshSeconds.coerceIn(10, 300) * 1000L

    LaunchedEffect(lifecycleOwner, inspection, refreshMs) {
        if (inspection) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onRefreshQuotes()
                delay(refreshMs)
            }
        }
    }
    LaunchedEffect(Unit) { onRefreshExtras() }

    val eventsByDate = remember(market.events) { market.events.groupBy { it.date } }
    val newsByDate = remember(market.news) { market.news.groupBy { it.date } }
    val quoteGroups = remember(quotes, state.settings.marketPinnedSymbols) {
        val pinnedIds = pinnedMarketSymbolIds(state.settings.marketPinnedSymbols)
        quotes.filterNot { it.id in pinnedIds }
            .groupBy { it.categoryLabel() }
            .toList()
    }
    val regularQuotes = remember(quotes, state.settings.marketPinnedSymbols) {
        quotes.filterNot { it.id in pinnedMarketSymbolIds(state.settings.marketPinnedSymbols) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 10.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "行情",
                subtitle = "自选、财报日历和市场消息，都不经过家里服务器",
                action = {
                    InlineAction("刷新 ${state.settings.marketRefreshSeconds}s", { refreshOpen = true })
                    InlineAction("编辑自选", { editOpen = true })
                },
            )
        }
        item {
            CreamCard {
                Text("大盘", style = MaterialTheme.typography.titleSmall)
                if (quotes.isEmpty() && state.marketLoading) {
                    LoadingRow("正在取大盘…")
                } else {
                    PinnedMarketRow(
                        symbols = state.settings.marketPinnedSymbols,
                        quotes = quotes,
                        onOpen = onSelectSymbol,
                        onPreview = onPreviewSymbol,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (regularQuotes.isEmpty()) {
            item {
                CreamCard {
                    EmptyState(if (state.marketLoading) "正在取行情…" else "自选列表还是空的，点右上角「编辑自选」添加")
                }
            }
        } else {
            quoteGroups.forEach { (category, categoryQuotes) ->
                item(key = "quotes:$category") {
                    CreamCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${categoryQuotes.size} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        categoryQuotes.chunked(2).forEachIndexed { index, rowQuotes ->
                            if (index > 0) ThinDivider(Modifier.padding(vertical = 10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                rowQuotes.forEach { quote ->
                                    MarketQuoteCell(
                                        quote = quote,
                                        onClick = { onSelectSymbol(quote.id) },
                                        onLongClick = { onPreviewSymbol(quote.id) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (rowQuotes.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item {
            EarningsCard(
                events = market.events,
                loading = market.eventsLoading,
                error = market.eventsError,
            )
        }
        item { AlgorithmCard(market, onShowRecords = { recordsOpen = true }) }
        item {
            SectionHeader(
                title = "全局日历",
                subtitle = "红点是财报，绿点是当天消息",
            )
        }
        item {
            CreamCard {
                MarketMonthCalendar(month = month, onMonthChange = { month = it }) { date ->
                    val events = eventsByDate[date.toString()].orEmpty()
                    val news = newsByDate[date.toString()].orEmpty()
                    GlobalDayCell(
                        date = date,
                        events = events.size,
                        news = news.size,
                        onClick = { if (events.isNotEmpty() || news.isNotEmpty()) selectedDate = date },
                    )
                }
            }
        }
        item {
            SectionHeader(
                title = "市场消息",
                subtitle = "本地评分，点「有用 / 没用」帮算法校准",
                action = { InlineAction("刷新", onRefreshExtras) },
            )
        }
        if (market.newsLoading && market.news.isEmpty()) {
            item { CreamCard { LoadingRow("正在拉市场消息…") } }
        } else if (market.newsError != null && market.news.isEmpty()) {
            item {
                CreamCard {
                    Text("消息没取到：${market.newsError}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(market.news.take(20).size) { index ->
                val news = market.news[index]
                NewsCard(news = news, onRate = { useful -> onRateNews(news.id, useful) })
            }
        }
    }

    if (editOpen) {
        MarketEditorSheet(
            symbols = state.settings.marketSymbols,
            pinnedSymbols = state.settings.marketPinnedSymbols,
            onAdd = { symbol -> onSaveSymbols(state.settings.marketSymbols + symbol, true) },
            onRemove = { symbol -> onSaveSymbols(state.settings.marketSymbols - symbol, true) },
            onSaveSymbols = onSaveSymbols,
            onSavePinned = onSavePinnedSymbols,
            onReset = onResetSymbols,
            onSearch = onSearch,
            onDismiss = { editOpen = false },
        )
    }

    selectedDate?.let { date ->
        DayDetailSheet(
            date = date,
            events = eventsByDate[date.toString()].orEmpty(),
            news = newsByDate[date.toString()].orEmpty(),
            onDismiss = { selectedDate = null },
        )
    }

    if (recordsOpen) {
        NewsRecordsSheet(records = market.mind.feedback, onDismiss = { recordsOpen = false })
    }

    if (refreshOpen) {
        AlertDialog(
            onDismissRequest = { refreshOpen = false },
            title = { Text("行情刷新频率") },
            text = {
                Column {
                    listOf(10 to "10 秒（最快）", 30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟")
                        .forEach { (seconds, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSetRefreshSeconds(seconds)
                                        refreshOpen = false
                                    }
                                    .padding(vertical = 11.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (state.settings.marketRefreshSeconds == seconds) {
                                    Text(
                                        text = "当前",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    Text(
                        text = "东财没有公开单分钟上限，社区实践一般控制在 1 秒 1 次以内。" +
                            "这里一次刷新会请求东财 1 次 + 腾讯 1 次（有美股再加新浪 1 次），所以最快只给 10 秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { refreshOpen = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarketQuoteCell(
    quote: MarketQuote,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = quote.changePercent
    val tint = marketChangeColor(percent)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = quote.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (quote.unit.isNotBlank()) {
                Text(
                    text = quote.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = quote.price?.let { marketPriceFormat.format(it) } ?: "—",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = percent?.let {
                (if (quote.previousSession) "昨收 " else "") +
                    String.format(java.util.Locale.US, "%+.2f%%", it)
            } ?: "暂无",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
        if (quote.afterPrice != null) {
            Text(
                text = buildString {
                    append(quote.afterLabel)
                    append(' ')
                    append(marketPriceFormat.format(quote.afterPrice))
                    quote.afterChangePercent?.let {
                        append(String.format(java.util.Locale.US, " %+.2f%%", it))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = marketChangeColor(quote.afterChangePercent),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EarningsCard(
    events: List<MarketEvent>,
    loading: Boolean,
    error: String?,
) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "纳指 100 财报",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "未来 10 天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            loading && events.isEmpty() -> LoadingRow("正在拉财报日历…")
            error != null && events.isEmpty() -> Text(
                text = "财报日历没取到：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            events.isEmpty() -> Text(
                text = "未来 10 天没有纳指 100 成分股要发财报。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            else -> events.take(8).forEachIndexed { index, event ->
                if (index > 0) ThinDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.date.takeLast(5),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (event.detail.isNotBlank()) {
                            Text(
                                text = event.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlgorithmCard(
    market: com.poi.homelab.navigation.MarketUiState,
    onShowRecords: () -> Unit,
) {
    val mind = market.mind
    val pending = mind.feedback.count { !it.calibrated }
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "消息评分算法",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("记录", onShowRecords)
            Tag("v${mind.weights.version}", Teal)
        }
        Text(
            text = "有效 ${mind.usefulTotal} · 无效 ${mind.uselessTotal} · 待校准 $pending",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "上次校准：${mind.lastCalibrated.ifBlank { "还没校准" }}（每天首次打开时用你的评价微调）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = "影响 ${oneDecimal(mind.weights.impact)} · 不确定 ${oneDecimal(mind.weights.uncertainty)} · " +
                "数字 ${oneDecimal(mind.weights.specificity)} · 热度 ${oneDecimal(mind.weights.heat)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "规则：强影响词加分，具体数字和讨论热度加分，传闻/据悉这类不确定表述扣分。只做重要性和确定性打分，不判断消息真假。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun GlobalDayCell(
    date: LocalDate,
    events: Int,
    news: Int,
    onClick: () -> Unit,
) {
    val today = LocalDate.now()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (date == today) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(enabled = events > 0 || news > 0, onClick = onClick)
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(top = 2.dp).height(5.dp),
        ) {
            if (events > 0) Box(Modifier.size(5.dp).clip(CircleShape).background(Danger))
            if (news > 0) Box(Modifier.size(5.dp).clip(CircleShape).background(Teal))
        }
    }
}

@Composable
internal fun NewsCard(news: MarketNews, onRate: (Boolean) -> Unit) {
    val grade = MarketScorer.grade(news.score)
    val color = when {
        news.score >= 75 -> Danger
        news.score >= 55 -> Amber
        else -> Muted
    }
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("${news.score} · $grade", color)
            Spacer(Modifier.size(8.dp))
            Text(
                text = news.time.takeLast(5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = news.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (news.summary.isNotBlank() && news.summary != news.title) {
            Text(
                text = news.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (news.reasons.isNotEmpty()) {
            Text(
                text = news.reasons.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            when (news.feedback) {
                1 -> Text(
                    text = "已记录：有用",
                    style = MaterialTheme.typography.labelMedium,
                    color = Success,
                    modifier = Modifier.weight(1f),
                )
                -1 -> Text(
                    text = "已记录：没用",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    InlineAction("有用", { onRate(true) })
                    InlineAction("没用", { onRate(false) }, color = Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailSheet(
    date: LocalDate,
    events: List<MarketEvent>,
    news: List<MarketNews>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "${date.monthValue} 月 ${date.dayOfMonth} 日",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (events.isNotEmpty()) {
                Text(
                    text = "财报",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
                events.forEach { event ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(event.title, style = MaterialTheme.typography.bodyMedium)
                        if (event.detail.isNotBlank()) {
                            Text(
                                text = event.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (news.isNotEmpty()) {
                Text(
                    text = "消息",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                news.take(12).forEach { item ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Tag("${item.score}", if (item.score >= 75) Danger else Amber)
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (events.isEmpty() && news.isEmpty()) {
                EmptyState("这天还没有记录")
            }
        }
    }
}

private fun oneDecimal(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsRecordsSheet(
    records: List<NewsFeedback>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "评价记录",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("完成", onDismiss)
            }
            Text(
                text = "每天首次打开时，待校准的样本会折进算法权重，然后标记为已校准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            if (records.isEmpty()) {
                EmptyState("还没有评价过消息")
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(records.reversed().size) { index ->
                        val record = records.reversed()[index]
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (record.useful) "有用" else "没用",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (record.useful) Success else Muted,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    text = "${record.date} · ${if (record.calibrated) "已校准" else "待校准"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = record.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (index != records.size - 1) ThinDivider()
                    }
                }
            }
        }
    }
}
