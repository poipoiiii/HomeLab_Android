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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.poi.homelab.feature.ai.ui.AiScreen
import com.poi.homelab.feature.ai.ui.AiSettingsScreen
import com.poi.homelab.feature.bills.AnalysisResponse
import com.poi.homelab.feature.bills.Insight
import com.poi.homelab.feature.bills.ui.BillImportScreen
import com.poi.homelab.feature.bills.ui.BillsScreen
import com.poi.homelab.feature.calendar.ui.CalendarScreen
import com.poi.homelab.feature.home.ui.HomeScreen
import com.poi.homelab.feature.lab.ui.DownloadsScreen
import com.poi.homelab.feature.lab.ui.ServicesScreen
import com.poi.homelab.feature.settings.ui.SettingsScreen
import com.poi.homelab.R
import kotlinx.coroutines.delay

private enum class Tab(
    val label: String,
    @DrawableRes val icon: Int,
) {
    Home("总览", R.drawable.ic_home),
    Calendar("日历", R.drawable.ic_calendar),
    Services("服务", R.drawable.ic_server),
    Bills("账单", R.drawable.ic_receipt),
    Ai("小砂糖", R.drawable.ic_ai),
    Downloads("下载", R.drawable.ic_download),
    Settings("设置", R.drawable.ic_settings);

    val isPrimary: Boolean
        get() = this == Home || this == Calendar || this == Ai || this == Settings
}

/** 主入口保留总览、日历、AI、设置；其余页面从总览里的入口进入。 */
private val primaryTabs = listOf(Tab.Home, Tab.Calendar, Tab.Ai, Tab.Settings)

private enum class NavStage { Full, Mini, Hidden }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomelabApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var screenIndex by rememberSaveable { mutableIntStateOf(0) }
    var navStage by remember { mutableStateOf(NavStage.Full) }
    var barPulse by remember { mutableIntStateOf(0) }
    var aiConfigOpen by rememberSaveable { mutableStateOf(false) }
    var browserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var billImportOpen by rememberSaveable { mutableStateOf(false) }
    var exitArmed by rememberSaveable { mutableStateOf(false) }
    val tabCenters = remember { mutableStateMapOf<Tab, Offset>() }
    var contentBounds by remember { mutableStateOf(Rect.Zero) }
    var revealOrigin by remember { mutableStateOf(Offset.Zero) }
    val screen = Tab.entries[screenIndex]

    LaunchedEffect(state.message) {
        val message = state.message
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            snackbar.showSnackbar("再按一次返回退出")
            exitArmed = false
        }
    }

    // 主入口菜单：完整条 → 迷你胶囊 → 只剩一根细把手；二级页面保持完整菜单。
    LaunchedEffect(screen, barPulse) {
        navStage = NavStage.Full
        if (screen.isPrimary) {
            delay(4500)
            navStage = NavStage.Mini
            delay(2000)
            navStage = NavStage.Hidden
        }
    }

    fun openTab(tab: Tab, origin: Offset? = null) {
        revealOrigin = origin
            ?: tabCenters[tab]
            ?: Offset(contentBounds.center.x, contentBounds.bottom)
        screenIndex = tab.ordinal
        navStage = NavStage.Full
        barPulse++
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
            !screen.isPrimary -> openTab(Tab.Home)
            exitArmed -> context.findActivity()?.finish()
            else -> exitArmed = true
        }
    }

    val openUrl = browserUrl
    WallpaperBackdrop(state.settings) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                AnimatedContent(
                    targetState = navStage,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f)) togetherWith
                            (fadeOut(tween(140)) + scaleOut(targetScale = 0.94f))
                    },
                    label = "mainNavBar",
                ) { stage ->
                    when (stage) {
                        NavStage.Full -> HomelabNavBar(
                            tabs = primaryTabs,
                            selected = screen,
                            onSelect = { openTab(it) },
                            onTabCenter = { tab, center -> tabCenters[tab] = center },
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                .widthIn(max = 560.dp),
                        )
                        NavStage.Mini -> MiniNavBar(
                            active = screen,
                            onExpand = {
                                navStage = NavStage.Full
                                barPulse++
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        NavStage.Hidden -> NavHandle(
                            onExpand = {
                                navStage = NavStage.Full
                                barPulse++
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { contentBounds = it.boundsInRoot() },
        ) {
            CircularReveal(
                target = screen,
                origin = revealOrigin - contentBounds.topLeft,
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
                )
                Tab.Calendar -> CalendarScreen(
                    state = state,
                    onSave = viewModel::saveCalendarEntry,
                    onDelete = viewModel::deleteCalendarEntry,
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
                    onFileTreeSelected = viewModel::setFileTreeUri,
                    onClearFileTree = viewModel::clearFileTree,
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
    openUrl?.let { url ->
        InAppBrowserScreen(url = url, onClose = { browserUrl = null })
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
private fun HomelabNavBar(
    tabs: List<Tab>,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    onTabCenter: (Tab, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(29.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 9.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemWidth = maxWidth / tabs.size
            val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 340f),
                label = "navIndicatorX",
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX + 4.dp, y = 6.dp)
                    .width((itemWidth - 8.dp).coerceAtLeast(0.dp))
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(primary.copy(alpha = 0.11f))
                    .border(1.dp, primary.copy(alpha = 0.16f), RoundedCornerShape(23.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    NavItem(
                        tab = tab,
                        active = tab == selected,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { coordinates ->
                                onTabCenter(tab, coordinates.boundsInRoot().center)
                            },
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: Tab,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(220),
        label = "navItemTint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1.06f else 0.94f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "navItemScale",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(23.dp))
            .selectable(
                selected = active,
                onClick = onClick,
                role = Role.Tab,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(tab.icon),
                contentDescription = if (active) null else tab.label,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
            AnimatedVisibility(
                visible = active,
                enter = fadeIn(tween(200)) + expandHorizontally(
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start,
                ),
                exit = fadeOut(tween(120)) + shrinkHorizontally(
                    animationSpec = tween(160, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start,
                ),
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniNavBar(
    active: Tab,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val activeIndex = primaryTabs.indexOf(active).coerceAtLeast(0)
    val slotWidth = 14.dp
    val rowWidth = slotWidth * primaryTabs.size
    val rowStart = (68.dp - rowWidth) / 2
    val capsuleX by animateDpAsState(
        targetValue = rowStart + slotWidth * activeIndex,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 360f),
        label = "miniNavX",
    )
    Box(
        modifier = modifier
            .width(68.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpand,
            )
            .semantics { contentDescription = "展开导航菜单" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(68.dp).height(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 7.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(rowWidth)
                        .height(32.dp),
                ) {
                    repeat(primaryTabs.size) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                                    ),
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = capsuleX)
                        .width(14.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(primary),
                )
            }
        }
    }
}

@Composable
private fun NavHandle(onExpand: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpand,
            )
            .semantics { contentDescription = "展开导航菜单" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .padding(bottom = 7.dp)
                .width(36.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)),
        )
    }
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
