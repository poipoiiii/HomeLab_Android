package com.rong.homelab.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rong.homelab.data.BillAccount
import com.rong.homelab.ui.UiState
import com.rong.homelab.ui.components.CategoryBar
import com.rong.homelab.ui.components.CreamCard
import com.rong.homelab.ui.components.DarkCard
import com.rong.homelab.ui.components.DarkStat
import com.rong.homelab.ui.components.EmptyState
import com.rong.homelab.ui.components.InlineAction
import com.rong.homelab.ui.components.LoadingRow
import com.rong.homelab.ui.components.PrimaryButton
import com.rong.homelab.ui.components.SectionHeader
import com.rong.homelab.ui.components.TransactionRow
import com.rong.homelab.ui.theme.OnDark
import com.rong.homelab.ui.theme.OnDarkSoft
import com.rong.homelab.ui.theme.SuccessStrong
import com.rong.homelab.util.money
import com.rong.homelab.util.monthLabel
import com.rong.homelab.util.plainAmount

@Composable
fun BillsScreen(
    state: UiState,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRefresh: () -> Unit,
    onAnalysis: () -> Unit,
) {
    val overview = state.overview
    val maxCategory = overview?.byCategory?.maxOfOrNull { plainAmount(it.amount) } ?: 0.0

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineAction("‹", onPrevMonth)
                Text(
                    text = monthLabel(state.billMonth),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                InlineAction("›", onNextMonth)
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
                    items(state.transactions, key = { it.id }) { txn ->
                        CreamCard {
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
