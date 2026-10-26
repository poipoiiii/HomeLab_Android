package com.rong.homelab.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rong.homelab.R
import com.rong.homelab.data.AnalysisResponse
import com.rong.homelab.data.Insight
import com.rong.homelab.ui.components.InlineAction
import com.rong.homelab.ui.components.LoadingRow
import com.rong.homelab.ui.components.MonospaceBlock
import com.rong.homelab.ui.components.PrimaryButton
import com.rong.homelab.ui.components.ThinDivider
import com.rong.homelab.ui.screens.BillsScreen
import com.rong.homelab.ui.screens.DownloadsScreen
import com.rong.homelab.ui.screens.HomeScreen
import com.rong.homelab.ui.screens.ServicesScreen
import com.rong.homelab.ui.screens.SettingsScreen
import com.rong.homelab.ui.theme.Danger

private enum class Tab(
    val label: String,
    @DrawableRes val icon: Int,
) {
    Home("总览", R.drawable.ic_home),
    Services("服务", R.drawable.ic_server),
    Bills("账单", R.drawable.ic_receipt),
    Downloads("下载", R.drawable.ic_download),
    Settings("设置", R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomelabApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val tab = Tab.entries[tabIndex]

    LaunchedEffect(state.message) {
        val message = state.message
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                Tab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        icon = {
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = item.label,
                            )
                        },
                        label = {
                            Text(item.label, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen(
                    state = state,
                    onRefresh = viewModel::refreshAll,
                    onOpenSettings = { tabIndex = Tab.Settings.ordinal },
                    onOpenBills = { tabIndex = Tab.Bills.ordinal },
                    onOpenServices = { tabIndex = Tab.Services.ordinal },
                    onOpenDownloads = { tabIndex = Tab.Downloads.ordinal },
                )
                Tab.Services -> ServicesScreen(
                    state = state,
                    onRefresh = viewModel::refreshHealth,
                    onAction = viewModel::requestServiceAction,
                    onLogs = viewModel::loadLogs,
                )
                Tab.Bills -> BillsScreen(
                    state = state,
                    onPrevMonth = { viewModel.changeMonth(-1) },
                    onNextMonth = { viewModel.changeMonth(1) },
                    onRefresh = viewModel::refreshBills,
                    onAnalysis = viewModel::loadAnalysis,
                )
                Tab.Downloads -> DownloadsScreen(
                    state = state,
                    onSubmit = viewModel::submitDownload,
                )
                Tab.Settings -> SettingsScreen(
                    state = state,
                    onSave = viewModel::saveSettings,
                    onTest = viewModel::testConnections,
                    onTheme = viewModel::setThemeMode,
                )
            }
        }
    }

    state.pendingAction?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingAction,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(if (pending.action == "stop") "停止服务" else "重启服务")
            },
            text = {
                Text(
                    "将要执行：\n${pending.command}\n\n确认后才会真正执行，token 2 分钟内有效。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmPendingAction,
                    enabled = !state.actionBusy,
                ) {
                    Text("确认执行", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPendingAction) {
                    Text("取消")
                }
            },
        )
    }

    if (state.logsTitle != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeLogs,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${state.logsTitle} 日志",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction("关闭", viewModel::closeLogs)
                }
                if (state.logsLoading) {
                    LoadingRow("正在读取日志…")
                } else {
                    MonospaceBlock(
                        text = state.logs.orEmpty(),
                        maxHeight = 560,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }

    state.analysis?.let { analysis ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeAnalysis,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AnalysisSheet(analysis, viewModel::closeAnalysis)
        }
    }
}

@Composable
private fun AnalysisSheet(analysis: AnalysisResponse, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "AI 财务分析",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            InlineAction("关闭", onClose)
        }
        Text(
            text = if (analysis.cached) "90 天 · 缓存结果" else "90 天 · 刚生成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (analysis.analysis.summary.isNotBlank()) {
            Text(
                text = analysis.analysis.summary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        InsightSection("亮点", analysis.analysis.highlights)
        InsightSection("风险", analysis.analysis.risks)
        InsightSection("建议", analysis.analysis.suggestions)

        if (analysis.analysis.categoryNote.isNotBlank()) {
            ThinDivider(Modifier.padding(vertical = 14.dp))
            Text(
                text = analysis.analysis.categoryNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PrimaryButton(
            text = "关闭",
            onClick = onClose,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

@Composable
private fun InsightSection(title: String, items: List<Insight>) {
    if (items.isEmpty()) return
    ThinDivider(Modifier.padding(vertical = 14.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
    )
    items.forEach { insight ->
        Column(Modifier.padding(top = 10.dp)) {
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = insight.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
