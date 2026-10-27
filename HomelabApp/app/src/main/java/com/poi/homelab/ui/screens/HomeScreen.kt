package com.poi.homelab.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.poi.homelab.R
import com.poi.homelab.data.TodoItem
import com.poi.homelab.ui.UiState
import com.poi.homelab.ui.components.ChecklistItem
import com.poi.homelab.ui.components.CreamCard
import com.poi.homelab.ui.components.DailyChecklistCard
import com.poi.homelab.ui.components.DarkCard
import com.poi.homelab.ui.components.DarkStat
import com.poi.homelab.ui.components.EmptyState
import com.poi.homelab.ui.components.HomarrBoardGrid
import com.poi.homelab.ui.components.InlineAction
import com.poi.homelab.ui.components.LoadingRow
import com.poi.homelab.ui.components.PrimaryButton
import com.poi.homelab.ui.components.QuickEntryPager
import com.poi.homelab.ui.components.SectionHeader
import com.poi.homelab.ui.components.StackCard
import com.poi.homelab.ui.components.StatBlock
import com.poi.homelab.ui.components.ThinDivider
import com.poi.homelab.ui.components.TodoEditorSheet
import com.poi.homelab.ui.components.TransactionRow
import com.poi.homelab.ui.theme.OnDark
import com.poi.homelab.ui.theme.OnDarkSoft
import com.poi.homelab.ui.theme.SuccessStrong
import com.poi.homelab.util.money
import com.poi.homelab.util.stateLabel
import com.poi.homelab.util.todayLabel
import java.net.URI

