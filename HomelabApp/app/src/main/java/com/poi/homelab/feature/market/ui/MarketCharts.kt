package com.poi.homelab.feature.market.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.feature.market.IntradaySeries
import com.poi.homelab.feature.market.MarketSymbol
import com.poi.homelab.feature.market.PeriodReturn
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** 分时柱状图：以昨收为中线，红涨绿跌。 */
@Composable
fun IntradayBarChart(
    series: IntradaySeries,
    modifier: Modifier = Modifier,
    barHeight: Int = 140,
) {
    val up = Danger
    val down = Success
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val base = series.preClose.takeIf { it > 0.0 }
    val values = remember(series, base) {
        base?.let { pre ->
            series.points.map { (it.price - pre) / pre * 100 }
        }.orEmpty()
    }
    if (values.isEmpty()) {
        EmptyState("还没有分时数据", modifier)
        return
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight.dp),
    ) {
        val center = size.height / 2f
        val maxAbs = max(0.05f, values.maxOf { abs(it).toFloat() })
        val slot = size.width / values.size
        val barWidth = max(1f, slot * 0.72f)
        drawLine(
            color = baselineColor,
            start = Offset(0f, center),
            end = Offset(size.width, center),
            strokeWidth = 1f,
        )
        values.forEachIndexed { index, value ->
            val height = (abs(value).toFloat() / maxAbs) * (center - 4f)
            if (height <= 0f) return@forEachIndexed
            val color = if (value >= 0) up else down
            val top = if (value >= 0) center - height else center
            drawRect(
                color = color,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, top),
                size = Size(barWidth, height),
            )
        }
    }
}

/** 历史区间柱状图，点柱子可以切换显示的日期和涨跌幅。 */
@Composable
fun PeriodBarChart(
    points: List<PeriodReturn>,
    modifier: Modifier = Modifier,
    barHeight: Int = 140,
) {
    if (points.isEmpty()) {
        EmptyState("还没有足够的历史数据", modifier)
        return
    }
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val current = points[selected.coerceIn(0, points.lastIndex)]
    val up = Danger
    val down = Success
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.primary

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = current.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = String.format(Locale.US, "%+.2f%%", current.percent),
                style = MaterialTheme.typography.titleSmall,
                color = marketChangeColor(current.percent),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / points.size
                        selected = (offset.x / slot).toInt().coerceIn(0, points.lastIndex)
                    }
                },
        ) {
            val center = size.height / 2f
            val maxAbs = max(0.5f, points.maxOf { abs(it.percent).toFloat() })
            val slot = size.width / points.size
            val barWidth = max(2f, slot * 0.62f)
            drawLine(
                color = baselineColor,
                start = Offset(0f, center),
                end = Offset(size.width, center),
                strokeWidth = 1f,
            )
            points.forEachIndexed { index, point ->
                val height = (abs(point.percent).toFloat() / maxAbs) * (center - 6f)
                if (height <= 0f) return@forEachIndexed
                val color = if (point.percent >= 0) up else down
                val top = if (point.percent >= 0) center - height else center
                drawRect(
                    color = color,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, top),
                    size = Size(barWidth, height),
                )
                if (index == selected) {
                    drawRect(
                        color = selectedColor,
                        topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - 3f),
                        size = Size(barWidth, 2f),
                    )
                }
            }
        }
    }
}

/** 长按行情卡片弹出的分时预览。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketIntradaySheet(
    symbol: MarketSymbol?,
    series: IntradaySeries?,
    loading: Boolean,
    error: String?,
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
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = symbol?.label ?: "分时",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "今日分时 · 长按卡片快速查看",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            ThinDivider(Modifier.padding(vertical = 12.dp))
            when {
                loading -> LoadingRow("正在取分时…")
                error != null -> Text(
                    text = "分时没取到：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                series == null || series.points.isEmpty() -> EmptyState("今天还没有分时数据")
                else -> {
                    val last = series.points.last()
                    val percent = if (series.preClose > 0.0) {
                        (last.price - series.preClose) / series.preClose * 100
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
                    IntradayBarChart(series, Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            text = series.points.firstOrNull()?.time.orEmpty(),
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
}
