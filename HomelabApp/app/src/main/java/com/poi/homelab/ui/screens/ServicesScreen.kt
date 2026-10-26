package com.poi.homelab.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poi.homelab.data.ContainerInfo
import com.poi.homelab.ui.UiState
import com.poi.homelab.ui.components.CreamCard
import com.poi.homelab.ui.components.EmptyState
import com.poi.homelab.ui.components.InlineAction
import com.poi.homelab.ui.components.LoadingRow
import com.poi.homelab.ui.components.Pill
import com.poi.homelab.ui.components.SectionHeader
import com.poi.homelab.ui.components.StatusDot
import com.poi.homelab.ui.components.Tag
import com.poi.homelab.ui.components.ThinDivider
import com.poi.homelab.ui.components.containerTone
import com.poi.homelab.ui.theme.Danger
import com.poi.homelab.ui.theme.SuccessStrong
import com.poi.homelab.util.stateLabel

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
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }

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
