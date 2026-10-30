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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.CardSurfaceAlpha
import com.poi.homelab.core.ui.theme.DarkSurface
import com.poi.homelab.core.ui.theme.OnDarkSoft
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.core.util.Reachability
import com.poi.homelab.core.util.ReachabilityState
import com.poi.homelab.core.util.Tailscale
import com.poi.homelab.core.util.money
import com.poi.homelab.core.util.todayLabel
import com.poi.homelab.feature.automation.SplashSkip
import com.poi.homelab.feature.bills.MonthStats
import com.poi.homelab.feature.bills.ui.TransactionRow
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.feature.homarr.resolveHomarrUrl
import com.poi.homelab.feature.homarr.stringValue
import com.poi.homelab.feature.homarr.ui.HomarrBoardGrid
import com.poi.homelab.feature.homarr.ui.InlinePage
import com.poi.homelab.feature.homarr.WeatherQuery
import com.poi.homelab.feature.homarr.WeatherSnapshot
import com.poi.homelab.feature.home.TodoItem
import com.poi.homelab.feature.home.ui.ChecklistItem
import com.poi.homelab.feature.home.ui.QuickEntryPager
import com.poi.homelab.feature.home.ui.StackCard
import com.poi.homelab.feature.lab.HealthSnapshot
import com.poi.homelab.feature.market.MarketSearchResult
import com.poi.homelab.feature.market.MarketSymbol
import com.poi.homelab.feature.market.ui.MarketCard
import com.poi.homelab.feature.market.ui.MarketEditorSheet
import com.poi.homelab.navigation.UiState
import com.poi.homelab.R
import java.net.URI
import java.time.LocalDate
import kotlinx.coroutines.delay
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
    onOpenCalendar: () -> Unit = {},
    onLoadDeviceUsage: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onRefreshMarket: () -> Unit = {},
    onSearchMarket: suspend (String) -> List<MarketSearchResult> = { emptyList() },
    onSaveMarketSymbols: (List<MarketSymbol>, Boolean) -> Unit = { _, _ -> },
    onSaveMarketPinnedSymbols: (List<MarketSymbol>, Boolean) -> Unit = { _, _ -> },
    onResetMarketSymbols: () -> Unit = {},
    onOpenMarketSymbol: (String) -> Unit = {},
    onPreviewMarketSymbol: (String) -> Unit = {},
) {
    val settings = state.settings
    val needsSetup = settings.labUrl.isBlank() && settings.billUrl.isBlank()
    // 探测已配置地址失败时进「脱机模式」，具体是没连 VPN 还是主机休眠由 ReachabilityProbe 判定
    val offline = state.labReachability?.online == false
    val todoItems = state.todos.map { ChecklistItem(it.id, it.label) }
    var powerOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val doneCount = todoItems.count { it.id in state.checkedToday }
    var quickSheetOpen by rememberSaveable { mutableStateOf(false) }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    var marketEditOpen by rememberSaveable { mutableStateOf(false) }
    var awaySheetOpen by rememberSaveable { mutableStateOf(false) }
    var marketExpanded by rememberSaveable { mutableStateOf(false) }
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

    // 设备排行要扫已安装应用，展开「更多」时才拉一次
    LaunchedEffect(moreExpanded) {
        if (moreExpanded) onLoadDeviceUsage()
    }

    // 行情默认折叠，折叠时不刷新；展开后按设置间隔轮询，退到后台就停
    val lifecycleOwner = LocalLifecycleOwner.current
    val inspection = LocalInspectionMode.current
    val marketRefreshMs = settings.marketRefreshSeconds.coerceIn(10, 300) * 1000L
    LaunchedEffect(lifecycleOwner, inspection, marketRefreshMs, marketExpanded) {
        if (inspection || !marketExpanded) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onRefreshMarket()
                delay(marketRefreshMs)
            }
        }
    }

    // 下拉即刷新：不再需要标题栏的「刷新」按钮
    PullToRefreshBox(
        isRefreshing = state.loadingBoard || state.loadingBills ||
            state.loadingHealth || state.loadingWeather,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HomeHeader(
                    health = state.health,
                    onOpenMenu = onOpenMenu,
                    offline = offline,
                    onOpenStatus = { awaySheetOpen = true },
                )
            }

            // 行情置顶：默认折叠，折叠时不轮询刷新
            item {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            .clickable { marketExpanded = !marketExpanded }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "行情",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (marketExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (marketExpanded) {
                        Spacer(Modifier.height(10.dp))
                        MarketCard(
                            quotes = state.marketQuotes,
                            loading = state.marketLoading,
                            error = state.marketError,
                            updatedAt = state.marketUpdatedAt,
                            onEdit = { marketEditOpen = true },
                            onRetry = onRefreshMarket,
                            onOpenSymbol = onOpenMarketSymbol,
                            onPreviewSymbol = onPreviewMarketSymbol,
                            pinnedSymbols = settings.marketPinnedSymbols,
                        )
                    }
                }
            }

            // 账本与日程并排：两块都是本机数据，脱机也能看
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!offline && settings.billUrl.isNotBlank() && settings.showLedgerOnHome) {
                        LedgerTile(state, onOpenBills, Modifier.weight(1f))
                    }
                    ScheduleTile(state.calendarEntries, onOpenCalendar, Modifier.weight(1f))
                }
            }

            if (needsSetup && !offline) {
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

            // 脱机模式也保留这块：净资产读上次缓存，日程/照片同步/设备排行都是本机数据
            val pendingBalances = if (offline) 0 else state.overview?.pendingBalanceCandidates ?: 0
            item {
                SectionHeader(
                    title = "更多",
                    subtitle = if (moreExpanded) {
                        null
                    } else {
                        buildString {
                            if (pendingBalances > 0) append("$pendingBalances 条余额待确认 · ")
                            if (offline) {
                                append("离线可用 · 余额 · 照片 · 设备")
                            } else {
                                append("净资产 · 主机 · 天气 · 设备")
                            }
                        }
                    },
                    action = {
                        InlineAction(
                            text = if (moreExpanded) "收起" else "展开",
                            onClick = { moreExpanded = !moreExpanded },
                        )
                    },
                )
            }
            if (moreExpanded) {
                state.overview?.let { overview ->
                    item {
                        NetWorthCard(
                            overview = overview,
                            onOpenBills = onOpenBills,
                            showPending = !offline,
                        )
                    }
                }
                if (!offline) {
                    state.health?.let { health ->
                        item { HostHealthCard(health = health, onOpenServices = onOpenServices) }
                    }
                    if (settings.weatherCity.isNotBlank()) {
                        item {
                            HomeWeatherCard(
                                weather = state.weather,
                                error = state.weatherError,
                                loading = state.loadingWeather,
                                onRetry = onRefresh,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }
                }
                if (settings.photoAutoSync) {
                    item { PhotoSyncCard(settings = settings, onOpenSettings = onOpenSettings) }
                }
                item {
                    DeviceUsageCard(
                        loading = state.loadingUsage,
                        hasAccess = state.usageAccess,
                        usageRows = state.usageRows,
                        storageRows = state.storageRows,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
            item { AdOffCard() }
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
        if (awaySheetOpen) {
            val awaySheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { awaySheetOpen = false },
                sheetState = awaySheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                ) {
                    AwayModeCard(
                        state = state,
                        reachability = state.labReachability,
                        onRetry = onRefresh,
                        onOpenSettings = {
                            awaySheetOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
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
                        onClick = { closeSheetThen(onOpenMenu) },
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
        if (marketEditOpen) {
            MarketEditorSheet(
                symbols = settings.marketSymbols,
                pinnedSymbols = settings.marketPinnedSymbols,
                onAdd = { symbol -> onSaveMarketSymbols(settings.marketSymbols + symbol, true) },
                onRemove = { symbol -> onSaveMarketSymbols(settings.marketSymbols - symbol, true) },
                onSaveSymbols = onSaveMarketSymbols,
                onSavePinned = onSaveMarketPinnedSymbols,
                onReset = onResetMarketSymbols,
                onSearch = onSearchMarket,
                onDismiss = { marketEditOpen = false },
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
private fun HomeHeader(
    health: HealthSnapshot?,
    onOpenMenu: () -> Unit,
    offline: Boolean,
    onOpenStatus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.app_avatar),
            contentDescription = "打开导航",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(onClick = onOpenMenu),
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
        if (offline) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenStatus)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(Warning, 7)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "脱机",
                    style = MaterialTheme.typography.labelMedium,
                    color = Warning,
                )
            }
        }
    }
}

/**
 * 脱机详情：点右上角「脱机」标签弹出的底部卡片。
 * 虚线框当作门口那块地址牌，写清是没连 VPN、主机休眠还是服务没起。
 */
@Composable
private fun AwayModeCard(
    state: UiState,
    reachability: Reachability?,
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
    val context = LocalContext.current
    val tailscaleInstalled = remember { Tailscale.isInstalled(context) }
    var connecting by remember { mutableStateOf(false) }
    LaunchedEffect(connecting) {
        if (!connecting) return@LaunchedEffect
        delay(3500)
        onRetry()
        connecting = false
    }
    // VPN 通道还在、只是主机没响应时，按钮改成断开，方便换网络或手动排查
    val vpnConnected = reachability?.state == ReachabilityState.HostAsleep ||
        reachability?.state == ReachabilityState.ServiceDown ||
        reachability?.state == ReachabilityState.Failed
    val cause = awayCause(reachability)

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
            Text("脱机模式", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Tag(cause.tag, Warning)
        }
        Text(
            text = host.ifBlank { "还没填地址" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = cause.headline,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = cause.hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (reachability?.state == ReachabilityState.Failed && !reachability.detail.isNullOrBlank()) {
            Text(
                text = reachability.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton(
                text = when {
                    connecting -> "正在处理…"
                    vpnConnected -> "断开 Tailscale"
                    else -> "连接 Tailscale"
                },
                onClick = {
                    // 优先发广播直接连/断隧道；被系统拒绝（接收器未导出）才退回打开 Tailscale
                    val sent = if (vpnConnected) {
                        Tailscale.disconnect(context)
                    } else {
                        Tailscale.connect(context)
                    }
                    if (!sent) Tailscale.openApp(context)
                    connecting = true
                },
                modifier = Modifier.weight(1f),
                enabled = tailscaleInstalled && !connecting,
                loading = connecting,
            )
            SecondaryButton("重试", onRetry, Modifier.weight(1f))
        }
        if (!tailscaleInstalled) {
            Text(
                text = "没找到 Tailscale（com.tailscale.ipn），只能手动连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            InlineAction("检查地址", onOpenSettings)
        }
    }
}

private data class AwayCause(val tag: String, val headline: String, val hint: String)

/** 把探测结果翻成可判断的说明：无网络、VPN 未连接、主机无响应、服务未启动。 */
private fun awayCause(reachability: Reachability?): AwayCause = when (reachability?.state) {
    ReachabilityState.NoNetwork -> AwayCause(
        tag = "无网络连接",
        headline = "当前没有可用的网络",
        hint = "Wi-Fi 与移动数据均不可用，恢复网络后重试。",
    )
    ReachabilityState.VpnOff -> AwayCause(
        tag = "VPN 未连接",
        headline = "VPN 未连接",
        hint = "当前没有可用的 VPN 连接，无法访问内网地址。请先连接 Tailscale 后重试。",
    )
    ReachabilityState.HostAsleep -> AwayCause(
        tag = "主机未响应",
        headline = "VPN 已连接，主机未响应",
        hint = "请求在 4 秒内没有返回。可能是家中主机处于休眠状态，或其 Tailscale 节点离线。",
    )
    ReachabilityState.ServiceDown -> AwayCause(
        tag = "服务未启动",
        headline = "主机在线，服务未响应",
        hint = "端口拒绝连接，主机在线但服务未启动，请检查容器或反向代理状态。",
    )
    ReachabilityState.Failed -> AwayCause(
        tag = "连接异常",
        headline = "已连接到主机，但握手失败",
        hint = "请检查地址、端口与证书配置。",
    )
    else -> AwayCause(
        tag = "待判定",
        headline = "尚未探测到主机",
        hint = "4 秒内未收到响应，请重试。",
    )
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
            text = "覆盖 ${SplashSkip.packages(context).size} 个应用 · 设置里可加",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 本月账本：并排小卡，只留支出大数字 + 收入/结余一行。 */
@Composable
private fun LedgerTile(
    state: UiState,
    onOpenBills: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = state.overview
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface)
            .border(1.dp, Color(0xFF3A3733), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenBills)
            .padding(16.dp),
    ) {
        Text("本月账本", style = MaterialTheme.typography.labelMedium, color = OnDarkSoft)
        when {
            overview != null -> {
                Text(
                    text = money(overview.monthStats.expense),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFE7A48C),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "支出 · 收 ${money(overview.monthStats.income)} · 结 ${money(overview.monthStats.net)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnDarkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.loadingBills -> Text(
                text = "正在读取…",
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkSoft,
                modifier = Modifier.padding(top = 10.dp),
            )
            else -> Text(
                text = "还没有读到账本",
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkSoft,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "明细",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** 日程：并排小卡，只显示最近两条。 */
@Composable
private fun ScheduleTile(
    entries: List<CalendarEntry>,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val upcoming = remember(entries, today) {
        val start = today.toString()
        val end = today.plusDays(7).toString()
        entries.filter { it.date in start..end }.take(2)
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenCalendar)
            .padding(16.dp),
    ) {
        Text(
            text = "日程",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (upcoming.isEmpty()) {
            Text(
                text = "未来 7 天没有安排",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            upcoming.forEachIndexed { index, entry ->
                Column(Modifier.padding(top = if (index == 0) 8.dp else 10.dp)) {
                    Text(
                        text = entry.date.takeLast(5).replace('-', '/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "打开日历",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
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
