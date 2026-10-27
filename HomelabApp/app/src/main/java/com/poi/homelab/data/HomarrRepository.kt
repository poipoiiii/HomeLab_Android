package com.poi.homelab.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Homarr 2.x 客户端。
 *
 * - 看板列表 / app 列表是 REST，`ApiKey` 头鉴权；
 * - 看板内容没有 REST GET，只能读 tRPC `board.getBoardByName`（query，需 `batch=1` 信封）。
 */
class HomarrRepository(
    private val http: OkHttpClient,
    private val json: Json,
) {

    suspend fun boards(baseUrl: String, apiKey: String): List<HomarrBoardSummary> =
        withContext(Dispatchers.IO) {
            val body = get(baseUrl, apiKey, "/api/boards", emptyList())
            json.decodeFromString(body)
        }

    suspend fun apps(baseUrl: String, apiKey: String): List<HomarrApp> =
        withContext(Dispatchers.IO) {
            val body = get(baseUrl, apiKey, "/api/apps", emptyList())
            json.decodeFromString(body)
        }

    suspend fun board(baseUrl: String, apiKey: String, name: String): HomarrBoard =
        withContext(Dispatchers.IO) {
            val input = """{"0":{"json":{"name":${JsonPrimitive(name)}}}}"""
            val body = get(
                baseUrl = baseUrl,
                apiKey = apiKey,
                path = "/api/trpc/board.getBoardByName",
                params = listOf("batch" to "1", "input" to input),
            )
            val first = runCatching { json.parseToJsonElement(body).jsonArray.first() }.getOrNull()
                ?: throw IOException("Homarr 返回的看板数据格式不正确")
            val envelope = first.jsonObject
            envelope["error"]?.let { throw IOException(errorMessage(it.jsonObject)) }
            val content = envelope["result"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("json") as? JsonObject
                ?: throw IOException("Homarr 没有返回看板内容，检查看板名是否正确")
            json.decodeFromJsonElement(HomarrBoard.serializer(), content)
        }

    private fun get(
        baseUrl: String,
        apiKey: String,
        path: String,
        params: List<Pair<String, String>>,
    ): String {
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IOException("Homarr 地址格式不正确")
        val key = apiKey.trim()
        if (key.isBlank()) throw IOException("请先在设置里填写 Homarr API Key")

        val builder = base.newBuilder()
        path.split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }

        val request = Request.Builder()
            .url(builder.build())
            .get()
            .header("ApiKey", key)
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(httpError(body, response.code))
            }
            return body
        }
    }

    private fun httpError(body: String, code: Int): String {
        val message = runCatching {
            errorMessage(json.parseToJsonElement(body).jsonObject)
        }.getOrNull()
        return message ?: "HTTP $code${if (body.isBlank()) "" else "：${body.take(200)}"}"
    }

    private fun errorMessage(node: JsonObject): String {
        (node["message"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        (node["json"]?.jsonObject?.get("message") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        (node["error"]?.jsonObject?.get("message") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return "Homarr 请求失败"
    }
}

/** href / iconUrl 可能是绝对地址、站内绝对路径或相对路径，统一补成可加载的完整 URL。 */
fun resolveHomarrUrl(baseUrl: String, href: String?): String? {
    val raw = href?.trim().orEmpty()
    val base = baseUrl.trim().trimEnd('/')
    if (raw.isEmpty()) return base.ifBlank { null }
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    if (base.isBlank()) return raw
    return if (raw.startsWith("/")) base + raw else "$base/$raw"
}

internal fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
