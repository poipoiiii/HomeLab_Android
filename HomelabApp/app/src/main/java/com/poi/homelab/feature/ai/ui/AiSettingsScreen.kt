package com.poi.homelab.feature.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.SuccessButton
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.feature.ai.AiProvider
import com.poi.homelab.feature.ai.requiresKey
import com.poi.homelab.navigation.UiState

@Composable
fun AiSettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onSaveAi: (AiProvider) -> Unit,
    onSelectAi: (String) -> Unit,
) {
    val settings = state.settings
    val selectedAi = settings.aiProviders.firstOrNull { it.id == settings.aiSelectedId }
        ?: settings.aiProviders.firstOrNull()

    var aiBaseUrl by rememberSaveable(selectedAi?.id, selectedAi?.baseUrl) {
        mutableStateOf(selectedAi?.baseUrl.orEmpty())
    }
    var aiModel by rememberSaveable(selectedAi?.id, selectedAi?.model) {
        mutableStateOf(selectedAi?.model.orEmpty())
    }
    var aiVision by rememberSaveable(selectedAi?.id, selectedAi?.visionModel) {
        mutableStateOf(selectedAi?.visionModel.orEmpty())
    }
    var aiImage by rememberSaveable(selectedAi?.id, selectedAi?.imageModel) {
        mutableStateOf(selectedAi?.imageModel.orEmpty())
    }
    var aiKey by rememberSaveable(selectedAi?.id, selectedAi?.apiKey) {
        mutableStateOf(selectedAi?.apiKey.orEmpty())
    }

    Scaffold(
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("AI 配置", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "只影响小砂糖的对话，和 Home 连接分开管理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            InlineAction("返回", onBack)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                CreamCard {
                    Text("模型服务商", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        settings.aiProviders.forEach { provider ->
                            Pill(
                                text = provider.name,
                                selected = provider.id == settings.aiSelectedId,
                                onClick = { onSelectAi(provider.id) },
                            )
                        }
                    }

                    if (selectedAi == null) {
                        Text(
                            "还没有可用的 AI 服务商。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        Text(
                            "Base URL",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        OutlinedTextField(
                            value = aiBaseUrl,
                            onValueChange = { aiBaseUrl = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = { Text("https://api.deepseek.com/v1") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )

                        Text(
                            "模型",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = aiModel,
                            onValueChange = { aiModel = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = { Text("deepseek-chat") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )

                        Text(
                            "识图模型（可选）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = aiVision,
                            onValueChange = { aiVision = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = {
                                Text(
                                    if (selectedAi.id == "deepseek") {
                                        "deepseek-flash（默认）"
                                    } else {
                                        "qwen-vl-max / gpt-4o"
                                    },
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Text(
                            "留空则自动用默认识图模型（DeepSeek 为 deepseek-flash）；需要服务商支持图片输入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        Text(
                            "生图模型（可选）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = aiImage,
                            onValueChange = { aiImage = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = { Text("gpt-image-1 / dall-e-3") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Text(
                            "留空则用上面的模型；需要服务商支持 OpenAI 兼容的 /v1/images/generations。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        Text(
                            "API Key",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = aiKey,
                            onValueChange = { aiKey = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = { Text("sk-…") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        if (aiKey.isBlank() && selectedAi.requiresKey) {
                            Text(
                                "这个服务商需要 API Key，填好后再回小砂糖页对话。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Text(
                            "Key 只保存在本机 DataStore；对话内容和工具结果会发送给所选服务商。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        )
                        SuccessButton(
                            text = "保存 ${selectedAi.name} 配置",
                            onClick = {
                                onSaveAi(
                                    selectedAi.copy(
                                        baseUrl = aiBaseUrl.trim(),
                                        model = aiModel.trim(),
                                        apiKey = aiKey.trim(),
                                        visionModel = aiVision.trim(),
                                        imageModel = aiImage.trim(),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        }
    }
}
