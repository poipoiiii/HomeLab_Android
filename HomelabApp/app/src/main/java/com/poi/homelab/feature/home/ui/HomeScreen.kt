package com.poi.homelab.feature.home.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.DarkCard
import com.poi.homelab.core.ui.components.DarkStat
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.StatusDot
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.OnDarkSoft
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.core.util.money
import com.poi.homelab.core.util.todayLabel
import com.poi.homelab.feature.automation.SplashSkip
import com.poi.homelab.feature.bills.MonthStats
import com.poi.homelab.feature.bills.ui.TransactionRow
import com.poi.homelab.feature.homarr.resolveHomarrUrl
import com.poi.homelab.feature.homarr.stringValue
import com.poi.homelab.feature.homarr.ui.HomarrBoardGrid
import com.poi.homelab.feature.homarr.ui.InlinePage
import com.poi.homelab.feature.homarr.WeatherQuery
import com.poi.homelab.feature.homarr.WeatherSnapshot
import com.poi.homelab.feature.home.TodoItem
import com.poi.homelab.feature.home.ui.ChecklistItem
import com.poi.homelab.feature.home.ui.DailyChecklistCard
import com.poi.homelab.feature.home.ui.QuickEntryPager
import com.poi.homelab.feature.home.ui.StackCard
import com.poi.homelab.feature.home.ui.TodoEditorSheet
import com.poi.homelab.feature.lab.HealthSnapshot
import com.poi.homelab.navigation.UiState
import com.poi.homelab.R
import java.net.URI
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    onLoadWeather: suspend (WeatherQuery) -> WeatherSnapshot? = { null },
    onOpenFiles: () -> Unit = {},
) {
    val settings = state.settings
    val needsSetup = settings.labUrl.isBlank() && settings.billUrl.isBlank()
    // 探测已配置地址失败（多半是 Tailscale 没连上），首页切到「离家模式」
    val offline = state.labReachable == false
    val todoItems = state.todos.map { ChecklistItem(it.id, it.label) }
    var todoEditorOpen by rememberSaveable { mutableStateOf(false) }
    var powerOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val doneCount = todoItems.count { it.id in state.checkedToday }
    var quickSheetOpen by rememberSaveable { mutableStateOf(false) }
    val quickSheetState = rememberModalBottomSheetState()
    val closeSheetThen: (() -> Unit) -> Unit = { action ->
        scope.launch {
            quickSheetState.hide()
            quickSheetOpen = false
            action()
        }
    }

    // Homarr 上的电源 iframe 移到右侧面板，避免在看板里占一大块
    val powerIframe = remember(state.board, settings.homarrUrl) {
        state.board?.items?.firstOrNull { item ->
            item.kind == "iframe" && (
                item.id.contains("power", ignoreCase = true) ||
                    item.advancedOptions?.stringValue("title")?.contains("电源") == true ||
                    item.options.stringValue("embedUrl")?.contains("power", ignoreCase = true) == true
                )
        }
    }
    val powerUrl = remember(powerIframe, settings.homarrUrl) {
        powerIframe?.let {
            resolveHomarrUrl(settings.homarrUrl, it.options.stringValue("embedUrl"))
        }
    }
    val powerSkipIds = remember(powerIframe) {
        powerIframe?.id?.let { setOf(it) } ?: emptySet()
    }

    BackHandler(enabled = powerOpen && powerUrl != null) { powerOpen = false }

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
    // 下拉即刷新：不再需要标题栏的「刷新」按钮
    PullToRefreshBox(
        isRefreshing = state.loadingBoard || state.loadingBills,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HomeHeader(health = state.health)
            }

            if (offline) {
                item {
                    AwayModeCard(
                        state = state,
                        onRetry = onRefresh,
                        onOpenSettings = onOpenSettings,
                    )
                }
                item { AdOffCard() }
            }

            if (!offline && needsSetup) {
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

            if (!offline && settings.homarrUrl.isNotBlank()) {
                item {
                    when {
                        state.board != null -> HomarrBoardGrid(
                            board = state.board,
                            apps = state.boardApps,
                            baseUrl = settings.homarrUrl,
                            status = state.appStatus,
                            skipItemIds = powerSkipIds,
                            loadWeather = onLoadWeather,
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
            } else if (!offline) {
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

            if (!offline && settings.billUrl.isNotBlank() && settings.showLedgerOnHome) {
                item {
                    LedgerCard(
                        state = state,
                        onOpenBills = onOpenBills,
                        onRefresh = onRefresh,
                    )
                }
            }

            if (!offline && settings.showLedgerOnHome) {
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

            if (!offline) {
                item { AdOffCard() }
            }
        }
        if (powerUrl != null) {
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
                    url = powerUrl,
                    onClose = { powerOpen = false },
                    onOpenFull = {
                        powerOpen = false
                        onOpenLink(powerUrl)
                    },
                )
            }
        }
        if (!powerOpen) {
            QuickPanelFab(
                onClick = { quickSheetOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 18.dp),
            )
        }
        if (todoEditorOpen) {
            TodoEditorSheet(
                items = state.todos,
                onDismiss = { todoEditorOpen = false },
                onSave = onSaveTodos,
            )
        }
        if (quickSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { quickSheetOpen = false },
                sheetState = quickSheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                ) {
                    QuickActionRow(
                        title = "今天清单",
                        subtitle = "完成 $doneCount/${todoItems.size} 项",
                        onClick = { closeSheetThen { scope.launch { drawerState.open() } } },
                    )
                    ThinDivider()
                    QuickActionRow(
                        title = "文件洞察",
                        subtitle = "扫描本机文件，让小砂糖分析",
                        onClick = { closeSheetThen(onOpenFiles) },
                    )
                    if (powerUrl != null) {
                        ThinDivider()
                        QuickActionRow(
                            title = "电源面板",
                            subtitle = "容器启停、内存与磁盘",
                            onClick = { closeSheetThen { powerOpen = true } },
                        )
                    }
                }
            }
        }
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

/** 右下角的「面板」入口：整屏只有一个触点，点开再选清单还是电源。 */
@Composable
private fun QuickPanelFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = "面板",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun QuickActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "打开",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HomeHeader(health: HealthSnapshot?) {
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
    }
}

/**
 * 离家模式：地址探测不通时顶掉看板和账本。
 * 虚线框当作门口那块地址牌，只写事实，不报警。
 */
@Composable
private fun AwayModeCard(
    state: UiState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(13f, 11f), 0f) }
    val target = listOf(state.settings.labUrl, state.settings.billUrl, state.settings.homarrUrl)
        .firstOrNull { it.isNotBlank() }
        ?: ""
    val host = target.substringAfter("://").substringBefore('/').substringBefore('?')
    val done = state.todos.count { it.id in state.checkedToday }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                )
            }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Warning, 9)
            Spacer(Modifier.width(8.dp))
            Text("离家模式", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            text = host.ifBlank { "还没填地址" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "4 秒没等到响应。多半是 Tailscale 没连上，也可能家里的机器在休眠。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton("重试", onRetry, Modifier.weight(1f))
            SecondaryButton("检查地址", onOpenSettings, Modifier.weight(1f))
        }
        ThinDivider(Modifier.padding(top = 18.dp, bottom = 14.dp))
        Text("离线还能用", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "清单：完成 $done/${state.todos.size} 项",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        val overview = state.overview
        Text(
            text = if (overview != null) {
                "账本：本月支出 ${money(overview.monthStats.expense)}（上次读取）"
            } else {
                "账本：要联网才能刷新"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 首页的去广告总览：只管开屏跳过，数据也都是本机真实记下的。 */
@Composable
private fun AdOffCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SplashSkip.isEnabled(context)) }
    val skipped = SplashSkip.todayCount(context)
    CreamCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("去广告", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        !enabled -> "关着，开屏广告不会自动跳过"
                        skipped > 0 -> "今天替你跳过 $skipped 次开屏"
                        else -> "今天还没遇到需要跳过的开屏"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    SplashSkip.setEnabled(context, it)
                },
            )
        }
        Text(
            text = "覆盖 ${SplashSkip.packages(context).size} 个应用，可以在设置里加。营销通知和广告包是在电脑上那个工具关的，这里只负责开屏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** 本月账本：只保留支出 / 收入 / 结余三个数字。 */
@Composable
private fun LedgerCard(
    state: UiState,
    onOpenBills: () -> Unit,
    onRefresh: () -> Unit,
) {
    val overview = state.overview
    DarkCard {
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
