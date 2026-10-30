package com.poi.homelab.feature.market.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.feature.market.MarketQuote
import com.poi.homelab.feature.market.MarketSearchResult
import com.poi.homelab.feature.market.MarketSymbol
import com.poi.homelab.feature.market.categoryLabel
import com.poi.homelab.feature.market.marketRegionLabel
import com.poi.homelab.feature.market.pinnedMarketSymbolIds
import com.poi.homelab.feature.market.regionKey
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val priceFormat = DecimalFormat("#,##0.00")

/**
 * 首页行情卡片：两列小格子，只看价格和涨跌幅。
 * 数据直连公开接口，家里服务器没开也能看。
 */
@Composable
fun MarketCard(
    quotes: List<MarketQuote>,
    loading: Boolean,
    error: String?,
    updatedAt: Long,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onOpenSymbol: (String) -> Unit = {},
    onPreviewSymbol: (String) -> Unit = {},
    pinnedSymbols: List<MarketSymbol> = emptyList(),
) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("行情", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        updatedAt > 0 -> "每 30 秒刷新 · ${timeLabel(updatedAt)} 更新"
                        loading -> "正在取行情…"
                        error != null -> "没取到行情"
                        else -> "等第一次刷新"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            InlineAction("编辑", onEdit)
        }
        if (quotes.isEmpty()) {
            if (loading) {
                LoadingRow("正在取行情…")
            } else {
                EmptyState(if (error != null) "行情没取到：$error" else "还没有自选行情")
            }
            if (error != null) InlineAction("重试", onRetry)
            return@CreamCard
        }
        PinnedMarketRow(
            symbols = pinnedSymbols,
            quotes = quotes,
            onOpen = onOpenSymbol,
            onPreview = onPreviewSymbol,
            modifier = Modifier.padding(top = 10.dp),
        )
        val restQuotes = quotes.filterNot { it.id in pinnedMarketSymbolIds(pinnedSymbols) }
        if (restQuotes.isNotEmpty()) {
            ThinDivider(Modifier.padding(top = 12.dp))
        }
        restQuotes.groupBy { it.categoryLabel() }
            .toList()
            .forEach { (category, categoryQuotes) ->
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                categoryQuotes.chunked(2).forEach { rowQuotes ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        rowQuotes.forEach { quote ->
                            QuoteTile(
                                quote = quote,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenSymbol(quote.id) },
                                onLongClick = { onPreviewSymbol(quote.id) },
                            )
                        }
                        if (rowQuotes.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        if (error != null) {
            ThinDivider(Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "部分行情没更新：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                InlineAction("重试", onRetry)
            }
        }
    }
}

