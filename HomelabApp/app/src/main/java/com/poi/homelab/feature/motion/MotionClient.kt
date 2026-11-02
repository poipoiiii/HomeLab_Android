package com.poi.homelab.feature.motion

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MotionException(
    val code: String,
    val userMessage: String,
    val httpCode: Int = 0,
) : IOException(userMessage)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** motion-studio 的 HTTP 客户端。播放/封面用 ?token=，其他接口用 Bearer。 */
class MotionClient(private val http: OkHttpClient, private val json: Json) {

    private val api: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloader: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun health(baseUrl: String, token: String): MotionHealth = withContext(Dispatchers.IO) {
        json.decodeFromString(call(baseUrl, token, "GET", "/healthz", null, authenticated = false))
    }

    suspend fun create(baseUrl: String, token: String, request: MotionRequest): MotionJob =
        withContext(Dispatchers.IO) {
            val body = call(baseUrl, token, "POST", "/api/v1/jobs", json.encodeToString(request))
            json.decodeFromString(body)
        }

    suspend fun jobs(baseUrl: String, token: String, limit: Int = 30): List<MotionJob> =
        withContext(Dispatchers.IO) {
            val body = call(
                baseUrl,
                token,
                "GET",
                "/api/v1/jobs",
                null,
                query = listOf("limit" to limit.coerceIn(1, 50).toString()),
            )
            json.decodeFromString<MotionJobPage>(body).jobs
        }

    suspend fun job(baseUrl: String, token: String, id: String): MotionJob =
        withContext(Dispatchers.IO) {
            json.decodeFromString(call(baseUrl, token, "GET", "/api/v1/jobs/$id", null))
        }

    suspend fun cancel(baseUrl: String, token: String, id: String): MotionJob =
        withContext(Dispatchers.IO) {
            json.decodeFromString(call(baseUrl, token, "POST", "/api/v1/jobs/$id/cancel", "{}"))
        }

    suspend fun delete(baseUrl: String, token: String, id: String) = withContext(Dispatchers.IO) {
        call(baseUrl, token, "DELETE", "/api/v1/jobs/$id", null)
    }

    suspend fun download(baseUrl: String, token: String, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(absolute(baseUrl, path, token))
                .build()
            downloader.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (response.code == 404) {
                    throw MotionException("result_expired", "成片已被清理（保留 14 天），需要重新生成", 404)
                }
                if (!response.isSuccessful) {
                    throw MotionException("download_failed", "下载成片失败：HTTP ${response.code}", response.code)
                }
                bytes
            }
        }

    /** 视频/封面给系统播放器或 Coil 直接用，Token 只能走查询参数。 */
    fun absolute(baseUrl: String, path: String?, token: String): String {
        val clean = path?.takeIf { it.isNotBlank() } ?: return ""
        val url = if (clean.startsWith("http://") || clean.startsWith("https://")) {
            clean
        } else {
            baseUrl.trim().trimEnd('/').removeSuffix("/api/v1").trimEnd('/') + clean
        }
        if (token.isBlank()) return url
        val encoded = URLEncoder.encode(token.trim(), "UTF-8")
        return url + (if (url.contains('?')) "&" else "?") + "token=$encoded"
    }

    private fun baseUrl(baseUrl: String): HttpUrl {
        val trimmed = baseUrl.trim().trimEnd('/')
            .removeSuffix("/api/v1")
            .trimEnd('/')
        if (trimmed.isBlank()) throw MotionException("not_configured", "还没有配置视频服务地址")
        return trimmed.toHttpUrlOrNull()
            ?: throw MotionException("bad_url", "视频服务地址格式不正确：$baseUrl")
    }

    private fun call(
        baseUrl: String,
        token: String,
        method: String,
        path: String,
        body: String?,
        query: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
    ): String {
        val builder = baseUrl(baseUrl).newBuilder()
        path.split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }

        val payload = body?.toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(builder.build())
            .method(method, payload)
            .apply { if (authenticated && token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .build()

        api.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return text
            throw parseError(text, response.code)
        }
    }

    private fun parseError(body: String, code: Int): MotionException {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val nested = parsed?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
        val errorCode = (nested?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (parsed?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: fallbackCode(code)
        val message = (nested?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (parsed?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: readable(code, body)
        return MotionException(errorCode, message, code)
    }

    private fun fallbackCode(code: Int): String = when (code) {
        401, 403 -> "unauthorized"
        404 -> "not_found"
        409 -> "job_finished"
        413 -> "payload_too_large"
        429 -> "queue_full"
        502, 503, 504 -> "backend_unavailable"
        else -> "http_$code"
    }

    private fun readable(code: Int, body: String): String {
        val hint = when (code) {
            401 -> "Token 不对，检查「视频 · 服务设置」里的 Token"
            429 -> "排队已满，稍后再试"
            502, 503, 504 -> "视频服务暂时不可用"
            else -> null
        }
        val detail = body.trim().take(120).ifBlank { "HTTP $code" }
        return if (hint != null) "$hint（$detail）" else detail
    }
}
