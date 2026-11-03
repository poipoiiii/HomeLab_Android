package com.poi.homelab.feature.device.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.StatusDot
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.feature.device.DeviceConfig
import com.poi.homelab.feature.device.DeviceLink
import com.poi.homelab.feature.device.DeviceUiState
import kotlinx.coroutines.delay

/** 首页设备卡：只读显示 rong 的状态快照，配置收进底部弹层。 */
@Composable
fun DeviceCard(
    state: DeviceUiState,
    onSave: (DeviceConfig) -> Unit,
) {
    var configOpen by rememberSaveable { mutableStateOf(false) }
    val connected = state.link == DeviceLink.Connected
    val now = rememberClock(connected)
    val fresh = state.lastMessageAt > 0 && now - state.lastMessageAt <= 90_000
    val online = connected && !state.deviceOffline && fresh

    val dotColor = when {
        online -> Success
        state.link == DeviceLink.Failed -> Danger
        connected -> Warning
        state.link == DeviceLink.Connecting -> Warning
        else -> Muted
    }
    val title = when {
        !state.config.configured -> "未配置"
        online -> "在线"
        state.link == DeviceLink.Connecting -> "连接中"
        connected && state.deviceOffline -> "设备离线"
        connected -> "等待上报"
        else -> "未连接"
    }
    val status = when {
        !state.config.configured -> "填 broker 地址和密码后，这里显示 rong 的实时状态。"
        state.link == DeviceLink.Failed -> state.error ?: "连接失败，稍后自动重试"
        state.link == DeviceLink.Connecting -> "正在连 ${state.config.host}:${state.config.port}"
        connected && state.deviceOffline -> "broker 在线，但设备标记为离线"
        connected && !fresh -> "broker 已连接，还没有收到 ${state.config.dev} 的数据"
        online -> null
        else -> "已暂停，回到首页会重新连接"
    }

    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "设备",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(dotColor, 8)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            InlineAction(
                text = if (state.config.configured) "配置" else "连接",
                onClick = { configOpen = true },
            )
        }

        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            val facts = buildList {
                state.snapshot?.uptimeSeconds?.let { add("已运行 ${uptimeText(it)}") }
                state.snapshot?.led?.let { add(if (it == 0) "LED 关" else "LED 开") }
                state.telemetry?.ip?.takeIf { it.isNotBlank() }?.let { add(it) }
                state.telemetry?.rssi?.let { add("RSSI $it") }
            }
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (state.lastMessageAt > 0) {
            Text(
                text = "最近上报 ${agoText(now, state.lastMessageAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (configOpen) {
        DeviceConfigSheet(
            state = state,
            onDismiss = { configOpen = false },
            onSave = {
                onSave(it)
                configOpen = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceConfigSheet(
    state: DeviceUiState,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
    val config = state.config
    var host by rememberSaveable(config.host) { mutableStateOf(config.host) }
    var port by rememberSaveable(config.port) { mutableStateOf(config.port.toString()) }
    var user by rememberSaveable(config.user) { mutableStateOf(config.user) }
    var password by rememberSaveable(config.password) { mutableStateOf(config.password) }
    var site by rememberSaveable(config.site) { mutableStateOf(config.site) }
    var dev by rememberSaveable(config.dev) { mutableStateOf(config.dev) }
    var reveal by rememberSaveable { mutableStateOf(false) }

    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val canSave = host.isNotBlank() && user.isNotBlank() && password.isNotBlank() && portValue != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "设备连接",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("关闭", onDismiss)
            }
            Text(
                text = "MQTT 走明文 1883。用户名填 broker 上的 app 账号，密码由家里的初始化脚本生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                label = { Text("Broker 地址") },
                placeholder = { Text("公网域名或内网 IP") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { input -> port = input.filter(Char::isDigit).take(5) },
                    modifier = Modifier.weight(1f),
                    label = { Text("端口") },
                    singleLine = true,
                    isError = port.isNotEmpty() && portValue == null,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("用户名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("密码") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { reveal = !reveal }) {
                        Text(if (reveal) "隐藏" else "显示")
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = site,
                    onValueChange = { site = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("站点") },
                    placeholder = { Text("dorm") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = dev,
                    onValueChange = { dev = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("设备") },
                    placeholder = { Text("rong") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            Text(
                text = "订阅 home/$site/stm32/$dev/state 与 /telemetry",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = 10.dp),
            )

            if (state.error != null) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            PrimaryButton(
                text = "保存并连接",
                onClick = {
                    onSave(
                        DeviceConfig(
                            host = host.trim(),
                            port = portValue ?: 1883,
                            user = user.trim(),
                            password = password,
                            site = site.trim().ifBlank { "dorm" },
                            dev = dev.trim().ifBlank { "rong" },
                        ),
                    )
                },
                modifier = Modifier.padding(top = 16.dp),
                enabled = canSave,
            )
        }
    }
}

/** 连接期间每 5 秒刷新一次，用来判断数据是否还算「新鲜」。 */
@Composable
private fun rememberClock(active: Boolean): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    return now
}

private fun uptimeText(totalSeconds: Int): String {
    if (totalSeconds < 60) return "$totalSeconds 秒"
    val minutes = totalSeconds / 60
    if (minutes < 60) return "$minutes 分钟"
    val hours = minutes / 60
    if (hours < 24) return "$hours 小时 ${minutes % 60} 分"
    return "${hours / 24} 天 ${hours % 24} 小时"
}

private fun agoText(now: Long, at: Long): String {
    val seconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds 秒前"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86_400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86_400} 天前"
    }
}
