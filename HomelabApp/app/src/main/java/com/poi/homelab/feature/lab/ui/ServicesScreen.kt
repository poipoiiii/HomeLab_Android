package com.poi.homelab.feature.lab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.containerTone
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.StatusDot
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.core.util.stateLabel
import com.poi.homelab.feature.homarr.resolveHomarrUrl
import com.poi.homelab.feature.homarr.stringValue
import com.poi.homelab.feature.homarr.ui.InlinePage
import com.poi.homelab.feature.lab.ContainerInfo
import com.poi.homelab.navigation.UiState

private val FILTERS = listOf(
    "all" to "全部",
    "running" to "运行中",
    "stopped" to "已停止",
    "attention" to "需关注",
)

@Composable
fun ServicesScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onAction: (String, String) -> Unit,
    onLogs: (String) -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var powerOpen by rememberSaveable { mutableStateOf(false) }

    // Homarr 里配置的电源 iframe：从看板挪到服务页，避免首页看板被大块 iframe 占满
    val powerIframe = remember(state.board, state.settings.homarrUrl) {
        state.board?.items?.firstOrNull { item ->
            item.kind == "iframe" && (
                item.id.contains("power", ignoreCase = true) ||
                    item.advancedOptions?.stringValue("title")?.contains("电源") == true ||
                    item.options.stringValue("embedUrl")?.contains("power", ignoreCase = true) == true
                )
        }
    }
    val powerUrl = remember(powerIframe, state.settings.homarrUrl) {
        powerIframe?.let {
            resolveHomarrUrl(state.settings.homarrUrl, it.options.stringValue("embedUrl"))
        }
    }

    BackHandler(enabled = powerOpen && powerUrl != null) { powerOpen = false }

    val health = state.health
    val attentionNames = health?.attention?.map { it.name }?.toSet().orEmpty()
    val containers = health?.containers.orEmpty()
        .asSequence()
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .filter {
            when (filter) {
                "running" -> it.state == "running"
                "stopped" -> it.state != "running"
                "attention" -> it.name in attentionNames || it.errorCount > 0 ||
                    it.health == "unhealthy" || it.state in setOf("restarting", "dead", "paused")
                else -> true
            }
        }
        .sortedBy { it.name }
        .toList()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader(
                    title = "服务",
                    subtitle = health?.let {
                        "${it.summary.running}/${it.summary.total} 运行中 · ${it.summary.attention} 需关注"
                    } ?: "Lab Tools 容器",
                    action = { InlineAction("刷新", onRefresh) },
                )
            }

            powerUrl?.let { url ->
                item {
                    PowerPanelEntry(url = url, onOpen = { powerOpen = true })
                }
            }

            if (state.settings.labUrl.isBlank()) {
                item {
                    CreamCard {
                        Text("还没有配置 Lab Tools", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "到设置里填写 MCP 地址，例如 http://100.x.x.x:8765。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索容器名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FILTERS.forEach { (key, label) ->
                        Pill(label, filter == key, onClick = { filter = key })
                    }
                }
            }

            when {
                state.loadingHealth && health == null -> item { LoadingRow("正在读取容器…") }
                health == null -> item {
                    CreamCard {
                        EmptyState("没有容器数据")
                        InlineAction("重试", onRefresh)
                    }
                }
                containers.isEmpty() -> item {
                    CreamCard { EmptyState("没有匹配的容器") }
                }
                else -> items(containers, key = { it.name }) { container ->
                    ServiceCard(
                        container = container,
                        actionBusy = state.actionBusy,
                        onAction = onAction,
                        onLogs = onLogs,
                    )
                }
            }
        }
        powerUrl?.let { url ->
            AnimatedVisibility(
                visible = powerOpen,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { powerOpen = false },
                )
            }
            AnimatedVisibility(
                visible = powerOpen,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(tween(260)) { it },
                exit = slideOutHorizontally(tween(220)) { it },
            ) {
                PowerPanel(
                    url = url,
                    onClose = { powerOpen = false },
                    onOpenFull = {
                        powerOpen = false
                        onOpenLink(url)
                    },
                )
            }
        }
    }
}

/** 服务页里的电源面板入口：直接嵌入 Homarr 里配置的 iframe 地址。 */
@Composable
private fun PowerPanelEntry(url: String, onOpen: () -> Unit) {
    CreamCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("电源面板", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "容器启停 · 内存与磁盘 · " +
                        url.substringAfter("://").substringBefore('/').substringBefore('?'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "打开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** 右侧电源面板：直接嵌入 Homarr 里配置的 iframe 地址。 */
@Composable
private fun PowerPanel(
    url: String,
    onClose: () -> Unit,
    onOpenFull: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.92f),
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("电源面板", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = url.substringAfter("://").substringBefore('/').substringBefore('?'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InlineAction("全屏", onOpenFull)
                InlineAction("关闭", onClose)
            }
            ThinDivider()
            InlinePage(
                url = url,
                modifier = Modifier.fillMaxSize(),
                focusable = true,
            )
        }
    }
}

@Composable
private fun ServiceCard(
    container: ContainerInfo,
    actionBusy: Boolean,
    onAction: (String, String) -> Unit,
    onLogs: (String) -> Unit,
) {
    val tone = containerTone(container.state, container.health)
    CreamCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tone)
            Text(
                text = container.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            Tag(
                text = if (container.health == "unhealthy") "unhealthy" else stateLabel(container.state),
                color = tone,
            )
        }

        if (container.ports.isNotEmpty()) {
            Text(
                text = container.ports.take(2).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (container.errorCount > 0) {
            Text(
                text = "${container.errorCount} 条错误日志",
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        ThinDivider(Modifier.padding(vertical = 10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (container.state == "running") {
                InlineAction(
                    "停止",
                    onClick = { onAction(container.name, "stop") },
                    enabled = !actionBusy,
                    color = Danger,
                )
                InlineAction(
                    "重启",
                    onClick = { onAction(container.name, "restart") },
                    enabled = !actionBusy,
                )
            } else {
                InlineAction(
                    "启动",
                    onClick = { onAction(container.name, "start") },
                    enabled = !actionBusy,
                    color = SuccessStrong,
                )
            }
            Spacer(Modifier.weight(1f))
            InlineAction("日志", onClick = { onLogs(container.name) })
        }
    }
}
