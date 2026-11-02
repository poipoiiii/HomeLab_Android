package com.poi.homelab.feature.imageedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.navigation.UiState

/**
 * 修图网关的连接与默认参数。
 *
 * 只影响 AI 修图，不碰小砂糖的对话服务商；Token 只存本机 DataStore。
 */
@Composable
fun ImageEditSettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onTest: () -> Unit,
    onSave: (url: String, token: String, model: String, steps: Int, maxSide: Int, autoSave: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val edit = state.imageEdit

    var url by rememberSaveable(settings.imageEditUrl) { mutableStateOf(settings.imageEditUrl) }
    var token by rememberSaveable(settings.imageEditToken) { mutableStateOf(settings.imageEditToken) }
    var model by rememberSaveable(settings.imageEditModel) { mutableStateOf(settings.imageEditModel) }
    var steps by rememberSaveable(settings.imageEditSteps) {
        mutableFloatStateOf(settings.imageEditSteps.toFloat())
    }
    var maxSide by rememberSaveable(settings.imageEditMaxSide) {
        mutableFloatStateOf(settings.imageEditMaxSide.toFloat())
    }
    var autoSave by rememberSaveable(settings.imageEditAutoSave) {
        mutableStateOf(settings.imageEditAutoSave)
    }

    fun save() {
        onSave(
            url.trim(),
            token.trim(),
            model.trim(),
            steps.toInt().coerceIn(20, 60),
            maxSide.toInt().coerceIn(768, 2048),
            autoSave,
        )
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 修图服务", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "只影响修图，和上面小砂糖的对话服务商分开。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    InlineAction("返回", onBack)
                }
            }

            item { SectionHeader("连接") }
            item {
                CreamCard {
                    Text("网关地址", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "家里 img-edit-gateway 的地址，例如 http://100.x.x.x:8094 或 http://192.168.1.10:8094。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("地址") },
                        placeholder = { Text("http://") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        label = { Text("Token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Text(
                        "Token 来自服务端 .env 的 IMAGE_EDIT_TOKEN，只存在这台手机里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            item { SectionHeader("默认参数") }
            item {
                CreamCard {
                    Text("推理步数", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${steps.toInt()} 步",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "20–60，越高越慢",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = steps,
                        onValueChange = { steps = it },
                        valueRange = 20f..60f,
                        steps = 7,
                    )

                    Text(
                        "上传最长边",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${maxSide.toInt()} px",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "越大越清晰，上传越慢",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = maxSide,
                        onValueChange = { maxSide = it },
                        valueRange = 768f..2048f,
                        steps = 9,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("完成后自动保存", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "任务成功就写进相册，同一张不会重复保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Switch(checked = autoSave, onCheckedChange = { autoSave = it })
                    }

                    Text(
                        "模型",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.trim() },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = { Text("模型标识") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }

            item {
                CreamCard {
                    SectionHeader(
                        title = "测试连接",
                        subtitle = "调 /health 看模型与队列状态",
                        action = { InlineAction("测试", onTest, enabled = !edit.healthLoading) },
                    )
                    Spacer(Modifier.height(8.dp))
                    val health = edit.health
                    val healthError = edit.healthError
                    when {
                        edit.healthLoading -> Text(
                            "正在连接…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        healthError != null -> Text(
                            text = healthError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Danger,
                        )
                        health != null -> {
                            val online = health.status == "ok"
                            Text(
                                text = when {
                                    online -> "模型在线"
                                    health.status == "warming_up" -> "模型预热中"
                                    health.status == "degraded" -> "服务降级"
                                    else -> "状态：${health.status.ifBlank { "未知" }}"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (online) SuccessStrong else Danger,
                            )
                            Text(
                                text = buildString {
                                    if (health.model.isNotBlank()) append(health.model)
                                    if (health.backend.isNotBlank()) append(" · ${health.backend}")
                                    append(" · 队列 ${health.queue.queued}，运行 ${health.queue.running}")
                                    health.vramUsedMb?.let { append(" · 显存 ${it} MB") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        else -> Text(
                            "还没测试过。先保存地址与 Token，再点右上角「测试」。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(text = "保存", onClick = { save() })
                }
            }

            item {
                Text(
                    "图片只会发到家里的服务器，不经过 DeepSeek、OpenAI 等服务商；结果在服务端保存 7 天，手机相册里的副本由你自己管理。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
