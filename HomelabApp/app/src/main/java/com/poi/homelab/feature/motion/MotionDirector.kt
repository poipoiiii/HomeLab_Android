package com.poi.homelab.feature.motion

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.openai.ChatCompletions
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.poi.homelab.feature.ai.AiProvider
import com.poi.homelab.feature.ai.openAiBaseUrl
import com.poi.homelab.feature.ai.requiresKey
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 视频导演：把用户的一句话交给模型，只拿回两种东西——追问，或者一份可渲染的分镜方案。
 *
 * 模型输出严格 JSON；这里做容错解析、一次修复重试和方案规整。
 * 渲染永远由用户点「确认生成」触发，模型自己不能直接开始。
 */
class MotionDirector(private val json: Json) {

    suspend fun reply(
        provider: AiProvider,
        history: List<DirectorTurn>,
        input: String,
        previousPlan: MotionRequest? = null,
    ): DirectorReply {
        if (provider.baseUrl.isBlank() || provider.model.isBlank()) {
            throw IOException("先到 设置 → 小砂糖 → AI 配置 选一个服务商")
        }
        if (provider.apiKey.isBlank() && provider.requiresKey) {
            throw IOException("AI 服务商还没有填写 API Key")
        }

        val first = generate(provider, buildPrompt(history, input, previousPlan))
        parse(first)?.let { return normalizeReply(it) }

        // 模型偶尔会带 Markdown 或解释，给它一次改写成纯 JSON 的机会
        val repaired = generate(
            provider,
            "你上一条输出不是可解析的 JSON。请只输出合法 JSON，不要代码块、不要解释。\n\n$first",
        )
        parse(repaired)?.let { return normalizeReply(it) }

        return DirectorReply(
            status = "ask",
            message = "我这边没把方案组织好，再说一句你想要的主题，或者直接说「你定」。",
            plan = null,
        )
    }

    private suspend fun generate(provider: AiProvider, prompt: String): String {
        val model = ChatCompletions(
            name = provider.model,
            apiKey = provider.apiKey.trim().ifBlank { null },
            httpOptions = HttpOptions(
                baseUrl = provider.openAiBaseUrl(),
                timeout = 120.seconds,
            ),
        )
        val request = LlmRequest(
            contents = listOf(
                Content(role = Role.USER, parts = listOf(Part(text = prompt))),
            ),
            config = GenerateContentConfig(temperature = 0.4f),
        )
        return model.generateContent(request, stream = false)
            .toList()
            .mapNotNull { it.content?.text() }
            .joinToString("")
            .trim()
    }

    private fun buildPrompt(
        history: List<DirectorTurn>,
        input: String,
        previousPlan: MotionRequest?,
    ): String {
        val turns = history.takeLast(12).joinToString("\n") { turn ->
            val speaker = if (turn.role == "user") "用户" else "导演"
            "$speaker：${turn.text.take(4000)}"
        }.ifBlank { "（还没有对话）" }
        val plan = previousPlan?.let {
            runCatching { json.encodeToString(it) }.getOrNull()
        }
        return buildString {
            appendLine(motionDirectorPrompt())
            appendLine()
            if (!plan.isNullOrBlank()) {
                appendLine("## 上一版方案（仅当用户是在修改上一版时参考；如果用户换了主题，忽略它）")
                appendLine(plan)
                appendLine()
            }
            appendLine("## 对话历史")
            appendLine(turns)
            appendLine()
            appendLine("## 用户新消息")
            appendLine(input.take(4000))
            appendLine()
            append("只输出 JSON，不要任何其他字符。")
        }
    }

    private fun parse(raw: String): DirectorReply? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString<DirectorReply>(cleaned.substring(start, end + 1))
        }.getOrNull()
    }

    private fun normalizeReply(reply: DirectorReply): DirectorReply {
        val plan = normalizePlan(reply.plan)
        if (plan != null) {
            return DirectorReply(
                status = "plan",
                message = reply.message.ifBlank { "方案整理好了，确认后我就开始渲染。" },
                plan = plan,
            )
        }
        return DirectorReply(
            status = "ask",
            message = reply.message.ifBlank { "想做什么样的视频？给我一句话就行，比如主题、时长和语气。" },
            plan = null,
        )
    }

    private fun normalizePlan(plan: MotionRequest?): MotionRequest? {
        if (plan == null) return null
        val scenes = plan.scenes.take(16).toMutableList()
        if (scenes.isEmpty()) return null

        if (scenes.first().type != "cover") {
            scenes.add(
                0,
                MotionScene(
                    type = "cover",
                    kicker = plan.kicker,
                    title = plan.title.ifBlank { "视频方案" },
                    subtitle = plan.subtitle,
                    duration = 4.0,
                ),
            )
        }
        if (scenes.last().type != "end") {
            scenes.add(
                MotionScene(
                    type = "end",
                    title = plan.endingTitle.ifBlank { plan.title },
                    subtitle = plan.endingSubtitle,
                    duration = 4.5,
                ),
            )
        }

        val theme = plan.theme.takeIf { it in setOf("ink", "paper", "signal") } ?: "ink"
        val resolution = plan.resolution.takeIf { it in setOf("1080p", "720p") } ?: "1080p"
        val title = plan.title.ifBlank { scenes.first().title }.ifBlank { "视频方案" }
        return plan.copy(
            template = "scenes",
            theme = theme,
            resolution = resolution,
            fps = 30,
            title = title,
            audio = plan.audio,
            sections = emptyList(),
            scenes = scenes,
        )
    }
}
