package com.rong.homelab.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rong.homelab.data.AppSettings
import com.rong.homelab.ui.UiState
import com.rong.homelab.ui.components.CreamCard
import com.rong.homelab.ui.components.Pill
import com.rong.homelab.ui.components.PrimaryButton
import com.rong.homelab.ui.components.SecondaryButton
import com.rong.homelab.ui.components.SectionHeader
import com.rong.homelab.ui.components.ThinDivider

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
) {
    val settings = state.settings
    var labUrl by rememberSaveable(settings.labUrl) { mutableStateOf(settings.labUrl) }
    var billUrl by rememberSaveable(settings.billUrl) { mutableStateOf(settings.billUrl) }
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var deviceName by rememberSaveable(settings.deviceName) { mutableStateOf(settings.deviceName) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(title = "设置", subtitle = "连接、外观与诊断")
        }

        item {
            CreamCard {
                Text("Lab Tools", style = MaterialTheme.typography.titleMedium)
                Text(
                    "lab-tools 的 MCP Streamable HTTP 地址，通常带 :8765，路径会自动补 /mcp。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
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
                Text(
                    "bill-ai 地址，本机是 :8091，tailnet 入口可能是 https://xxx.ts.net:8451。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
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
                    "Bearer Token（可选）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    "只会加到账本请求的 Authorization 头；当前 lab-tools 不需要。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
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
            PrimaryButton(
                text = "保存设置",
                onClick = {
                    onSave(
                        AppSettings(
                            labUrl = labUrl.trim(),
                            billUrl = billUrl.trim(),
                            token = token.trim(),
                            deviceName = deviceName.trim(),
                            themeMode = settings.themeMode,
                        ),
                    )
                },
            )
        }
        item {
            SecondaryButton(text = "测试连接", onClick = onTest)
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
            SectionHeader("外观")
        }
        item {
            CreamCard {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    THEMES.forEach { (key, label) ->
                        Pill(label, settings.themeMode == key, onClick = { onTheme(key) })
                    }
                }
            }
        }

        item {
            SectionHeader("关于")
        }
        item {
            CreamCard {
                Text("poi的实验室 1.0.0", style = MaterialTheme.typography.titleMedium)
                Text(
                    "一个自用的 Homelab 控制台：容器状态与启停、账本、下载入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ThinDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    "地址与 Token 只保存在本机 DataStore；所有请求直接发往你填写的服务器，不经过第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThinDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    "当前版本聚焦实验室控制台；短信/通知账单采集会在后续版本接回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
