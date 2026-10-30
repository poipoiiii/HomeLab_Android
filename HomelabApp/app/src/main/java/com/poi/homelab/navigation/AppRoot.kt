package com.poi.homelab.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.core.ui.components.CircularReveal
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.MonospaceBlock
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.InAppBrowserScreen
import com.poi.homelab.core.ui.theme.CardSurfaceAlpha
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.util.todayLabel
import com.poi.homelab.feature.ai.ui.AiScreen
import com.poi.homelab.feature.ai.ui.AiSettingsScreen
import com.poi.homelab.feature.bills.AnalysisResponse
import com.poi.homelab.feature.bills.Insight
import com.poi.homelab.feature.bills.ui.BillImportScreen
import com.poi.homelab.feature.bills.ui.BillsScreen
import com.poi.homelab.feature.calendar.ui.CalendarScreen
import com.poi.homelab.feature.files.ui.FileInsightScreen
import com.poi.homelab.feature.home.ui.ChecklistItem
import com.poi.homelab.feature.home.ui.DailyChecklistCard
import com.poi.homelab.feature.home.ui.HomeScreen
import com.poi.homelab.feature.home.ui.TodoEditorSheet
import com.poi.homelab.feature.lab.ui.DownloadsScreen
import com.poi.homelab.feature.lab.ui.ServicesScreen
import com.poi.homelab.feature.market.ui.MarketIntradaySheet
import com.poi.homelab.feature.market.ui.MarketScreen
import com.poi.homelab.feature.settings.ui.SettingsScreen
import com.poi.homelab.feature.watch.ui.WatchScreen
import com.poi.homelab.R
import kotlinx.coroutines.launch

private enum class Tab(
    val label: String,
    @DrawableRes val icon: Int,
) {
    Home("总览", R.drawable.ic_home),
    Market("行情", R.drawable.ic_chart),
    Calendar("日历", R.drawable.ic_calendar),
    Services("服务", R.drawable.ic_server),
    Bills("账单", R.drawable.ic_receipt),
    Watch("监控", R.drawable.ic_watch),
    Ai("小砂糖", R.drawable.ic_ai),
    Downloads("下载", R.drawable.ic_download),
    Settings("设置", R.drawable.ic_settings),
}