@Composable
fun HomeScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenDownloads: () -> Unit,
    onToggleChecklist: (String) -> Unit,
    onSaveTodos: (List<TodoItem>) -> Unit,
) {
    val settings = state.settings
    val context = LocalContext.current
    val needsSetup = settings.labUrl.isBlank() && settings.billUrl.isBlank()
    val todoItems = state.todos.map { ChecklistItem(it.id, it.label) }
    var todoEditorOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.app_avatar),
                    contentDescription = "应用头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "poi's home",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = todayLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                InlineAction("刷新", onRefresh)
            }
        }

        item {
            DailyChecklistCard(
                items = todoItems,
                checked = state.checkedToday,
                onToggle = onToggleChecklist,
                onEdit = { todoEditorOpen = true },
            )
        }

        if (needsSetup) {
            item {
                CreamCard {
                    Text("先连上你的实验室", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "填写 Lab Tools 和账本地址后，这里会显示容器状态、本月收支和最近流水。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                    PrimaryButton("去设置", onOpenSettings)
                }
            }
        }

        if (settings.showLedgerOnHome) {
        item {
            SectionHeader(
                title = "本月账本",
                subtitle = if (settings.billUrl.isBlank()) "账本地址未配置" else null,
                action = {
                    if (settings.billUrl.isNotBlank()) {
                        InlineAction("明细", onOpenBills)
                    }
                },
            )
        }
        item {
            val overview = state.overview
            when {
                settings.billUrl.isBlank() -> CreamCard {
                    Text(
                        "还没有连接「poi的账单」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.loadingBills && overview == null -> CreamCard { LoadingRow("正在读取账本…") }
                overview == null -> CreamCard {
                    EmptyState("账本没有返回数据")
                    InlineAction("重试", onRefresh)
                }
                else -> DarkCard {
                    Text("净资产", style = MaterialTheme.typography.labelMedium, color = OnDarkSoft)
                    Text(
                        money(overview.netWorth),
                        style = MaterialTheme.typography.displayMedium,
                        color = OnDark,
                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                    )
                    HorizontalDivider(color = Color(0xFF3A3733))
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DarkStat(
                            "本月支出",
                            money(overview.monthStats.expense),
                            Modifier.weight(1f),
                            Color(0xFFE7A48C),
                        )
                        DarkStat(
                            "本月收入",
                            money(overview.monthStats.income),
                            Modifier.weight(1f),
                            Color(0xFF8FD9A3),
                        )
                        DarkStat("结余", money(overview.monthStats.net), Modifier.weight(1f))
                    }
                }
            }
        }
        }

        item {
            SectionHeader(
                title = "Home",
                subtitle = state.health?.summary?.checkedAt?.replace("T", " "),
                action = {
                    if (settings.labUrl.isNotBlank()) {
                        InlineAction("服务", onOpenServices)
                    }
                },
            )
        }
        item {
            val health = state.health
            when {
                settings.labUrl.isBlank() -> CreamCard {
                    Text(
                        "还没有连接 Lab Tools，容器状态和磁盘占用不会显示。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.loadingHealth && health == null -> CreamCard { LoadingRow("正在读取容器状态…") }
                health == null -> CreamCard {
                    EmptyState("Lab Tools 没有返回数据")
                    InlineAction("重试", onRefresh)
                }
                else -> CreamCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatBlock(
                            "运行中",
                            "${health.summary.running}/${health.summary.total}",
                            Modifier.weight(1f),
                            SuccessStrong,
                        )
                        StatBlock(
                            "需关注",
                            health.summary.attention.toString(),
                            Modifier.weight(1f),
                            if (health.summary.attention > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        StatBlock(
                            "可回收",
                            health.disk.totalReclaimable.ifBlank { "—" },
                            Modifier.weight(1f),
                        )
                    }
                    if (health.attention.isNotEmpty()) {
                        ThinDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            "需要关注",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        health.attention.take(4).forEach { item ->
                            Text(
                                "· ${item.name}  ${stateLabel(item.state)}" +
                                    if (item.errorCount > 0) "  ${item.errorCount} 条错误日志" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (settings.homarrUrl.isNotBlank()) {
            item {
                SectionHeader(
                    title = state.board?.name?.takeIf { it.isNotBlank() } ?: "看板",
                    subtitle = if (state.loadingBoard && state.board == null) "正在同步 Homarr" else "来自 Homarr",
                    action = { InlineAction("刷新", onRefresh) },
                )
            }
            item {
                val board = state.board
                val boardError = state.boardError
                when {
                    board != null -> HomarrBoardGrid(
                        board = board,
                        apps = state.boardApps,
                        baseUrl = settings.homarrUrl,
                        onOpen = { url ->
                            // 账本磁贴直接进原生账单页，其余交给浏览器
                            if (settings.billUrl.isNotBlank() && sameOrigin(url, settings.billUrl)) {
                                onOpenBills()
                            } else {
                                openInBrowser(context, url)
                            }
                        },
                    )
                    state.loadingBoard -> CreamCard { LoadingRow("正在读取 Homarr 看板…") }
                    boardError != null -> CreamCard {
                        Text("看板没加载出来", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = boardError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        InlineAction("重试", onRefresh)
                    }
                    else -> CreamCard { EmptyState("看板没有返回数据") }
                }
            }
        } else {
            item {
                SectionHeader(
                    title = "快捷入口",
                    subtitle = "左右滑动切换，点卡片进入",
                )
            }
            item {
                QuickEntryPager(
                    cards = listOf(
                        StackCard("账本明细", "本月流水与分类"),
                        StackCard("下载队列", "提交 MeTube 任务"),
                        StackCard("实验室服务", "容器启停与日志"),
                    ),
                    onCardClick = { index ->
                        when (index) {
                            0 -> onOpenBills()
                            1 -> onOpenDownloads()
                            else -> onOpenServices()
                        }
                    },
                )
            }
        }

        if (settings.showLedgerOnHome) {
            val overview = state.overview
            if (overview != null && overview.recent.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "最近流水",
                        action = { InlineAction("全部", onOpenBills) },
                    )
                }
                item {
                    CreamCard {
                        overview.recent.take(5).forEachIndexed { index, txn ->
                            TransactionRow(txn)
                            if (index != overview.recent.take(5).lastIndex) ThinDivider()
                        }
                    }
                }
            }
        }
    }
    if (todoEditorOpen) {
        TodoEditorSheet(
            items = state.todos,
            onDismiss = { todoEditorOpen = false },
            onSave = onSaveTodos,
        )
    }
    }
}

private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** 用 scheme + host + port 比较，判断看板磁贴和已配置服务是不是同一个入口。 */
private fun sameOrigin(first: String, second: String): Boolean {
    val a = originOf(first) ?: return false
    val b = originOf(second) ?: return false
    return a == b
}

private fun originOf(value: String): String? = runCatching {
    val uri = URI(value.trim())
    val host = uri.host ?: return@runCatching null
    val port = if (uri.port == -1) {
        if (uri.scheme.equals("https", true)) 443 else 80
    } else {
        uri.port
    }
    "${uri.scheme?.lowercase()}://${host.lowercase()}:$port"
}.getOrNull()
