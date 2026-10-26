package com.poi.homelab.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poi.homelab.data.BillAccount
import com.poi.homelab.data.BillTransaction
import com.poi.homelab.ui.UiState
import com.poi.homelab.ui.components.CategoryBar
import com.poi.homelab.ui.components.CreamCard
import com.poi.homelab.ui.components.DarkCard
import com.poi.homelab.ui.components.DarkStat
import com.poi.homelab.ui.components.EmptyState
import com.poi.homelab.ui.components.InlineAction
import com.poi.homelab.ui.components.LoadingRow
import com.poi.homelab.ui.components.PrimaryButton
import com.poi.homelab.ui.components.SectionHeader
import com.poi.homelab.ui.components.TransactionRow
import com.poi.homelab.ui.theme.OnDark
import com.poi.homelab.ui.theme.OnDarkSoft
import com.poi.homelab.ui.theme.SuccessStrong
import com.poi.homelab.util.money
import com.poi.homelab.util.monthLabel
import com.poi.homelab.util.plainAmount
import java.time.YearMonth
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BillsScreen(
    state: UiState,
    onSelectMonth: (String) -> Unit,
    onRefresh: () -> Unit,
    onAnalysis: () -> Unit,
) {
    val overview = state.overview
    val maxCategory = overview?.byCategory?.maxOfOrNull { plainAmount(it.amount) } ?: 0.0
    val months = remember { buildMonths(state.billMonth) }
    val initialIndex = remember(months) {
        months.indexOfFirst { it.toString() == state.billMonth }.coerceAtLeast(0)
    }
    val monthListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val snapBehavior = rememberSnapFlingBehavior(
        lazyListState = monthListState,
        snapPosition = SnapPosition.Center,
    )
    var settleTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(monthListState, state.billMonth) {
        snapshotFlow { monthListState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val info = monthListState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val closest = info.visibleItemsInfo.minByOrNull { item ->
                abs(item.offset + item.size / 2 - center)
            }
            settleTick++
            val month = closest?.index?.let { months.getOrNull(it)?.toString() }
            if (month != null && month != state.billMonth) {
                onSelectMonth(month)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "账单",
                subtitle = "poi的账单",
                action = { InlineAction("刷新", onRefresh) },
            )
        }

        if (state.settings.billUrl.isBlank()) {
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
            return@LazyColumn
        }

        item {
            CreamCard {
                Text("选择月份", style = MaterialTheme.typography.labelMedium)
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val cardWidth = minOf(maxWidth * 0.36f, 132.dp)
                    val sidePadding = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
                    LazyRow(
                        state = monthListState,
                        flingBehavior = snapBehavior,
                        contentPadding = PaddingValues(horizontal = sidePadding),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(months) { index, month ->
                            val key = month.toString()
                            val selected = key == state.billMonth
                            Surface(
                                modifier = Modifier
                                    .width(cardWidth)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        scope.launch { monthListState.animateScrollToItem(index) }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.background
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = "${month.monthValue}月",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                    Text(
                                        text = month.year.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = monthLabel(state.billMonth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (overview != null) {
                        Text(
                            text = "${overview.monthStats.countOut} 笔支出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                item {
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

                if (overview.byCategory.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "支出分类",
                            subtitle = "日均 ${money(overview.monthStats.dailyAvg)} · 活跃 ${overview.monthStats.activeDays} 天",
                        )
                    }
                    item {
                        CreamCard {
                            overview.byCategory.take(7).forEach { category ->
                                CategoryBar(category.name, category.amount, maxCategory)
                            }
                        }
                    }
                }

                item {
                    PrimaryButton(
                        text = if (state.loadingAnalysis) "正在分析…" else "AI 财务分析",
                        onClick = onAnalysis,
                        loading = state.loadingAnalysis,
                    )
                }

                item {
                    SectionHeader(
                        title = "流水",
                        subtitle = "本月 ${state.transactions.size} 笔",
                    )
                }

                if (state.transactions.isEmpty()) {
                    item { CreamCard { EmptyState("这个月还没有流水") } }
                } else {
                    item {
                        TransactionTimeline(
                            transactions = state.transactions,
                            trigger = settleTick,
                        )
                    }
                }
            }
        }
    }
}

private fun buildMonths(current: String): List<YearMonth> {
    val now = YearMonth.now()
    val range = (-24..3).map { now.plusMonths(it.toLong()) }.toMutableList()
    val parsed = runCatching { YearMonth.parse(current) }.getOrNull()
    if (parsed != null && parsed !in range) range += parsed
    return range.sorted()
}

@Composable
private fun TransactionTimeline(transactions: List<BillTransaction>, trigger: Int) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger, transactions) {
        progress.snapTo(0f)
        delay(160)
        val duration = (240 + transactions.size * 90).coerceAtMost(1500)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = duration, easing = LinearOutSlowInEasing),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        transactions.forEachIndexed { index, txn ->
            TimelineRow(
                txn = txn,
                index = index,
                total = transactions.size,
                progress = progress.value,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    txn: BillTransaction,
    index: Int,
    total: Int,
    progress: Float,
) {
    val segment = ((progress * total) - index).coerceIn(0f, 1f)
    val nextSegment = ((progress * total) - (index + 1)).coerceIn(0f, 1f)
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val cardBackground = MaterialTheme.colorScheme.background

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val dotY = 22.dp.toPx()
                val stroke = 2.dp.toPx()

                if (index > 0) {
                    drawLine(track.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, dotY), stroke)
                    if (segment > 0f) {
                        drawLine(
                            color = accent,
                            start = Offset(x, 0f),
                            end = Offset(x, dotY * segment),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                if (index < total - 1) {
                    drawLine(track.copy(alpha = 0.45f), Offset(x, dotY), Offset(x, size.height), stroke)
                    if (nextSegment > 0f) {
                        drawLine(
                            color = accent,
                            start = Offset(x, dotY),
                            end = Offset(x, dotY + (size.height - dotY) * nextSegment),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                drawCircle(
                    color = accent.copy(alpha = 0.18f + 0.82f * segment),
                    radius = 5.dp.toPx() * (0.7f + 0.3f * segment),
                    center = Offset(x, dotY),
                )
                drawCircle(
                    color = cardBackground,
                    radius = 1.8.dp.toPx(),
                    center = Offset(x, dotY),
                )
            }
        }
        CreamCard(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    alpha = 0.18f + 0.82f * segment
                    translationX = (1f - segment) * 12.dp.toPx()
                },
        ) {
            TransactionRow(txn)
            if (txn.note.isNotBlank()) {
                Text(
                    text = "备注：${txn.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
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