/** 依赖家里 Lab Tools / 账本服务的页面，脱机时没有可看的数据。 */
private fun Tab.needsLab(): Boolean = this == Tab.Services || this == Tab.Bills

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomelabApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var screenIndex by rememberSaveable { mutableIntStateOf(0) }
    var marketSymbolId by rememberSaveable { mutableStateOf<String?>(null) }
    var aiConfigOpen by rememberSaveable { mutableStateOf(false) }
    var browserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var billImportOpen by rememberSaveable { mutableStateOf(false) }
    var fileInsightOpen by rememberSaveable { mutableStateOf(false) }
    var todoEditorOpen by rememberSaveable { mutableStateOf(false) }
    var exitArmed by rememberSaveable { mutableStateOf(false) }
    val screen = Tab.entries[screenIndex]

    LaunchedEffect(state.message) {
        val message = state.message
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // 回到前台重新探一次：家里主机可能刚从休眠醒来，VPN 也可能刚连上
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.probeReachability()
    }

    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            snackbar.showSnackbar("再按一次返回退出")
            exitArmed = false
        }
    }

    // 主菜单收进左侧抽屉：点头像打开，不再用底部导航
    fun openTab(tab: Tab) {
        if (tab == Tab.Market) marketSymbolId = null
        screenIndex = tab.ordinal
        scope.launch { drawerState.close() }
    }

    if (aiConfigOpen) {
        BackHandler { aiConfigOpen = false }
        WallpaperBackdrop(state.settings) {
            AiSettingsScreen(
                state = state,
                onBack = { aiConfigOpen = false },
                onSaveAi = viewModel::saveAiProvider,
                onSelectAi = viewModel::selectAiProvider,
            )
        }
        return
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            screen == Tab.Market && marketSymbolId != null -> marketSymbolId = null
            screen != Tab.Home -> openTab(Tab.Home)
            exitArmed -> context.findActivity()?.finish()
            else -> exitArmed = true
        }
    }

    val openUrl = browserUrl
    WallpaperBackdrop(state.settings) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            HomelabDrawer(
                state = state,
                screen = screen,
                onSelect = { openTab(it) },
                onToggle = viewModel::toggleChecklist,
                onEditTodos = { todoEditorOpen = true },
            )
        },
    ) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (screen != Tab.Home) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavAvatar(onClick = { scope.launch { drawerState.open() } })
                }
            }
            Box(Modifier.weight(1f)) {
            CircularReveal(
                target = screen,
                origin = Offset(48f, 24f),
            ) { active ->
            when (active) {
                Tab.Home -> HomeScreen(
                    state = state,
                    onRefresh = viewModel::refreshAll,
                    onOpenSettings = { openTab(Tab.Settings) },
                    onOpenBills = { openTab(Tab.Bills) },
                    onOpenServices = { openTab(Tab.Services) },
                    onOpenDownloads = { openTab(Tab.Downloads) },
                    onOpenLink = { browserUrl = it },
                    onToggleChecklist = viewModel::toggleChecklist,
                    onSaveTodos = viewModel::saveTodos,
                    onLoadWeather = viewModel::loadWeather,
                    onOpenFiles = { fileInsightOpen = true },
                    onOpenCalendar = { openTab(Tab.Calendar) },
                    onLoadDeviceUsage = viewModel::loadDeviceUsage,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onRefreshMarket = viewModel::refreshMarket,
                    onSearchMarket = viewModel::searchMarket,
                    onSaveMarketSymbols = viewModel::saveMarketSymbols,
                    onSaveMarketPinnedSymbols = viewModel::saveMarketPinnedSymbols,
                    onResetMarketSymbols = viewModel::resetMarketSymbols,
                    onOpenMarketSymbol = { symbolId ->
                        marketSymbolId = symbolId
                        screenIndex = Tab.Market.ordinal
                    },
                    onPreviewMarketSymbol = viewModel::openMarketPreview,
                )
                Tab.Market -> MarketScreen(
                    state = state,
                    selectedSymbolId = marketSymbolId,
                    onSelectSymbol = { marketSymbolId = it },
                    onCloseDetail = { marketSymbolId = null },
                    onRefreshQuotes = viewModel::refreshMarket,
                    onRefreshExtras = viewModel::refreshMarketExtras,
                    onLoadBars = viewModel::loadMarketBars,
                    onLoadIntraday = viewModel::loadIntraday,
                    onAnalyze = viewModel::analyzeMarket,
                    onRateNews = viewModel::rateMarketNews,
                    onPreviewSymbol = viewModel::openMarketPreview,
                    onSetRefreshSeconds = viewModel::setMarketRefreshSeconds,
                    onSearch = viewModel::searchMarket,
                    onSaveSymbols = viewModel::saveMarketSymbols,
                    onSavePinnedSymbols = viewModel::saveMarketPinnedSymbols,
                    onResetSymbols = viewModel::resetMarketSymbols,
                )
                Tab.Calendar -> CalendarScreen(
                    state = state,
                    onSave = viewModel::saveCalendarEntry,
                    onDelete = viewModel::deleteCalendarEntry,
                    onToggleDone = viewModel::setCalendarEntryDone,
                    onLoadMonth = viewModel::loadCalendarBills,
                    onOpenBills = { month ->
                        viewModel.selectMonth(month)
                        openTab(Tab.Bills)
                    },
                )
                Tab.Services -> ServicesScreen(
                    state = state,
                    onRefresh = viewModel::refreshHealth,
                    onAction = viewModel::requestServiceAction,
                    onLogs = viewModel::loadLogs,
                )
                Tab.Bills -> BillsScreen(
                    state = state,
                    onSelectMonth = viewModel::selectMonth,
                    onRefresh = viewModel::refreshBills,
                    onAnalysis = viewModel::loadAnalysis,
                    onOpenImport = { billImportOpen = true },
                    onRefreshBalance = viewModel::refreshBalanceCandidates,
                    onConfirmBalance = viewModel::confirmBalanceCandidate,
                    onRejectBalance = viewModel::rejectBalanceCandidate,
                )
                Tab.Watch -> WatchScreen(
                    state = state,
                    onTestNotification = viewModel::testWatchNotification,
                    onSaveRule = viewModel::saveWatchRule,
                    onDeleteRule = viewModel::deleteWatchRule,
                    onToggleRule = viewModel::toggleWatchRule,
                    onRunNow = viewModel::runWatchNow,
                    onLoadTypes = viewModel::loadWatchTypes,
                )
                Tab.Ai -> AiScreen(
                    state = state,
                    onSend = viewModel::sendChat,
                    onClear = viewModel::clearChat,
                    onOpenAiSettings = { aiConfigOpen = true },
                    onRefreshBalance = viewModel::refreshAiBalance,
                    onChooseUploadDir = viewModel::startPendingUpload,
                    onCancelUpload = viewModel::cancelPendingUpload,
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
                    onOpenAiSettings = { aiConfigOpen = true },
                    onFileTreeSelected = viewModel::addFileTreeUri,
                    onClearFileTree = viewModel::clearFileTree,
                    onOpenFiles = { fileInsightOpen = true },
                    onShowLedger = viewModel::setShowLedgerOnHome,
                    onShowWallpaper = viewModel::setShowWallpaper,
                    onWallpaperOpacity = viewModel::setWallpaperOpacity,
                    onWallpaperDim = viewModel::setWallpaperDim,
                    onSettingsDetailed = viewModel::setSettingsDetailed,
                )
            }
            }
            }
        }
    }
    openUrl?.let { url ->
        InAppBrowserScreen(url = url, onClose = { browserUrl = null })
    }
    state.market.previewSymbol?.let { symbolId ->
        MarketIntradaySheet(
            symbol = com.poi.homelab.feature.market.findMarketSymbol(
                state.settings.marketPinnedSymbols,
                state.settings.marketSymbols,
                symbolId,
            ),
            series = if (state.market.intradaySymbol == symbolId) state.market.intraday else null,
            loading = state.market.intradayLoading && state.market.intradaySymbol == symbolId,
            error = if (state.market.intradaySymbol == symbolId) state.market.intradayError else null,
            onDismiss = viewModel::closeMarketPreview,
        )
    }
    if (billImportOpen) {
        BillImportScreen(
            state = state,
            onClose = { billImportOpen = false },
            onLoadMeta = viewModel::loadBillMeta,
            onPickFiles = { uris, account -> viewModel.extractBillFiles(uris, account) },
            onUpdateItem = viewModel::updateBillImportItem,
            onRemoveItem = viewModel::removeBillImportItem,
            onCommit = viewModel::commitBillImport,
            onClear = viewModel::clearBillImport,
            onRefreshCloud = viewModel::refreshBillImportCloud,
        )
    }
    if (fileInsightOpen) {
        FileInsightScreen(
            state = state,
            onLoad = viewModel::loadFileInsight,
            onClose = { fileInsightOpen = false },
            onRootSelected = viewModel::addFileTreeUri,
            onRootRemoved = viewModel::removeFileTreeUri,
            onScan = viewModel::startFileScan,
            onCancelScan = viewModel::cancelFileScan,
            onViewChange = viewModel::setFileView,
            onCategoryChange = viewModel::setFileCategory,
            onQueryChange = viewModel::setFileQuery,
            onSortChange = viewModel::setFileSort,
            onLoadMore = viewModel::loadMoreFiles,
            onAnalyze = viewModel::analyzeFiles,
            onShowAnalysis = viewModel::showFileAnalysis,
            onCloseAnalysis = viewModel::closeFileAnalysis,
        )
    }
    }
    }
    }

    if (todoEditorOpen) {
        TodoEditorSheet(
            items = state.todos,
            onDismiss = { todoEditorOpen = false },
            onSave = viewModel::saveTodos,
        )
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

/** 左侧导航抽屉：点头像打开，包含全部页面入口和今日清单。 */
@Composable
private fun HomelabDrawer(
    state: UiState,
    screen: Tab,
    onSelect: (Tab) -> Unit,
    onToggle: (String) -> Unit,
    onEditTodos: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.app_avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("poi's home", style = MaterialTheme.typography.titleLarge)
                    val health = state.health
                    Text(
                        text = if (health == null) {
                            todayLabel()
                        } else {
                            "${todayLabel()} · ${health.summary.running}/${health.summary.total} 运行"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val offline = state.labReachability?.online == false
            Tab.entries.forEach { tab ->
                DrawerRow(
                    tab = tab,
                    active = tab == screen,
                    enabled = !offline || !tab.needsLab(),
                    onClick = { onSelect(tab) },
                )
            }
            ThinDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日清单",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("编辑", onEditTodos)
            }
            Spacer(Modifier.height(6.dp))
            DailyChecklistCard(
                items = state.todos.map { ChecklistItem(it.id, it.label) },
                checked = state.checkedToday,
                onToggle = onToggle,
                onEdit = onEditTodos,
            )
        }
    }
}

@Composable
private fun DrawerRow(tab: Tab, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active && enabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(tab.icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (!enabled) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "脱机不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** 左上角头像：点开导航抽屉。首页复用标题里的头像，其余页面用这个。 */
@Composable
private fun NavAvatar(onClick: () -> Unit) {
    Image(
        painter = painterResource(R.drawable.app_avatar),
        contentDescription = "打开导航",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** 全局壁纸层：所有主页面共用一张壁纸，卡片半透明后能透出背景。 */
@Composable
private fun WallpaperBackdrop(settings: AppSettings, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (settings.showWallpaper) {
            Image(
                painter = painterResource(R.drawable.home_wallpaper),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(settings.wallpaperOpacity),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.background.copy(alpha = settings.wallpaperDim),
                    ),
            )
        }
        content()
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
