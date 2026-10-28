package com.poi.homelab.feature.lab.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InertiaWheel
import com.poi.homelab.core.ui.components.MonospaceBlock
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.navigation.UiState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val TARGETS = listOf(
    "anime" to "动漫",
    "movies" to "电影",
    "game_video" to "游戏",
    "yt_video" to "YouTube",
)

private val SOURCES = listOf(
    "bilibili" to "B 站",
    "youtube" to "YouTube",
)

private val PrettyJson = Json { prettyPrint = true }

@Composable
fun DownloadsScreen(
    state: UiState,
    onSubmit: (url: String, query: String, target: String, source: String, series: String, limit: Int, items: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf("") }
    var items by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(0) }
    var target by rememberSaveable { mutableStateOf("anime") }
    var source by rememberSaveable { mutableStateOf("bilibili") }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(title = "下载", subtitle = "交给 MeTube / yt-dlp 执行")
        }

        if (state.settings.labUrl.isBlank()) {
            item {
                CreamCard {
                    Text("还没有配置 Lab Tools", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "下载工具由 lab-tools 转发给 MeTube，先到设置里填写 MCP 地址。",
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
                Text("链接 / 分享口令", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("https://… 或「7.43 复制打开抖音…」") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    "或者只填片名，让服务器去搜",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("例如：干物妹小埋") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Text(
                    "保存到",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TARGETS.forEach { (key, label) ->
                        Pill(label, target == key, onClick = { target = key })
                    }
                }

                Text(
                    "搜索源",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SOURCES.forEach { (key, label) ->
                        Pill(label, source == key, onClick = { source = key })
                    }
                }

                OutlinedTextField(
                    value = series,
                    onValueChange = { series = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    label = { Text("剧名（仅动漫，用于 Jellyfin 命名）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                OutlinedTextField(
                    value = items,
                    onValueChange = { items = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("集数") },
                    placeholder = { Text("10-13") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Text(
                    "数量限制",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "0 = 全部；上下拨动滚轮，松手自动吸附",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
                InertiaWheel(
                    items = (0..30).map { if (it == 0) "全部" else it.toString() },
                    selectedIndex = limit,
                    onSelected = { limit = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        item {
            PrimaryButton(
                text = "提交下载",
                onClick = {
                    onSubmit(
                        url.trim(),
                        query.trim(),
                        target,
                        source,
                        series.trim(),
                        limit,
                        items.trim(),
                    )
                },
                loading = state.downloadBusy,
            )
        }

        val result = state.downloadResult
        if (result != null) {
            item {
                SectionHeader(
                    title = "任务结果",
                    subtitle = if (result["queued"]?.toString() == "true") "已进入队列" else "服务器返回",
                )
            }
            item {
                MonospaceBlock(
                    text = formatResult(result),
                    maxHeight = 360,
                )
            }
        }

        item {
            Text(
                "下载目标只允许动漫、电影、游戏、YouTube 四个目录；番剧会按 Jellyfin 结构落盘。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatResult(result: JsonObject): String =
    runCatching { PrettyJson.encodeToString(result) }.getOrElse { result.toString() }