@Composable
fun PinnedMarketRow(
    symbols: List<MarketSymbol>,
    quotes: List<MarketQuote>,
    onOpen: (String) -> Unit,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinned = symbols.mapNotNull { symbol ->
        quotes.firstOrNull { it.id == symbol.id }
    }
    if (pinned.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        pinned.forEach { quote ->
            QuoteTile(
                quote = quote,
                modifier = Modifier.width(112.dp),
                onClick = { onOpen(quote.id) },
                onLongClick = { onPreview(quote.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuoteTile(
    quote: MarketQuote,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val percent = quote.changePercent
    val tint = marketChangeColor(percent)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 2.dp),
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
                Spacer(Modifier.width(4.dp))
                Text(
                    text = quote.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = quote.price?.let { priceFormat.format(it) } ?: "—",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = percent?.let {
                (if (quote.previousSession) "昨收 " else "") + String.format(Locale.US, "%+.2f%%", it)
            } ?: "暂无",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(top = 1.dp),
        )
        if (quote.afterPrice != null) {
            Text(
                text = buildString {
                    append(quote.afterLabel)
                    append(' ')
                    append(priceFormat.format(quote.afterPrice))
                    quote.afterChangePercent?.let { append(String.format(Locale.US, " %+.2f%%", it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = marketChangeColor(quote.afterChangePercent),
                maxLines = 1,
            )
        }
    }
}

/** 编辑弹窗：上面管已添加，下面按名称 / 代码搜索新标的。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketEditorSheet(
    symbols: List<MarketSymbol>,
    pinnedSymbols: List<MarketSymbol>,
    onAdd: (MarketSymbol) -> Unit,
    onRemove: (MarketSymbol) -> Unit,
    onSaveSymbols: (List<MarketSymbol>, Boolean) -> Unit,
    onSavePinned: (List<MarketSymbol>, Boolean) -> Unit,
    onReset: () -> Unit,
    onSearch: suspend (String) -> List<MarketSearchResult>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MarketSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var categoryTarget by remember { mutableStateOf<MarketSymbol?>(null) }
    var categoryText by remember { mutableStateOf("") }
    val pinnedIds = remember(pinnedSymbols) { pinnedMarketSymbolIds(pinnedSymbols) }
    val editable = remember(symbols, pinnedIds) { symbols.filterNot { it.id in pinnedIds } }
    val addedIds = remember(symbols, pinnedIds) { (symbols.map { it.id } + pinnedIds).toSet() }

    val movePinned: (Int, Int) -> Unit = { from, to ->
        if (from in pinnedSymbols.indices && to in pinnedSymbols.indices) {
            val list = pinnedSymbols.toMutableList()
            val item = list.removeAt(from)
            list.add(to, item)
            onSavePinned(list, false)
        }
    }
    val moveWatchlist: (MarketSymbol, Int) -> Unit = { symbol, delta ->
        val base = editable.toMutableList()
        val index = base.indexOfFirst { it.id == symbol.id }
        val target = index + delta
        if (index >= 0 && target in base.indices) {
            val item = base.removeAt(index)
            base.add(target, item)
            onSaveSymbols(base, false)
        }
    }

    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        // 输入时别每敲一个字就打一次接口
        delay(350)
        results = onSearch(keyword)
        searching = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "编辑行情",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("恢复默认", onReset)
                InlineAction("完成", onDismiss)
            }
            Text(
                text = "置顶一行和自选都可以排序；分类留空时按地区显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                item { Text("添加自选", style = MaterialTheme.typography.titleSmall) }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        placeholder = {
                            Text("名称或代码，如 苹果 / AAPL / 沪深300", maxLines = 1)
                        },
                    )
                }
                if (searching) {
                    item { LoadingRow("正在搜索…") }
                }
                items(results, key = { "search:${it.symbol.id}" }) { result ->
                    val isPinned = result.symbol.id in pinnedIds
                    val added = isPinned || result.symbol.id in addedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !added) {
                                onAdd(result.symbol)
                                query = ""
                                results = emptyList()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = result.symbol.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOf(result.market, result.symbol.code)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = when {
                                isPinned -> "已置顶"
                                added -> "已添加"
                                else -> "添加"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (added) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                if (!searching && query.isNotBlank() && results.isEmpty()) {
                    item {
                        Text(
                            text = "没找到，换个名称或代码试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                item { ThinDivider(Modifier.padding(vertical = 10.dp)) }
                item { Text("置顶一行（可排序）", style = MaterialTheme.typography.titleSmall) }
                if (pinnedSymbols.isEmpty()) {
                    item {
                        Text(
                            text = "置顶行为空，可从自选点「置顶」加回来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                itemsIndexed(pinnedSymbols, key = { _, symbol -> "pin:${symbol.id}" }) { index, symbol ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(symbol.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${marketRegionLabel(symbol.regionKey())} · ${symbol.code}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        InlineAction("↑", { movePinned(index, index - 1) }, enabled = index > 0)
                        InlineAction(
                            "↓",
                            { movePinned(index, index + 1) },
                            enabled = index < pinnedSymbols.lastIndex,
                        )
                        InlineAction("移出", { onSavePinned(pinnedSymbols - symbol, true) }, color = Danger)
                    }
                }
                item {
                    ThinDivider(Modifier.padding(vertical = 10.dp))
                    Text("自选（可排序、分类）", style = MaterialTheme.typography.titleSmall)
                }
                if (editable.isEmpty()) {
                    item { EmptyState("还没有自选，在下面搜索添加") }
                }
                itemsIndexed(editable, key = { _, symbol -> "own:${symbol.id}" }) { index, symbol ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = symbol.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            InlineAction("↑", { moveWatchlist(symbol, -1) }, enabled = index > 0)
                            InlineAction(
                                "↓",
                                { moveWatchlist(symbol, 1) },
                                enabled = index < editable.lastIndex,
                            )
                            InlineAction("移除", { onRemove(symbol) }, color = Danger)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = listOf(
                                    if (symbol.category.isBlank()) {
                                        "按地区：${marketRegionLabel(symbol.regionKey())}"
                                    } else {
                                        "分类：${symbol.category}"
                                    },
                                    symbol.code,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            InlineAction("分类", {
                                categoryTarget = symbol
                                categoryText = symbol.category
                            })
                            InlineAction("置顶", { onSavePinned(pinnedSymbols + symbol, true) })
                        }
                    }
                }
                item { Spacer(Modifier.heightIn(min = 12.dp)) }
            }
        }
    }

    categoryTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { categoryTarget = null },
            title = { Text("设置分类") },
            text = {
                Column {
                    OutlinedTextField(
                        value = categoryText,
                        onValueChange = { categoryText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("留空则按地区显示") },
                    )
                    Text(
                        text = "例如：科技、基金、观察。置顶一行不参与分类。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveSymbols(
                            symbols.map { item ->
                                if (item.id == target.id) {
                                    item.copy(category = categoryText.trim())
                                } else {
                                    item
                                }
                            },
                            false,
                        )
                        categoryTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { categoryTarget = null }) { Text("取消") }
            },
        )
    }
}

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
