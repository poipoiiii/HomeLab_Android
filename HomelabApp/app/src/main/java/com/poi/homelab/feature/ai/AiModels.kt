package com.poi.homelab.feature.ai

import kotlinx.serialization.Serializable

/** 一条对话消息，会持久化到 [ChatStore]。 */
@Serializable
data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val toolName: String? = null,
    /** 相册工具找到/识别的照片 uri，用来在工具结果里显示缩略图。 */
    val photos: List<String> = emptyList(),
    /** 消息时间；旧记录缺省为当前时间，仅用于界面按天分组。 */
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    /** 识图模型，留空则用 [model]；需要服务商支持图片输入。 */
    val visionModel: String = "",
    /** 生图模型，留空则用 [model]；需要服务商支持 /v1/images/generations。 */
    val imageModel: String = "",
)

val AiProvider.requiresKey: Boolean
    get() = id in setOf("deepseek", "openai", "moonshot", "qwen", "siliconflow")

/** 识图模型：优先用单独配置的，没有则按服务商给默认值。 */
val AiProvider.effectiveVisionModel: String
    get() = visionModel.ifBlank {
        if (id == "deepseek" || baseUrl.contains("api.deepseek.com")) {
            "deepseek-flash"
        } else {
            model
        }
    }

/** ADK 的 Chat Completions 模型会在 baseUrl 后追加 /chat/completions，这里对齐旧逻辑。 */
internal fun AiProvider.openAiBaseUrl(): String {
    var trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.endsWith("/chat/completions")) {
        trimmed = trimmed.removeSuffix("/chat/completions").trimEnd('/')
    }
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}

fun defaultAiProviders(): List<AiProvider> = listOf(
    AiProvider(
        id = "deepseek",
        name = "DeepSeek 官方",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        visionModel = "deepseek-flash",
    ),
    AiProvider(
        id = "openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
    ),
    AiProvider(
        id = "moonshot",
        name = "Moonshot Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        model = "moonshot-v1-8k",
    ),
    AiProvider(
        id = "qwen",
        name = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
    ),
    AiProvider(
        id = "siliconflow",
        name = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "deepseek-ai/DeepSeek-V3",
    ),
    AiProvider(
        id = "ollama",
        name = "本地 Ollama",
        baseUrl = "http://100.64.0.10:11434/v1",
        model = "llama3.1",
    ),
    AiProvider(
        id = "custom",
        name = "自定义",
        baseUrl = "",
        model = "",
    ),
)
