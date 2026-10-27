package com.poi.homelab.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.poi.homelab.automation.AutomationRestore
import com.poi.homelab.automation.UiAutomation
import com.poi.homelab.data.AppSettings
import com.poi.homelab.ui.UiState
import com.poi.homelab.ui.components.CreamCard
import com.poi.homelab.ui.components.Pill
import com.poi.homelab.ui.components.SecondaryButton
import com.poi.homelab.ui.components.SectionHeader
import com.poi.homelab.ui.components.SuccessButton
import com.poi.homelab.ui.components.ThinDivider
import com.poi.homelab.ui.theme.Danger
import com.poi.homelab.ui.theme.SuccessStrong
import com.poi.homelab.util.hasPhotoAccess
import com.poi.homelab.util.hasUsageAccess
import com.poi.homelab.util.photoPermission

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
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var deviceName by rememberSaveable(settings.deviceName) { mutableStateOf(settings.deviceName) }
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
    if (!isPreview) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            usageGranted = hasUsageAccess(context)
            photoGranted = hasPhotoAccess(context)
            automationGranted = UiAutomation.isEnabled(context)
            autoRestoreReady = AutomationRestore.canAutoRestore(context)
        }
    }

    val photoLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            photoGranted = granted
        }
    }

    var treeName by remember(settings.fileTreeUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.fileTreeUri) {
        treeName = if (settings.fileTreeUri.isBlank()) {
            null
        } else {
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(settings.fileTreeUri))?.name
            }.getOrNull()
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
            SectionHeader("连接")
        }
        item {
            CreamCard {
                Text("Lab Tools", style = MaterialTheme.typography.titleMedium)
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

                Text(
                    "账本",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
                )
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

                Text(
                    "Homarr",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
                )
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

                Text(
                    "Bearer Token（可选）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
                )
                SettingHint(
                    "只会加到账本请求的 Authorization 头；当前 lab-tools 不需要。",
                    detailed,
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("留空表示不加认证头") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Text(
                    "设备名",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
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
                                token = token.trim(),
                                deviceName = deviceName.trim(),
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
                Text("可搜索目录", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (settings.fileTreeUri.isBlank()) {
                        "未选择目录"
                    } else {
                        "已选择：${treeName ?: settings.fileTreeUri}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SettingHint(
                    "AI 只会在你授权的目录内按文件名查找，只读，不会修改或删除文件。",
                    detailed,
                    Modifier.padding(top = 5.dp, bottom = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(
                        text = "选择目录",
                        onClick = { treeLauncher?.launch(null) },
                        modifier = Modifier.weight(1f),
                        enabled = treeLauncher != null,
                    )
                    if (settings.fileTreeUri.isNotBlank()) {
                        SecondaryButton(
                            text = "清除",
                            onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(settings.fileTreeUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
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
