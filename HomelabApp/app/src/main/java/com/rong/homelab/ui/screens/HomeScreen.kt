package com.rong.homelab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rong.homelab.ui.UiState
import com.rong.homelab.ui.components.CreamCard
import com.rong.homelab.ui.components.DarkCard
import com.rong.homelab.ui.components.DarkStat
import com.rong.homelab.ui.components.EmptyState
import com.rong.homelab.ui.components.InlineAction
import com.rong.homelab.ui.components.LoadingRow
import com.rong.homelab.ui.components.PrimaryButton
import com.rong.homelab.ui.components.SectionHeader
import com.rong.homelab.ui.components.StatBlock
import com.rong.homelab.ui.components.ThinDivider
import com.rong.homelab.ui.components.TransactionRow
import com.rong.homelab.ui.theme.OnDark
import com.rong.homelab.ui.theme.OnDarkSoft
import com.rong.homelab.ui.theme.SuccessStrong
import com.rong.homelab.util.money
import com.rong.homelab.util.stateLabel
import com.rong.homelab.util.todayLabel

@Composable
fun HomeScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val settings = state.settings
    val needsSetup = settings.labUrl.isBlank() && settings.billUrl.isBlank()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "poi的实验室",
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

        item {
            SectionHeader(
                title = "实验室",
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

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard("下载视频", "贴链接或片名", Modifier.weight(1f), onOpenDownloads)
                QuickCard("全部服务", "容器启停与日志", Modifier.weight(1f), onOpenServices)
            }
        }

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

@Composable
private fun QuickCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CreamCard(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        InlineAction("打开", onClick)
    }
}
