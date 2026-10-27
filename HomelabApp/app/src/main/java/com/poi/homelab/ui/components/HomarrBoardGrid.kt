package com.poi.homelab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.poi.homelab.data.HomarrApp
import com.poi.homelab.data.HomarrBoard
import com.poi.homelab.data.HomarrItem
import com.poi.homelab.data.booleanValue
import com.poi.homelab.data.resolveHomarrUrl
import com.poi.homelab.data.stringValue
import com.poi.homelab.ui.theme.CardSurfaceAlpha
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

private val TileShape = RoundedCornerShape(16.dp)
private val TileGap = 6.dp

/**
 * 按 Homarr 的网格坐标渲染看板。
 *
 * 只原生还原 app / clock / iframe 三类；其他 widget 不猜数据，降级成「在 Homarr 中查看」。
 * 完全空白的行会被折叠，避免手机上一大片空行（Homarr 网页端会原样留白）。
 */
@Composable
fun HomarrBoardGrid(
    board: HomarrBoard,
    apps: List<HomarrApp>,
    baseUrl: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = board.layouts
            .filter { it.breakpoint <= maxWidth.value.toInt() }
            .maxByOrNull { it.breakpoint }
            ?: board.layouts.minByOrNull { it.breakpoint }
        if (layout == null) return@BoxWithConstraints

        val columns = layout.columnCount.coerceAtLeast(1)
        val cell = (maxWidth - TileGap * (columns - 1)) / columns

        val placed = board.items.mapNotNull { item ->
            val pos = item.layouts.firstOrNull { it.layoutId == layout.id } ?: return@mapNotNull null
            PlacedCell(
                item = item,
                column = pos.xOffset.coerceAtLeast(0),
                row = pos.yOffset.coerceAtLeast(0),
                width = pos.width.coerceAtLeast(1),
                height = pos.height.coerceAtLeast(1),
            )
        }

        val usedRows = placed
            .flatMap { tile -> (tile.row until tile.row + tile.height).toList() }
            .distinct()
            .sorted()
        if (usedRows.isEmpty()) return@BoxWithConstraints
        val rowIndex = usedRows.withIndex().associate { (index, row) -> row to index }

        val totalHeight = cell * usedRows.size + TileGap * (usedRows.size - 1)

        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            placed.forEach { cellItem ->
                val row = rowIndex[cellItem.row] ?: return@forEach
                Box(
                    Modifier
                        .offset(x = (cell + TileGap) * cellItem.column, y = (cell + TileGap) * row)
                        .size(
                            width = cell * cellItem.width + TileGap * (cellItem.width - 1),
                            height = cell * cellItem.height + TileGap * (cellItem.height - 1),
                        ),
                ) {
                    BoardItem(
                        item = cellItem.item,
                        apps = apps,
                        baseUrl = baseUrl,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

private data class PlacedCell(
    val item: HomarrItem,
    val column: Int,
    val row: Int,
    val width: Int,
    val height: Int,
)

@Composable
private fun BoardItem(
    item: HomarrItem,
    apps: List<HomarrApp>,
    baseUrl: String,
    onOpen: (String) -> Unit,
) {
    val title = item.advancedOptions?.stringValue("title")
    when (item.kind) {
        "app" -> {
            val app = apps.firstOrNull { it.id == item.options.stringValue("appId") }
            val href = resolveHomarrUrl(baseUrl, app?.href)
            Tile(
                enabled = href != null,
                onClick = { href?.let(onOpen) },
            ) {
                val showTitle = item.options.booleanValue("showTitle") == true
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (showTitle) Modifier.weight(1f) else Modifier.fillMaxSize()),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        name = app?.name ?: title ?: "应用",
                        iconUrl = app?.let { resolveHomarrUrl(baseUrl, it.iconUrl) },
                    )
                }
                if (showTitle) {
                    Text(
                        text = app?.name ?: title.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        "clock" -> ClockTile(item = item, title = title)

        "iframe" -> {
            val embedUrl = resolveHomarrUrl(baseUrl, item.options.stringValue("embedUrl"))
            Tile(enabled = embedUrl != null, onClick = { embedUrl?.let(onOpen) }) {
                TileCaption(title = title ?: "嵌入页面", subtitle = embedUrl?.hostLabel() ?: "—")
            }
        }

        else -> Tile(
            enabled = true,
            onClick = { onOpen(baseUrl) },
        ) {
            TileCaption(title = title ?: item.kind, subtitle = "在 Homarr 中查看")
        }
    }
}

@Composable
private fun ClockTile(item: HomarrItem, title: String?) {
    val is24Hour = item.options.booleanValue("is24HourFormat") != false
    val showDate = item.options.booleanValue("showDate") != false
    val timePattern = if (is24Hour) {
        item.options.stringValue("customTimeFormat")?.takeIf { it.isNotBlank() } ?: "HH:mm"
    } else {
        "hh:mm"
    }
    val datePattern = item.options.stringValue("dateFormat")
        ?.replace("YYYY", "yyyy")
        ?.replace("DD", "dd")
        ?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd"
    val timeFormat = remember(timePattern) { DateTimeFormatter.ofPattern(timePattern, Locale.getDefault()) }
    val dateFormat = remember(datePattern) { DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()) }

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }

    Tile(enabled = false, onClick = null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = now.format(timeFormat),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (showDate) {
                Text(
                    text = "${now.format(dateFormat)} ${now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AppIcon(name: String, iconUrl: String?) {
    if (iconUrl.isNullOrBlank()) {
        Monogram(name)
        return
    }
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(2.dp),
        loading = {},
        error = { Monogram(name) },
    )
}

@Composable
private fun Monogram(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = name.trim().take(1).ifBlank { "·" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TileCaption(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Tile(
    enabled: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = TileShape
    val color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick == null) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = color,
            border = border,
        ) {
            Column(Modifier.fillMaxSize().padding(6.dp), content = content)
        }
    } else {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = color,
            border = border,
        ) {
            Column(Modifier.fillMaxSize().padding(6.dp), content = content)
        }
    }
}

private fun String.hostLabel(): String {
    val withoutScheme = substringAfter("://", this)
    return withoutScheme.substringBefore('/').substringBefore('?')
}
