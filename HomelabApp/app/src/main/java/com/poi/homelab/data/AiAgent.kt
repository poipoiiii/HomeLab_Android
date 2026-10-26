package com.poi.homelab.data

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.openai.ChatCompletions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * 基于 Google ADK 的对话 Agent：会话、工具调用循环、流式事件都由 ADK 负责，
 * 这里只负责把本地设置和历史消息接进去。
 */
class AiAgent(
    private val sessionService: InMemorySessionService = InMemorySessionService(),
) {

    fun run(
        provider: AiProvider,
        tools: AgentTools,
        instruction: String,
        history: List<ChatMessage>,
        input: String,
    ): Flow<Event> = flow {
        ensureSession(history)

        val model = model(provider)
        val agent = LlmAgent(
            name = AGENT_NAME,
            description = "小砂糖，poi's home 的私人助理",
            model = model,
            instruction = Instruction(instruction),
            tools = tools.generatedTools(),
            generateContentConfig = GenerateContentConfig(temperature = 0.2f),
            maxSteps = 12,
        )
        val runner = InMemoryRunner(
            app = App(
                appName = APP_NAME,
                rootAgent = agent,
                // 每 6 轮把更早的对话压成一条摘要，保留最近 2 轮重叠；
                // 单轮内工具调用很多时，按 token 阈值再做一次尾部保留压缩。
                eventsCompactionConfig = EventsCompactionConfig(
                    compactionInterval = 6,
                    overlapSize = 2,
                    tokenThreshold = 24_000,
                    eventRetentionSize = 12,
                ),
            ),
            sessionService = sessionService,
        )
        try {
            emitAll(
                runner.runAsync(
                    userId = USER_ID,
                    sessionId = SESSION_ID,
                    newMessage = Content(
                        role = Role.USER,
                        parts = listOf(Part(text = input)),
                    ),
                    runConfig = RunConfig(streamingMode = StreamingMode.SSE),
                ),
            )
        } finally {
            runner.close()
        }
    }

    /** 回答结束后，用同一服务商生成 2–3 个用户可能继续问的短句。 */
    suspend fun suggestFollowUps(
        provider: AiProvider,
        question: String,
        answer: String,
    ): List<String> {
        if (provider.baseUrl.isBlank() || provider.model.isBlank()) return emptyList()
        val prompt = """
            根据下面这轮对话，列出用户接下来最可能问的 3 个问题。
            要求：
            - 每行一个，只写问题，不要编号、不要解释。
            - 每个问题不超过 20 个汉字。
            - 问题要具体，能由这个手机助手继续查询或操作：实验室、账本、相册、本机文件、日历、手机状态。
            用户：$question
            助手：${answer.take(1500)}
        """.trimIndent()
        val request = LlmRequest(
            contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = prompt)))),
            config = GenerateContentConfig(temperature = 0.4f),
        )
        val text = runCatching {
            model(provider, timeoutSeconds = 30)
                .generateContent(request, stream = false)
                .toList()
                .mapNotNull { it.content?.text() }
                .joinToString("")
        }.getOrDefault("")
        return parseSuggestions(text)
    }

    private fun model(provider: AiProvider, timeoutSeconds: Long = 200): ChatCompletions =
        ChatCompletions(
            name = provider.model,
            apiKey = provider.apiKey.trim().ifBlank { null },
            httpOptions = HttpOptions(
                baseUrl = provider.openAiBaseUrl(),
                timeout = timeoutSeconds.seconds,
            ),
        )

    private fun parseSuggestions(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().trimStart('-', '*', '•').trim() }
            .map { it.replace(Regex("^\\d+[.、)）]\\s*"), "").trim() }
            .map { it.trim('"', '“', '”', ' ', '　') }
            .filter { it.length in 2..40 }
            .distinct()
            .take(3)
            .toList()

    /** 清空对话时同时丢掉 ADK 会话，下次发送会重新按本地记录建会话。 */
    suspend fun clearHistory() {
        sessionService.deleteSession(SessionKey(APP_NAME, USER_ID, SESSION_ID))
    }

    /** 首次发送时用已保存的对话补齐上下文，让 AI 能接着上次聊。 */
    private suspend fun ensureSession(history: List<ChatMessage>) {
        val key = SessionKey(APP_NAME, USER_ID, SESSION_ID)
        if (sessionService.getSession(key) != null) return

        val session = sessionService.createSession(key)
        history
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(24)
            .forEach { message ->
                val fromUser = message.role == "user"
                sessionService.appendEvent(
                    session,
                    Event(
                        author = if (fromUser) USER_AUTHOR else AGENT_NAME,
                        content = Content(
                            role = if (fromUser) Role.USER else Role.MODEL,
                            parts = listOf(Part(text = message.content)),
                        ),
                    ),
                )
            }
    }

    private companion object {
        const val APP_NAME = "poi_home"
        const val USER_ID = "poi"
        const val USER_AUTHOR = "user"
        const val AGENT_NAME = "poi_home_assistant"
        const val SESSION_ID = "main"
    }
}
