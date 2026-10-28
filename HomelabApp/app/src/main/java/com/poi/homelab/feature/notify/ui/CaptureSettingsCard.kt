package com.poi.homelab.feature.notify.ui

import android.content.Context
import android.content.Intent
import android.Manifest
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.MonospaceBlock
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.feature.notify.CaptureLogs
import com.poi.homelab.feature.notify.CaptureStatus
import com.poi.homelab.feature.notify.NotifyPrefs
import com.poi.homelab.feature.notify.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CaptureUi(
    val notification: Boolean = true,
    val sms: Boolean = true,
    val allApps: Boolean = false,
    val listenerReady: Boolean = false,
    val smsReady: Boolean = false,
    val pending: Int = 0,
    val configured: Boolean = false,
    val logs: String = "还没有记录",
)

private fun readCapture(context: Context) = CaptureUi(
    notification = NotifyPrefs.notificationEnabled(context),
    sms = NotifyPrefs.smsEnabled(context),
    allApps = NotifyPrefs.allAppsEnabled(context),
    listenerReady = CaptureStatus.notificationAccessGranted(context),
    smsReady = CaptureStatus.smsGranted(context),
    pending = CaptureStatus.pending(context),
    configured = CaptureStatus.configured(context),
    logs = CaptureLogs.tail(context, 6),
)

/** 预览里不碰 SharedPreferences 与文件系统，只展示静态样子。 */
private fun previewCapture() = CaptureUi(
    listenerReady = true,
    smsReady = false,
    pending = 2,
    configured = true,
    logs = "08-14 12:03  采集通知：微信支付凭证（队列 1）\n08-14 12:03  已上报 1 条",
)

/**
 * 记账采集（通知监听 + 短信）的设置卡片。
 *
 * 服务器地址与上报凭证不在这里填，统一取「连接 → 账本」那一组，避免两处配置不一致。
 */
@Composable
fun CaptureSettingsCard(
    detailed: Boolean,
    modifier: Modifier = Modifier,
    isPreview: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ui by remember { mutableStateOf(if (isPreview) previewCapture() else readCapture(context)) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (!isPreview) ui = readCapture(context)
    }

    if (!isPreview) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    }

    val smsLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    }

    CreamCard(modifier) {
        Text("通知与短信采集", style = MaterialTheme.typography.titleMedium)
        Hint(
            "把微信、支付宝、银行 App 的支付通知和银行短信上报到账本，先进待入账队列，确认后才写进流水。",
            detailed,
            Modifier.padding(top = 4.dp),
        )

        ThinDivider(Modifier.padding(vertical = 12.dp))

        StatusLine("账本地址与凭证", if (ui.configured) "已同步" else "未配置", ui.configured)
        StatusLine("通知使用权", if (ui.listenerReady) "已开启" else "未开启", ui.listenerReady)
        StatusLine("短信权限", if (ui.smsReady) "已授予" else "未授予", ui.smsReady)
        StatusLine("待补发", "${ui.pending} 条", ui.pending == 0)

        ThinDivider(Modifier.padding(vertical = 12.dp))

        ToggleRow("采集通知", "微信 / 支付宝 / 银行的推送", ui.notification, detailed, !isPreview) { checked ->
            ui = ui.copy(notification = checked)
            NotifyPrefs.setNotificationEnabled(context, checked)
        }
        ToggleRow("采集短信", "银行与支付的短信通知", ui.sms, detailed, !isPreview) { checked ->
            ui = ui.copy(sms = checked)
            NotifyPrefs.setSmsEnabled(context, checked)
        }
        ToggleRow("监听所有应用的通知", "关闭时只认白名单里的应用", ui.allApps, detailed, !isPreview) { checked ->
            ui = ui.copy(allApps = checked)
            NotifyPrefs.setAllAppsEnabled(context, checked)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(
                text = "通知使用权设置",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isPreview,
            )
            SecondaryButton(
                text = "立即补发",
                onClick = {
                    busy = true
                    scope.launch {
                        val summary = withContext(Dispatchers.IO) { Uploader.flush(context) }
                        busy = false
                        result = summary
                        refresh()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !busy && !isPreview,
                loading = busy,
            )
        }

        if (!ui.smsReady && smsLauncher != null) {
            SecondaryButton(
                text = "授予短信权限",
                onClick = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) },
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Hint(
            "HyperOS 还需要：设置 → 应用设置 → 本应用，打开「自启动」，省电策略选「无限制」，并在最近任务里锁定，否则后台收不到通知。",
            detailed,
            Modifier.padding(top = 10.dp),
        )

        result?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            "最近采集与发送",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        MonospaceBlock(ui.logs, maxHeight = 180)
    }
}

@Composable
private fun Hint(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        modifier = modifier,
    )
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (positive) SuccessStrong else Warning,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    detailed: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detailed) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
