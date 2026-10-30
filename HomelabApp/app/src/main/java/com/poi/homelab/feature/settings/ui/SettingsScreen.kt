package com.poi.homelab.feature.settings.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.SuccessButton
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.core.util.hasPhotoAccess
import com.poi.homelab.core.util.hasUsageAccess
import com.poi.homelab.core.util.photoPermission
import com.poi.homelab.feature.automation.AutomationRestore
import com.poi.homelab.feature.automation.SplashSkip
import com.poi.homelab.feature.automation.SplashSubscriptions
import com.poi.homelab.feature.automation.UiAutomation
import com.poi.homelab.feature.homarr.HomarrBoard
import com.poi.homelab.feature.notify.ui.CaptureSettingsCard
import com.poi.homelab.feature.photos.PhotoAutoSync
import com.poi.homelab.feature.photos.PhotoSyncStatus
import com.poi.homelab.navigation.UiState
import kotlinx.coroutines.launch

private val THEMES = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

@Composable
fun SettingsScreen(
    state: UiState,
    onSave: (AppSettings) -> Unit,
    onTest: () -> Unit,
    onTheme: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onFileTreeSelected: (String) -> Unit,
    onClearFileTree: () -> Unit,
    onShowLedger: (Boolean) -> Unit,
    onShowWallpaper: (Boolean) -> Unit,
    onWallpaperOpacity: (Float) -> Unit,
    onWallpaperDim: (Float) -> Unit,
    onSettingsDetailed: (Boolean) -> Unit,
    onOpenFiles: () -> Unit = {},
    isPreview: Boolean = false,
) {
    val settings = state.settings
    val detailed = settings.settingsDetailed
    val context = LocalContext.current
    var labUrl by rememberSaveable(settings.labUrl) { mutableStateOf(settings.labUrl) }
    var billUrl by rememberSaveable(settings.billUrl) { mutableStateOf(settings.billUrl) }
    var homarrUrl by rememberSaveable(settings.homarrUrl) { mutableStateOf(settings.homarrUrl) }
    var homarrApiKey by rememberSaveable(settings.homarrApiKey) { mutableStateOf(settings.homarrApiKey) }
    var homarrBoard by rememberSaveable(settings.homarrBoard) { mutableStateOf(settings.homarrBoard) }
    var cloudreveUrl by rememberSaveable(settings.cloudreveUrl) { mutableStateOf(settings.cloudreveUrl) }
    var cloudreveUser by rememberSaveable(settings.cloudreveUser) { mutableStateOf(settings.cloudreveUser) }
    var cloudrevePassword by rememberSaveable(settings.cloudrevePassword) {
        mutableStateOf(settings.cloudrevePassword)
    }
    var cloudreveDir by rememberSaveable(settings.cloudreveDir) { mutableStateOf(settings.cloudreveDir) }
    var photoAutoSync by rememberSaveable(settings.photoAutoSync) {
        mutableStateOf(settings.photoAutoSync)
    }
    var photoSyncStatus by remember {
        mutableStateOf(if (isPreview) "还没有同步记录" else PhotoSyncStatus.describe(context))
    }
    var syncDialogOpen by rememberSaveable { mutableStateOf(false) }
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var deviceName by rememberSaveable(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var weatherCity by rememberSaveable(settings.weatherCity) { mutableStateOf(settings.weatherCity) }
    // 已经填好的连接项默认收起，只留一行摘要，点「修改」再展开
    var labExpanded by rememberSaveable(settings.labUrl) { mutableStateOf(settings.labUrl.isBlank()) }
    var billExpanded by rememberSaveable(settings.billUrl) { mutableStateOf(settings.billUrl.isBlank()) }
    var homarrExpanded by rememberSaveable(settings.homarrUrl, settings.homarrApiKey) {
        mutableStateOf(settings.homarrUrl.isBlank() && settings.homarrApiKey.isBlank())
    }
    var cloudreveExpanded by rememberSaveable(settings.cloudreveUrl) {
        mutableStateOf(settings.cloudreveUrl.isBlank())
    }
    var wallpaperOpacity by remember(settings.wallpaperOpacity) {
        mutableFloatStateOf(settings.wallpaperOpacity)
    }
    var wallpaperDim by remember(settings.wallpaperDim) {
        mutableFloatStateOf(settings.wallpaperDim)
    }

    val selectedAi = settings.aiProviders.firstOrNull { it.id == settings.aiSelectedId }
        ?: settings.aiProviders.firstOrNull()

    var usageGranted by remember {
        mutableStateOf(if (isPreview) false else hasUsageAccess(context))
    }
    var photoGranted by remember {
        mutableStateOf(if (isPreview) false else hasPhotoAccess(context))
    }
    var automationGranted by remember {
        mutableStateOf(if (isPreview) false else UiAutomation.isEnabled(context))
    }
    var autoRestoreReady by remember {
        mutableStateOf(if (isPreview) false else AutomationRestore.canAutoRestore(context))
    }
    var splashSkip by remember {
        mutableStateOf(if (isPreview) false else SplashSkip.isEnabled(context))
    }
    var splashExtra by remember {
        mutableStateOf(if (isPreview) emptySet<String>() else SplashSkip.extraPackages(context))
    }
    var splashAppsOpen by rememberSaveable { mutableStateOf(false) }
    var splashSubsOpen by rememberSaveable { mutableStateOf(false) }
    var splashSubsVersion by remember { mutableStateOf(0) }
    if (!isPreview) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            usageGranted = hasUsageAccess(context)
            photoGranted = hasPhotoAccess(context)
            automationGranted = UiAutomation.isEnabled(context)
            autoRestoreReady = AutomationRestore.canAutoRestore(context)
            splashExtra = SplashSkip.extraPackages(context)
            photoSyncStatus = PhotoSyncStatus.describe(context)
            // 用户可能刚在系统里开了精确闹钟，回到前台时重排一次，让下一次走精确时间
            PhotoAutoSync.apply(context, settings.photoAutoSync, settings.photoSyncHour)
        }
    }

    val photoLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            photoGranted = granted
        }
    }

    val notifyLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    }

    var treeNames by remember(settings.fileTreeUris) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(settings.fileTreeUris) {
        treeNames = settings.fileTreeUris.mapIndexed { index, uri ->
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "文件夹 ${index + 1}"
        }
    }

    val treeLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onFileTreeSelected(uri.toString())
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "设置",
                subtitle = if (detailed) "连接、小砂糖、权限与外观" else null,
            )
        }

        item {
            SectionHeader("外观与首页")
        }
        item {
            CreamCard {
                Text("主题", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    THEMES.forEach { (key, label) ->
                        Pill(label, settings.themeMode == key, onClick = { onTheme(key) })
                    }
                }
                ThinDivider(Modifier.padding(vertical = 12.dp))
                Text("介绍模式", style = MaterialTheme.typography.titleMedium)
                if (detailed) {
                    Text(
                        "详细模式会显示每项功能的用途和注意事项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("详细", detailed, onClick = { onSettingsDetailed(true) })
                    Pill("简略", !detailed, onClick = { onSettingsDetailed(false) })
                }
            }
        }
        item {
            CreamCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("全局壁纸", style = MaterialTheme.typography.titleMedium)
                        SettingHint(
                            "所有页面共用这张壁纸，卡片会透出一点背景。",
                            detailed,
                            Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = settings.showWallpaper,
                        onCheckedChange = onShowWallpaper,
                    )
                }
                if (settings.showWallpaper) {
                    ThinDivider(Modifier.padding(vertical = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "壁纸透明度",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(wallpaperOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = wallpaperOpacity,
                        onValueChange = { wallpaperOpacity = it },
                        onValueChangeFinished = { onWallpaperOpacity(wallpaperOpacity) },
                        valueRange = 0.05f..0.9f,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "暗色遮罩",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(wallpaperDim * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = wallpaperDim,
                        onValueChange = { wallpaperDim = it },
                        onValueChangeFinished = { onWallpaperDim(wallpaperDim) },
                        valueRange = 0f..0.8f,
                    )
                    SettingHint(
                        "透明度越高壁纸越明显；遮罩越深文字越清楚。",
                        detailed,
                        Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item {
            CreamCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("显示账本", style = MaterialTheme.typography.titleMedium)
                        SettingHint(
                            "关闭后首页不再显示本月账本和最近流水，账单页仍可进入。",
                            detailed,
                            Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = settings.showLedgerOnHome,
                        onCheckedChange = onShowLedger,
                    )
                }
            }
        }

        item {
            CreamCard {
                Text("首页天气", style = MaterialTheme.typography.titleMedium)
                SettingHint(
                    "填城市名后，首页「更多」里会出现天气卡。数据直接来自 Open-Meteo，不经过 Homarr；留空则不显示。",
                    detailed,
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = weatherCity,
                    onValueChange = { weatherCity = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("城市名，例如 杭州") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }

        item {
            SectionHeader("连接")
        }
        item {
            CreamCard {
                ConnectionSection(
                    title = "Lab Tools",
                    summary = labUrl,
                    configured = settings.labUrl.isNotBlank(),
                    expanded = labExpanded,
                    onToggle = { labExpanded = !labExpanded },
                ) {
                    SettingHint(
                        "lab-tools 的 MCP Streamable HTTP 地址，通常带 :8765，路径会自动补 /mcp。",
                        detailed,
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = labUrl,
                        onValueChange = { labUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://100.x.x.x:8765") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                ThinDivider(Modifier.padding(vertical = 12.dp))
                ConnectionSection(
                    title = "账本",
                    summary = billUrl,
                    configured = settings.billUrl.isNotBlank(),
                    expanded = billExpanded,
                    onToggle = { billExpanded = !billExpanded },
                ) {
                    SettingHint(
                        "bill-ai 地址，本机是 :8091，tailnet 入口可能是 https://xxx.ts.net:8451。",
                        detailed,
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = billUrl,
                        onValueChange = { billUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://100.x.x.x:8091") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                ThinDivider(Modifier.padding(vertical = 12.dp))
                ConnectionSection(
                    title = "Homarr",
                    summary = homarrUrl,
                    configured = settings.homarrUrl.isNotBlank() || settings.homarrApiKey.isNotBlank(),
                    expanded = homarrExpanded,
                    onToggle = { homarrExpanded = !homarrExpanded },
                ) {
                    SettingHint(
                        "看板来源。填 tailnet 入口即可（如 https://xxx.ts.net），首页的快捷入口会换成这块看板。",
                        detailed,
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = homarrUrl,
                        onValueChange = { homarrUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://xxx.ts.net") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = homarrApiKey,
                        onValueChange = { homarrApiKey = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("API Key（管理 → Tools → API）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = homarrBoard,
                        onValueChange = { homarrBoard = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("看板名，留空自动用首页看板") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                ThinDivider(Modifier.padding(vertical = 12.dp))
                ConnectionSection(
                    title = "Cloudreve（WebDAV）",
                    summary = cloudreveUrl,
                    configured = settings.cloudreveUrl.isNotBlank(),
                    expanded = cloudreveExpanded,
                    onToggle = { cloudreveExpanded = !cloudreveExpanded },
                ) {
                    SettingHint(
                        "填站点根地址即可，会自动拼 /dav/。先在 Cloudreve 的 WebDAV 页面创建 WebDAV 账户" +
                            "（用户组要允许 WebDAV）：账号填注册邮箱，密码用该账户生成/显示的密码，不是登录密码。",
                        detailed,
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = cloudreveUrl,
                        onValueChange = { cloudreveUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://xxx.ts.net:5212") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = cloudreveUser,
                        onValueChange = { cloudreveUser = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Cloudreve 账号（邮箱）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = cloudrevePassword,
                        onValueChange = { cloudrevePassword = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Cloudreve WebDAV 密码（不是登录密码）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = cloudreveDir,
                        onValueChange = { cloudreveDir = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("上传目录，默认 手机照片") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                ThinDivider(Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("照片自动同步", style = MaterialTheme.typography.titleMedium)
                        SettingHint(
                            if (settings.photoAutoSync) {
                                "每天 ${settings.photoSyncHour}:00 · 最近 ${settings.photoSyncDays} 天" +
                                    " · 每次最多 ${settings.photoSyncLimit} 张"
                            } else {
                                "未开启。点「设置」可改时间、范围和单次张数。"
                            },
                            detailed,
                            Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = { syncDialogOpen = true }) {
                        Text("设置")
                    }
                }

                Text(
                    "账本 Token（可选）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
                )
                SettingHint(
                    "会加到账本请求的 Authorization 头，同时作为通知/短信采集上报的 X-Ingest-Token；" +
                        "要用自动记账必须填。",
                    detailed,
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("留空则账本不带认证头，也不上报采集") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Text(
                    "设备名",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
                )
                SettingHint(
                    "会作为云盘照片目录的第一级（设备名/年-月/年-月-日）。多台手机建议改成不同名字，避免同名截图混在一起。",
                    detailed,
                    Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SuccessButton(
                    text = "保存设置",
                    onClick = {
                        onSave(
                            settings.copy(
                                labUrl = labUrl.trim(),
                                billUrl = billUrl.trim(),
                                homarrUrl = homarrUrl.trim(),
                                homarrApiKey = homarrApiKey.trim(),
                                homarrBoard = homarrBoard.trim(),
                                cloudreveUrl = cloudreveUrl.trim(),
                                cloudreveUser = cloudreveUser.trim(),
                                cloudrevePassword = cloudrevePassword,
                                cloudreveDir = cloudreveDir.trim(),
                                photoAutoSync = photoAutoSync,
                                token = token.trim(),
                                deviceName = deviceName.trim(),
                                weatherCity = weatherCity.trim(),
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "测试连接",
                    onClick = onTest,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val test = state.connectionTest
        if (!test.isNullOrBlank()) {
            item {
                CreamCard {
                    Text("连接测试", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = test,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            SectionHeader("小砂糖")
        }
        item {
            CreamCard {
                Text("AI 配置", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = selectedAi?.let {
                        "当前：${it.name} · ${it.model.ifBlank { "未配置模型" }}"
                    } ?: "还没有可用的服务商",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SettingHint(
                    "服务商、模型和 API Key 全部在独立的 AI 配置页管理，AI 页只负责对话。",
                    detailed,
                    Modifier.padding(top = 5.dp, bottom = 12.dp),
                )
                SecondaryButton(
                    text = "打开 AI 配置",
                    onClick = onOpenAiSettings,
                    enabled = !isPreview,
                )
            }
        }

        item {
            CreamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "扫描范围",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction("文件洞察", onOpenFiles)
                }
                Text(
                    text = if (settings.fileTreeUris.isEmpty()) {
                        "未选择文件夹"
                    } else {
                        "已选择 ${settings.fileTreeUris.size} 个：${treeNames.joinToString("、")}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SettingHint(
                    "同一份授权用于 AI 文件搜索和文件洞察。Android 11+ 不允许选择内部存储根目录，" +
                        "请逐个添加 DCIM、Download、Documents 等文件夹；Android/data 等系统目录无法读取。只读，不会修改或删除文件。",
                    detailed,
                    Modifier.padding(top = 5.dp, bottom = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(
                        text = "添加文件夹",
                        onClick = { treeLauncher?.launch(null) },
                        modifier = Modifier.weight(1f),
                        enabled = treeLauncher != null,
                    )
                    if (settings.fileTreeUris.isNotEmpty()) {
                        SecondaryButton(
                            text = "清除全部",
                            onClick = {
                                settings.fileTreeUris.forEach { uri ->
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            Uri.parse(uri),
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                }
                                onClearFileTree()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("权限")
        }
        item {
            CreamCard {
                Text("界面自动化", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (automationGranted) "已开启" else "未开启",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (automationGranted) SuccessStrong else Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SettingHint(
                    "通过无障碍服务读取当前界面并执行点击、输入、滚动，用于“打开微信给某人发消息”这类操作。" +
                        "只在你明确要求时动作，不会后台自动点击。",
                    detailed,
                    Modifier.padding(top = 6.dp),
                )
                SettingHint(
                    "开启路径：设置 → 无障碍 → 已下载的应用 → poi's home。Android 13 及以上如果提示" +
                        "“受限设置”，需要先在应用详情页点「允许受限设置」。",
                    detailed,
                    Modifier.padding(top = 6.dp),
                )
                ThinDivider(Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("开屏广告自动跳过", style = MaterialTheme.typography.titleMedium)
                        SettingHint(
                            "只在 QQ 音乐、微博、知乎、淘宝等内置名单里，找顶部的「跳过」小按钮点一下，" +
                                "点不到就放弃，不连点，也不碰其他应用。",
                            detailed,
                            Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = splashSkip,
                        onCheckedChange = {
                            splashSkip = it
                            SplashSkip.setEnabled(context, it)
                        },
                        enabled = !isPreview,
                    )
                }
                val covered = remember(splashExtra, splashSubsVersion) {
                    if (isPreview) SplashSkip.supportedCount() else SplashSkip.packages(context).size
                }
                val subsSummary = remember(splashSubsVersion) {
                    if (isPreview) {
                        "未启用"
                    } else {
                        val enabledIds = SplashSubscriptions.enabledSources(context)
                        val names = SplashSubscriptions.sources(context)
                            .filter { it.id in enabledIds }
                            .joinToString("、") { it.name }
                        if (names.isEmpty()) "未启用，只用内置名单" else names
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "开屏跳过应用：$covered 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction(
                        text = "管理",
                        onClick = { splashAppsOpen = true },
                        enabled = !isPreview,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "规则订阅：$subsSummary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    InlineAction(
                        text = "订阅",
                        onClick = { splashSubsOpen = true },
                        enabled = !isPreview,
                    )
                }
                ThinDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    text = if (autoRestoreReady) {
                        "重启自动恢复：已就绪"
                    } else {
                        "重启自动恢复：未启用。HyperOS 重启会收回 adb 安装应用的无障碍开关，需要一次性授权：" +
                            "\nadb shell pm grant com.poi.homelab android.permission.WRITE_SECURE_SETTINGS"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (autoRestoreReady) {
                        SuccessStrong
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                SecondaryButton(
                    text = if (automationGranted) "查看无障碍设置" else "打开无障碍设置",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    enabled = !isPreview,
                )
            }
        }
        item {
            CreamCard {
                Text("使用情况访问", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (usageGranted) "已授权" else "未授权",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (usageGranted) SuccessStrong else Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SettingHint(
                    "这个权限不在应用详情页的权限列表里，需要去系统设置单独开：设置 → 应用 → " +
                        "特殊应用权限 → 使用情况访问（部分机型：设置 → 安全与隐私 → 隐私 → " +
                        "使用情况访问），找到「poi's home」打开。",
                    detailed,
                    Modifier.padding(top = 6.dp),
                )
                SettingHint(
                    "它只用于统计各应用最近 24 小时的使用时长和存储占用。即使授权，Android 也不会把" +
                        "其他应用的运行内存开放给普通应用，内存排行依然拿不到。",
                    detailed,
                    Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                SecondaryButton(
                    text = if (usageGranted) "查看授权页面" else "打开使用情况访问",
                    onClick = { openUsageAccess(context) },
                    enabled = !isPreview,
                )
            }
        }
        item {
            CreamCard {
                Text("相册", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (photoGranted) "已授权" else "未授权",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (photoGranted) SuccessStrong else Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SettingHint(
                    "用于按关键词找图、识图和生图。只读取照片，不会修改或删除。",
                    detailed,
                    Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                if (!photoGranted) {
                    SecondaryButton(
                        text = "授权相册",
                        onClick = { photoLauncher?.launch(photoPermission) },
                        enabled = !isPreview && photoLauncher != null,
                    )
                }
            }
        }

        item {
            CaptureSettingsCard(detailed = detailed, isPreview = isPreview)
        }

        item {
            SectionHeader("关于")
        }
        item {
            CreamCard {
                Text("poi's home 1.0.0", style = MaterialTheme.typography.titleMedium)
                if (detailed) {
                    Text(
                        "一个自用的 Homelab 控制台：容器状态与启停、账本、下载入口和只读小砂糖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ThinDivider(Modifier.padding(vertical = 12.dp))
                    Text(
                        "小砂糖默认只读：不会修改/删除文件，也不会启停容器。开启无障碍后，它可以在你明确要求时" +
                            "点击和输入其他应用。地址、Key 与目录授权只保存在本机 DataStore；" +
                            "对话和工具结果会发送给你选择的 AI 服务商。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThinDivider(Modifier.padding(vertical = 12.dp))
                    Text(
                        "当前版本聚焦 Home 控制台；短信/通知账单采集会在后续版本接回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (splashAppsOpen && !isPreview) {
        SplashAppsDialog(
            context = context,
            initialExtra = splashExtra,
            onExtraChange = { splashExtra = it },
            onDismiss = { splashAppsOpen = false },
        )
    }

    if (splashSubsOpen && !isPreview) {
        SplashSubsDialog(
            context = context,
            onChanged = { splashSubsVersion++ },
            onDismiss = {
                splashSubsOpen = false
                splashSubsVersion++
            },
        )
    }

    if (syncDialogOpen) {
        PhotoSyncDialog(
            settings = settings,
            status = photoSyncStatus,
            canSyncNow = !isPreview && cloudreveUrl.isNotBlank() && photoGranted,
            exactAlarmBlocked = !isPreview && !PhotoAutoSync.canScheduleExact(context),
            batteryUnrestricted = !isPreview && PhotoAutoSync.isIgnoringBatteryOptimizations(context),
            onOpenBatterySettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
            onOpenExactAlarm = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            },
            onSyncNow = {
                PhotoAutoSync.runNow(context)
                photoSyncStatus = "已开始同步，通知栏会显示进度"
            },
            onDismiss = { syncDialogOpen = false },
            onSave = { enabled, hour, days, limit ->
                syncDialogOpen = false
                photoAutoSync = enabled
                if (enabled && !isPreview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifyLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                onSave(
                    settings.copy(
                        labUrl = labUrl.trim(),
                        billUrl = billUrl.trim(),
                        homarrUrl = homarrUrl.trim(),
                        homarrApiKey = homarrApiKey.trim(),
                        homarrBoard = homarrBoard.trim(),
                        cloudreveUrl = cloudreveUrl.trim(),
                        cloudreveUser = cloudreveUser.trim(),
                        cloudrevePassword = cloudrevePassword,
                        cloudreveDir = cloudreveDir.trim(),
                        photoAutoSync = enabled,
                        photoSyncHour = hour,
                        photoSyncDays = days,
                        photoSyncLimit = limit,
                        token = token.trim(),
                        deviceName = deviceName.trim(),
                    ),
                )
            },
        )
    }
}

/**
 * 开屏跳过的应用名单：内置的锁成常开，其他应用按需打开。
 * 只对能在桌面上点开的 App 生效，避免把系统组件加进来。
 */
@Composable
private fun SplashAppsDialog(
    context: Context,
    initialExtra: Set<String>,
    onExtraChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(initialExtra) }
    val apps = remember { launchableApps(context) }
    val builtin = remember { SplashSkip.builtinPackages() }
    val filtered = remember(query, apps) {
        val keyword = query.trim()
        if (keyword.isEmpty()) apps else apps.filter {
            it.first.contains(keyword, true) || it.second.contains(keyword, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开屏跳过应用") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(
                    text = "内置 ${builtin.size} 个常见应用，常开；下面可以再加你常用的 App。" +
                        "只会点带「跳过」字样的小按钮，找不到就放弃。订阅带来的应用不在这里列。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("搜索应用") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                LazyColumn(Modifier.padding(top = 6.dp)) {
                    items(filtered) { app ->
                        val pkg = app.second
                        val locked = pkg in builtin
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.first, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (locked) "$pkg · 内置" else pkg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = locked || pkg in extra,
                                onCheckedChange = { on ->
                                    extra = if (on) extra + pkg else extra - pkg
                                    if (locked) {
                                        // 内置项不允许关，开关只做展示
                                    } else if (on) {
                                        SplashSkip.addPackage(context, pkg)
                                    } else {
                                        SplashSkip.removePackage(context, pkg)
                                    }
                                    onExtraChange(extra)
                                },
                                enabled = !locked,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 开屏跳过订阅：只从订阅里取「哪些应用有开屏广告」，点哪里、点几次仍由 [SplashSkip] 自己判断。
 * 默认全部关闭，启用得越多白名单越大。
 */
@Composable
private fun SplashSubsDialog(
    context: Context,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(SplashSubscriptions.sources(context)) }
    val installed = remember { launchableApps(context).associate { it.second to it.first } }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开屏跳过订阅") },
        text = {
            Column(Modifier.heightIn(max = 500.dp)) {
                Text(
                    text = "订阅只用来判断「哪些应用有开屏广告」，点哪里、点几次仍由本机决定。" +
                        "默认全部关闭；启用越多白名单越大，建议最多开一个。" +
                        "订阅里的「全局开屏规则」会对所有应用生效，这里不启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(sources, key = { it.id }) { source ->
                        val pkgs = remember(source.id, version) {
                            SplashSubscriptions.packages(context, source.id)
                        }
                        val hits = remember(pkgs, installed) { pkgs.mapNotNull { installed[it] } }
                        val enabled = remember(source.id, version) {
                            SplashSubscriptions.isEnabled(context, source.id)
                        }
                        val synced = remember(source.id, version) {
                            SplashSubscriptions.syncedAt(context, source.id) > 0L
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = source.desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { on ->
                                        if (on && !synced) {
                                            message = "先点「同步」拿到名单再启用"
                                        } else {
                                            SplashSubscriptions.setEnabled(context, source.id, on)
                                            version++
                                            onChanged()
                                        }
                                    },
                                )
                            }
                            Text(
                                text = when {
                                    busy == source.id -> "同步中…"
                                    !synced -> "未同步"
                                    hits.isEmpty() -> "已同步 ${pkgs.size} 个，本机没有命中"
                                    else -> "已同步 ${pkgs.size} 个，本机命中 ${hits.size} 个：" +
                                        hits.take(6).joinToString("、") +
                                        if (hits.size > 6) " 等" else ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InlineAction(
                                    text = if (synced) "更新" else "同步",
                                    onClick = {
                                        scope.launch {
                                            busy = source.id
                                            val result = SplashSubscriptions.sync(context, source)
                                            busy = null
                                            version++
                                            message = result.fold(
                                                onSuccess = { "已同步 ${source.name}：$it 个应用" },
                                                onFailure = { "同步失败：${it.message}" },
                                            )
                                        }
                                    },
                                    enabled = busy == null,
                                )
                                if (source.custom) {
                                    InlineAction(
                                        text = "移除",
                                        onClick = {
                                            SplashSubscriptions.removeSource(context, source.id)
                                            sources = SplashSubscriptions.sources(context)
                                            version++
                                            onChanged()
                                        },
                                        enabled = busy == null,
                                    )
                                }
                            }
                        }
                        ThinDivider()
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { customUrl = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("自定义订阅地址") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                            )
                            TextButton(
                                onClick = {
                                    val added = SplashSubscriptions.addCustomSource(context, customUrl)
                                    if (added == null) {
                                        message = "地址无效或已存在"
                                    } else {
                                        message = "已添加 ${added.name}"
                                        customUrl = ""
                                        sources = SplashSubscriptions.sources(context)
                                        version++
                                    }
                                },
                            ) { Text("添加") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 能出现在桌面上的应用，按名字排序。 */
private fun launchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm).toString().ifBlank { pkg }
            label to pkg
        }
        .distinctBy { it.second }
        .sortedBy { it.first.lowercase() }
}

@Composable
private fun PhotoSyncDialog(
    settings: AppSettings,
    status: String,
    canSyncNow: Boolean,
    exactAlarmBlocked: Boolean,
    batteryUnrestricted: Boolean,
    onOpenExactAlarm: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, hour: Int, days: Int, limit: Int) -> Unit,
) {
    var enabled by remember(settings.photoAutoSync) { mutableStateOf(settings.photoAutoSync) }
    var hour by remember(settings.photoSyncHour) { mutableStateOf(settings.photoSyncHour.toString()) }
    var days by remember(settings.photoSyncDays) { mutableStateOf(settings.photoSyncDays.toString()) }
    var limit by remember(settings.photoSyncLimit) { mutableStateOf(settings.photoSyncLimit.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("照片自动同步") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每天自动上传", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "到点由前台服务上传，通知栏显示进度，可随时取消。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = hour,
                    onValueChange = { value ->
                        if (value.length <= 2 && value.all { it.isDigit() }) hour = value
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("每天几点开始（0-23）") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = days,
                    onValueChange = { value ->
                        if (value.length <= 2 && value.all { it.isDigit() }) days = value
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("同步最近几天（1-30）") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.all { it.isDigit() }) limit = value
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("单次最多几张（1-500）") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    text = "上次同步：$status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (enabled && exactAlarmBlocked) {
                    Text(
                        text = "系统还没允许「精确闹钟」，凌晨可能延迟几十分钟。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = onOpenExactAlarm) {
                        Text("去允许精确闹钟")
                    }
                }
                if (enabled && !batteryUnrestricted) {
                    Text(
                        text = "系统还在限制本应用后台，长时间息屏可能被冻结，导致同步不跑。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = onOpenBatterySettings) {
                        Text("允许后台不限电")
                    }
                }
                SecondaryButton(
                    text = "立即同步一次",
                    onClick = onSyncNow,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = canSyncNow,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        enabled,
                        hour.toIntOrNull()?.coerceIn(0, 23) ?: settings.photoSyncHour,
                        days.toIntOrNull()?.coerceIn(1, 30) ?: settings.photoSyncDays,
                        limit.toIntOrNull()?.coerceIn(1, 500) ?: settings.photoSyncLimit,
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SettingHint(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    if (visible) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    } else {
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 连接项：已经填过的默认只留标题和一行摘要，点「修改」再展开输入框；
 * 没配置过的直接展开，省一次点击。
 */
@Composable
private fun ConnectionSection(
    title: String,
    summary: String,
    configured: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (configured) {
            InlineAction(text = if (expanded) "收起" else "修改", onClick = onToggle)
        }
    }
    if (expanded || !configured) {
        content()
    } else {
        Text(
            text = summary.ifBlank { "已配置" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 使用情况访问是特殊权限，找不到对应页面时逐级退回设置页和应用详情页。 */
private fun openUsageAccess(context: Context) {
    val targets = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
    for (intent in targets) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
