package com.poi.homelab.feature.lab

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class McpException(message: String) : IOException(message)

private data class RpcResult(
    val result: JsonElement?,
    val error: JsonObject?,
    val sessionId: String?,
    val protocolVersion: String?,
)

/**
 * 极简 MCP Streamable HTTP 客户端。
 *
 * 只实现客户端需要的 initialize / notifications/initialized / tools/call，
 * 兼容 application/json 与 text/event-stream 两种响应。
 */
class McpClient(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var initializedUrl: String? = null
    private var nextId = 0

    suspend fun callTool(
        baseUrl: String,
        tool: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val url = normalize(baseUrl)
            if (initializedUrl != url) {
                initialize(url)
            }

            val id = ++nextId
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", "tools/call")
                put(
                    "params",
                    buildJsonObject {
                        put("name", tool)
                        put("arguments", arguments)
                    },
                )
            }
            val rpc = post(url, payload, id)
            rpc.error?.let { throw McpException(errorMessage(it)) }
            val result = rpc.result ?: throw McpException("服务器没有返回结果")

            if (result is JsonObject && (result["isError"] as? JsonPrimitive)?.contentOrNull == "true") {
                throw McpException(extractText(result) ?: "工具调用失败")
            }
            extractText(result) ?: result.toString()
        }
    }

    private fun initialize(url: String) {
        sessionId = null
        protocolVersion = null
        var rpc = initializeRequest(url, "2025-03-26")
        if (rpc.error != null && isVersionError(errorMessage(rpc.error))) {
            sessionId = null
            rpc = initializeRequest(url, "2024-11-05")
        }
        rpc.error?.let { throw McpException(errorMessage(it)) }

        rpc.sessionId?.let { sessionId = it }
        val negotiated = (rpc.result as? JsonObject)
            ?.get("protocolVersion")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
        protocolVersion = negotiated ?: "2025-03-26"

        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        post(url, notification, null)

        initializedUrl = url
    }

    private fun initializeRequest(url: String, protocol: String): RpcResult {
        val id = ++nextId
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", protocol)
                    put("capabilities", buildJsonObject {})
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "homelab-android")
                            put("version", "1.0.0")
                        },
                    )
                },
            )
        }
        return post(url, payload, id)
    }

    private fun post(url: String, payload: JsonObject, requestId: Int?): RpcResult {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))

        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }

        http.newCall(builder.build()).execute().use { response ->
            val newSession = response.header("Mcp-Session-Id")
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 502/503 多半是反向代理连不上上游，返回的 HTML 错误页对用户没意义
                val hint = if (response.code in setOf(502, 503, 504)) {
                    "（反向代理连不上上游服务）"
                } else {
                    ""
                }
                val detail = body.trim()
                    .takeIf { it.isNotEmpty() && !it.startsWith("<") }
                    ?.take(200)
                throw McpException(
                    "HTTP ${response.code}$hint${detail?.let { "：$it" } ?: ""}",
                )
            }

            val contentType = response.header("Content-Type").orEmpty()
            val elements = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                parseSse(body)
            } else {
                parseJson(body)?.let { listOf(it) } ?: emptyList()
            }

            val match = elements
                .firstOrNull { it is JsonObject && requestId != null && it.idOrNull() == requestId }
                ?: elements.firstOrNull { it is JsonObject && (it["result"] != null || it["error"] != null) }
                ?: elements.lastOrNull()

            return RpcResult(
                result = (match as? JsonObject)?.get("result"),
                error = (match as? JsonObject)?.get("error") as? JsonObject,
                sessionId = newSession,
                protocolVersion = null,
            )
        }
    }

    private fun parseSse(body: String): List<JsonElement> {
        val events = mutableListOf<JsonElement>()
        val data = StringBuilder()
        fun flush() {
            if (data.isNotBlank()) {
                parseJson(data.toString().trim())?.let { element ->
                    if (element is JsonArray) events.addAll(element) else events.add(element)
                }
                data.clear()
            }
        }
        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
                line.isBlank() -> flush()
            }
        }
        flush()
        return events
    }

    private fun parseJson(text: String): JsonElement? {
        if (text.isBlank()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun extractText(result: JsonElement): String? {
        val content = (result as? JsonObject)?.get("content") as? JsonArray ?: return null
        val parts = content.mapNotNull { item ->
            val objectItem = item as? JsonObject ?: return@mapNotNull null
            if (objectItem["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            objectItem["text"]?.jsonPrimitive?.contentOrNull
        }
        return parts.joinToString("\n").ifBlank { null }
    }

    private fun JsonObject.idOrNull(): Int? =
        (this["id"] as? JsonPrimitive)?.intOrNull

    private fun errorMessage(error: JsonObject): String =
        (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()

    private fun isVersionError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("protocol") || lower.contains("version") || lower.contains("unsupported")
    }

    private fun normalize(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) throw McpException("请先在设置里填写 Lab Tools 地址")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw McpException("Lab Tools 地址需要以 http:// 或 https:// 开头")
        }
        return if (trimmed.endsWith("/mcp")) trimmed else "$trimmed/mcp"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
