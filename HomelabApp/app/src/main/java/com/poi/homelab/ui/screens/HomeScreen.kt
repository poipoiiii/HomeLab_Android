package com.poi.homelab.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.R
import com.poi.homelab.data.AppSettings
import com.poi.homelab.data.HealthSnapshot
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
import com.poi.homelab.ui.components.ThinDivider
import com.poi.homelab.ui.components.TodoEditorSheet
import com.poi.homelab.ui.components.TransactionRow
import com.poi.homelab.ui.theme.OnDark
import com.poi.homelab.ui.theme.OnDarkSoft
import com.poi.homelab.util.money
import com.poi.homelab.util.todayLabel
import java.net.URI
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLink: (String) -> Unit,
    onToggleChecklist: (String) -> Unit,
    onSaveTodos: (List<TodoItem>) -> Unit,
) {
    val settings = state.settings
    val needsSetup = settings.labUrl.isBlank() && settings.billUrl.isBlank()
    val todoItems = state.todos.map { ChecklistItem(it.id, it.label) }
    var todoEditorOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val doneCount = todoItems.count { it.id in state.checkedToday }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            // 待办收进左侧抽屉：左缘右滑或点标题栏的「清单」拉出
            ModalDrawerSheet(
                modifier = Modifier.width(336.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "清单",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        InlineAction(
                            text = "收起",
                            onClick = { scope.launch { drawerState.close() } },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    DailyChecklistCard(
                        items = todoItems,
                        checked = state.checkedToday,
                        onToggle = onToggleChecklist,
                        onEdit = { todoEditorOpen = true },
                    )
                    Text(
                        text = "点左侧的「清单」标签，或把它往右拖，就能随时拉出这份清单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
            }
        },
    ) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HomeHeader(
                    health = state.health,
                    todoLabel = "清单 $doneCount/${todoItems.size}",
                    onOpenTodo = { scope.launch { drawerState.open() } },
                    onRefresh = onRefresh,
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

            if (settings.homarrUrl.isNotBlank()) {
                item {
                    when {
                        state.board != null -> HomarrBoardGrid(
                            board = state.board,
                            apps = state.boardApps,
                            baseUrl = settings.homarrUrl,
                            status = state.appStatus,
                            onOpen = { url ->
                                // 账本磁贴直接进原生账单页，其余走应用内浏览器
                                if (settings.billUrl.isNotBlank() && sameOrigin(url, settings.billUrl)) {
                                    onOpenBills()
                                } else {
                                    onOpenLink(url)
                                }
                            },
                        )
                        state.loadingBoard -> CreamCard { LoadingRow("正在读取 Homarr 看板…") }
                        state.boardError != null -> CreamCard {
                            Text("看板没有加载出来", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = state.boardError,
                                style = MaterialTheme.typography.bodySmall,
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

            if (settings.billUrl.isNotBlank() || settings.labUrl.isNotBlank()) {
                item {
                    LabGlanceCard(
                        state = state,
                        settings = settings,
                        onOpenBills = onOpenBills,
                        onOpenServices = onOpenServices,
                        onOpenSettings = onOpenSettings,
                        onRefresh = onRefresh,
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
        if (drawerState.currentValue == DrawerValue.Closed) {
            TodoTab(
                done = doneCount,
                total = todoItems.size,
                onOpen = { scope.launch { drawerState.open() } },
                modifier = Modifier.align(Alignment.CenterStart),
            )
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
}

/** 左缘的「清单」标签：点一下或往右拖都能拉出待办抽屉。 */
@Composable
private fun TodoTab(
    done: Int,
    total: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragged by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier = modifier
            .width(26.dp)
            .height(96.dp)
            .pointerInput(onOpen) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { dragged = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                        if (dragged > 24.dp.toPx()) {
                            dragged = 0f
                            onOpen()
                        }
                    },
                )
            }
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "清",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "单",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$done/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HomeHeader(
    health: HealthSnapshot?,
    todoLabel: String,
    onOpenTodo: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.app_avatar),
            contentDescription = "应用头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
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
            Row(Modifier.padding(top = 2.dp)) {
                Text(
                    text = todayLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                health?.let {
                    Text(
                        text = " · ${it.summary.running}/${it.summary.total} 运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        InlineAction(todoLabel, onOpenTodo)
        InlineAction("刷新", onRefresh)
    }
}

/**
 * 账本 + 容器合成一块：只保留能看懂的数字和失败原因，
 * 不再为「没配置 / 没数据」单独占一整张卡。
 */
@Composable
private fun LabGlanceCard(
    state: UiState,
    settings: AppSettings,
    onOpenBills: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val overview = state.overview
    val health = state.health
    val showLedger = settings.billUrl.isNotBlank() && settings.showLedgerOnHome

    DarkCard {
        if (showLedger) {
            when {
                overview != null -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                state.loadingBills -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "正在读取账本…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction("明细", onOpenBills)
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "账本没有返回数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction("重试", onRefresh)
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color(0xFF3A3733))
            Spacer(Modifier.height(14.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("容器", style = MaterialTheme.typography.labelMedium, color = OnDarkSoft)
                Text(
                    text = when {
                        settings.labUrl.isBlank() -> "未连接 Lab Tools"
                        health != null -> buildString {
                            append("${health.summary.running}/${health.summary.total} 运行")
                            if (health.summary.attention > 0) {
                                append(" · ${health.summary.attention} 需关注")
                            }
                        }
                        state.loadingHealth -> "正在读取…"
                        state.healthError != null -> "连接失败"
                        else -> "没有返回数据"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = OnDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (health == null && settings.labUrl.isNotBlank() && !state.loadingHealth) {
                    Text(
                        text = state.healthError ?: "检查设置里的 Lab Tools 地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnDarkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            InlineAction(
                text = if (settings.labUrl.isBlank()) "去设置" else "服务",
                onClick = if (settings.labUrl.isBlank()) onOpenSettings else onOpenServices,
            )
        }
    }
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
