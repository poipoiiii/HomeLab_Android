package com.poi.homelab.feature.market.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.feature.ai.ui.MarkdownText
import com.poi.homelab.feature.market.DailyBar
import com.poi.homelab.feature.market.MarketEvent
import com.poi.homelab.feature.market.MarketPeriod
import com.poi.homelab.feature.market.MarketSymbol
import com.poi.homelab.feature.market.marketRegionLabel
import com.poi.homelab.feature.market.periodReturns
import com.poi.homelab.feature.market.regionKey
import com.poi.homelab.navigation.UiState
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

private val detailPriceFormat = DecimalFormat("#,##0.00")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDetailScreen(
    state: UiState,
    symbol: MarketSymbol,
    onBack: () -> Unit,
    onRefreshQuotes: () -> Unit,
    onRefreshExtras: () -> Unit,
    onLoadBars: (String) -> Unit,
    onLoadIntraday: (String) -> Unit,
    onAnalyze: (String) -> Unit,
    onRateNews: (String, Boolean) -> Unit,
) {
    val quote = state.marketQuotes.firstOrNull { it.id == symbol.id }
    val market = state.market
    val bars = if (market.barsSymbol == symbol.id) market.bars else emptyList()
    val aiText = if (market.aiSymbol == symbol.id) market.aiText else null
    val aiError = if (market.aiSymbol == symbol.id) market.aiError else null
    val aiLoading = market.aiLoading && market.aiSymbol == symbol.id
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var calendarAmount by rememberSaveable { mutableStateOf(false) }
    var period by rememberSaveable { mutableStateOf(MarketPeriod.Day) }

    val suffix = symbol.code.substringAfter('.', "")
    val events = market.events.filter { it.symbol == suffix }
    val relatedNews = market.news.filter { news ->
        news.stocks.any { it == symbol.code } || news.title.contains(symbol.label)
    }.take(20)
    val barsByDate = remember(bars) { bars.associateBy { it.date } }
    val eventsByDate = remember(events) { events.groupBy { it.date } }
    val intraday = if (market.intradaySymbol == symbol.id) market.intraday else null
    val intradayLoading = market.intradayLoading && market.intradaySymbol == symbol.id
    val intradayError = if (market.intradaySymbol == symbol.id) market.intradayError else null
    val periodPoints = remember(bars, period) {
        when (period) {
            MarketPeriod.Day -> periodReturns(bars, period, 30)
            MarketPeriod.Week -> periodReturns(bars, period, 20)
            MarketPeriod.Month -> periodReturns(bars, period, 12)
        }
    }

    LaunchedEffect(symbol.id) {
        onRefreshQuotes()
        onRefreshExtras()
        onLoadBars(symbol.id)
        onLoadIntraday(symbol.id)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineAction("‹ 行情", onBack)
                Spacer(Modifier.size(6.dp))
                Text(
                    text = symbol.label,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            CreamCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = quote?.price?.let { detailPriceFormat.format(it) } ?: "—",
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = marketChangeColor(quote?.changePercent),
                        )
                        Row(Modifier.padding(top = 2.dp)) {
                            val percent = quote?.changePercent
                            Text(
                                text = (if (quote?.previousSession == true) "昨收 " else "") +
                                    (percent?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "暂无"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = marketChangeColor(percent),
                            )
                            quote?.change?.let {
                                Text(
                                    text = "  ${String.format(Locale.US, "%+.2f", it)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = marketChangeColor(quote.changePercent),
                                )
                            }
                            if (symbol.unit.isNotBlank()) {
                                Text(
                                    text = "  ${symbol.unit}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        quote?.afterPrice?.let { afterPrice ->
                            Text(
                                text = buildString {
                                    append(quote.afterLabel)
                                    append(' ')
                                    append(detailPriceFormat.format(afterPrice))
                                    quote.afterChangePercent?.let {
                                        append(String.format(Locale.US, "（%+.2f%%）", it))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = marketChangeColor(quote.afterChangePercent),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Text(
                        text = "${marketRegionLabel(symbol.regionKey())} · ${symbol.code}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            CreamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "今日分时",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (intraday != null && intraday.points.isNotEmpty()) {
                        Text(
                            text = "${intraday.points.size} 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    intradayLoading -> LoadingRow("正在取分时…")
                    intradayError != null -> Text(
                        text = "分时没取到：$intradayError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    intraday == null || intraday.points.isEmpty() -> Text(
                        text = "今天还没有分时数据。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    else -> {
                        val last = intraday.points.last()
                        val percent = if (intraday.preClose > 0.0) {
                            (last.price - intraday.preClose) / intraday.preClose * 100
                        } else {
                            0.0
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "%.2f", last.price),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = String.format(Locale.US, "%+.2f%%", percent),
                                style = MaterialTheme.typography.bodyMedium,
                                color = marketChangeColor(percent),
                            )
                        }
                        IntradayBarChart(intraday, Modifier.padding(top = 8.dp), barHeight = 120)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(
                                text = intraday.points.firstOrNull()?.time.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = last.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            CreamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "历史涨跌",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MarketPeriod.entries.forEach { item ->
                        InlineAction(
                            text = item.label,
                            onClick = { period = item },
                            color = if (period == item) MaterialTheme.colorScheme.primary else Muted,
                        )
                    }
                }
                PeriodBarChart(periodPoints, Modifier.padding(top = 8.dp), barHeight = 130)
                Text(
                    text = "点柱子切换日期；周 K / 月 K 按区间收盘价计算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            CreamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "小砂糖分析",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction(
                        text = if (aiText == null) "开始分析" else "重新分析",
                        onClick = { onAnalyze(symbol.id) },
                        enabled = !aiLoading,
                    )
                }
                when {
                    aiLoading -> LoadingRow("小砂糖正在看行情…")
                    aiError != null -> Text(
                        text = "分析没完成：$aiError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    aiText != null -> MarkdownText(aiText, Modifier.padding(top = 10.dp))
                    else -> Text(
                        text = "会把现价、近 20 个交易日、未来财报和相关消息交给当前配置的 AI；" +
                            "只做观察，不构成投资建议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            CreamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "月历",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction(
                        text = "涨跌幅",
                        onClick = { calendarAmount = false },
                        color = if (!calendarAmount) MaterialTheme.colorScheme.primary else Muted,
                    )
                    InlineAction(
                        text = "涨跌额",
                        onClick = { calendarAmount = true },
                        color = if (calendarAmount) MaterialTheme.colorScheme.primary else Muted,
                    )
                }
                MarketMonthCalendar(month = month, onMonthChange = { month = it }) { date ->
                    val bar = barsByDate[date.toString()]
                    val dayEvents = eventsByDate[date.toString()].orEmpty()
                    DetailDayCell(
                        date = date,
                        bar = bar,
                        hasEvent = dayEvents.isNotEmpty(),
                        showAmount = calendarAmount,
                        onClick = {
                            if (bar != null || dayEvents.isNotEmpty()) selectedDate = date
                        },
                    )
                }
                Text(
                    text = "过去显示收盘涨跌幅，未来有「财」表示当天有财报。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                when {
                    market.barsLoading && market.barsSymbol == symbol.id -> LoadingRow("正在取历史行情…")
                    market.barsError != null && market.barsSymbol == symbol.id -> Text(
                        text = "历史行情没取到：${market.barsError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item { SectionHeader(title = "相关消息") }
        if (relatedNews.isEmpty()) {
            item { CreamCard { EmptyState("暂时没有和 ${symbol.label} 直接相关的消息") } }
        } else {
            items(relatedNews.size) { index ->
                val news = relatedNews[index]
                NewsCard(news = news, onRate = { useful -> onRateNews(news.id, useful) })
            }
        }
    }

    selectedDate?.let { date ->
        val bar = barsByDate[date.toString()]
        val dayEvents = eventsByDate[date.toString()].orEmpty()
        DetailDaySheet(date = date, bar = bar, events = dayEvents, onDismiss = { selectedDate = null })
    }
}

@Composable
private fun DetailDayCell(
    date: LocalDate,
    bar: DailyBar?,
    hasEvent: Boolean,
    showAmount: Boolean,
    onClick: () -> Unit,
) {
    val today = LocalDate.now()
    val future = date.isAfter(today)
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
            .clickable(enabled = bar != null || hasEvent, onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            bar != null && !future -> Text(
                text = if (showAmount) formatDayAmount(bar) else String.format(Locale.US, "%+.1f%%", bar.changePercent),
                style = MaterialTheme.typography.labelSmall,
                color = marketChangeColor(bar.changePercent),
                maxLines = 1,
            )
            hasEvent -> Text(
                text = "财",
                style = MaterialTheme.typography.labelSmall,
                color = Danger,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailDaySheet(
    date: LocalDate,
    bar: DailyBar?,
    events: List<MarketEvent>,
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
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "${date.monthValue} 月 ${date.dayOfMonth} 日",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (bar != null) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("收盘 ${detailPriceFormat.format(bar.close)}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = String.format(Locale.US, "%+.2f%%", bar.changePercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = marketChangeColor(bar.changePercent),
                    )
                }
            }
            if (events.isNotEmpty()) {
                Text("大事", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
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
        }
    }
}

/** 日历里切换成「涨跌额」时的紧凑格式，数值大就去掉小数。 */
private fun formatDayAmount(bar: DailyBar): String {
    if (bar.changePercent <= -100.0) return "--"
    val amount = bar.close * bar.changePercent / (100 + bar.changePercent)
    return if (abs(amount) >= 100) {
        String.format(Locale.US, "%+.0f", amount)
    } else {
        String.format(Locale.US, "%+.2f", amount)
    }
}
