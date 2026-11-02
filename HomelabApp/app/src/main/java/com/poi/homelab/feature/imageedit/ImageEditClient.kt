package com.poi.homelab.feature.imageedit

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 带错误码的网关异常；[code] 与设计稿里的错误码一致，例如 backend_timeout / result_expired。 */
class ImageEditException(
    val code: String,
    val userMessage: String,
    val httpCode: Int = 0,
) : IOException(userMessage)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * img-edit-gateway 的 HTTP 客户端。
 *
 * 只做单次请求，长轮询放在 [ImageEditRepository]；超时按用途拆开：
 * 提交/查询 60s，下载结果 120s，不污染全局 OkHttp。
 */
class ImageEditClient(private val http: OkHttpClient, private val json: Json) {

    private val api: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloader: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun health(baseUrl: String, token: String): ImageEditHealth = withContext(Dispatchers.IO) {
        val body = call(baseUrl, token, "GET", "/health", null)
        json.decodeFromString(body)
    }

    suspend fun createJob(
        baseUrl: String,
        token: String,
        request: ImageEditRequest,
    ): ImageEditCreateAck = withContext(Dispatchers.IO) {
        val body = call(baseUrl, token, "POST", "/v1/jobs", json.encodeToString(request))
        json.decodeFromString(body)
    }

    suspend fun createBatch(
        baseUrl: String,
        token: String,
        requests: List<ImageEditRequest>,
    ): ImageEditBatchAck = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(ImageEditBatchBody(requests))
        val body = call(baseUrl, token, "POST", "/v1/jobs/batch", payload)
        json.decodeFromString(body)
    }

    suspend fun jobs(
        baseUrl: String,
        token: String,
        status: String = "all",
        limit: Int = 30,
        cursor: Long? = null,
    ): ImageEditJobPage = withContext(Dispatchers.IO) {
        val params = buildList {
            add("status" to status)
            add("limit" to limit.coerceIn(1, 50).toString())
            cursor?.let { add("cursor" to it.toString()) }
        }
        val body = call(baseUrl, token, "GET", "/v1/jobs", null, params)
        json.decodeFromString(body)
    }

    suspend fun job(baseUrl: String, token: String, id: String): ImageEditJob =
        withContext(Dispatchers.IO) {
            val body = call(baseUrl, token, "GET", "/v1/jobs/$id", null)
            json.decodeFromString(body)
        }

    /** 取消后回读一次状态；排队中返回 canceled，运行中返回 cancel_requested。 */
    suspend fun cancel(baseUrl: String, token: String, id: String): ImageEditJob =
        withContext(Dispatchers.IO) {
            runCatching { call(baseUrl, token, "DELETE", "/v1/jobs/$id", null) }
            json.decodeFromString(call(baseUrl, token, "GET", "/v1/jobs/$id", null))
        }

    suspend fun download(baseUrl: String, token: String, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(absolute(baseUrl, path))
                .header("Authorization", "Bearer ${token.trim()}")
                .build()
            downloader.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (response.code == 404) {
                    throw ImageEditException("result_expired", "结果已被清理（保留 7 天），需要重新修图", 404)
                }
                if (!response.isSuccessful) {
                    throw ImageEditException("download_failed", "下载结果失败：HTTP ${response.code}", response.code)
                }
                bytes
            }
        }

    /** 给 Coil 直接加载缩略图用：把服务端返回的相对路径拼成绝对地址。 */
    fun absolute(baseUrl: String, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else baseUrl.trim().trimEnd('/') + path

    // ---------------------------------------------------------------- 内部
    private fun url(baseUrl: String): HttpUrl {
        var trimmed = baseUrl.trim().trimEnd('/')
        // 允许用户误填到具体接口：/v1/jobs/batch、/v1/jobs、/v1 都剥掉
        listOf("/v1/jobs/batch", "/v1/jobs", "/v1", "/jobs").forEach { suffix ->
            if (trimmed.endsWith(suffix)) trimmed = trimmed.removeSuffix(suffix)
        }
        trimmed = trimmed.trimEnd('/')
        if (trimmed.isBlank()) throw ImageEditException("not_configured", "还没有配置修图网关地址")
        return trimmed.toHttpUrlOrNull()
            ?: throw ImageEditException("bad_url", "修图网关地址格式不正确：$baseUrl")
    }

    private fun call(
        baseUrl: String,
        token: String,
        method: String,
        path: String,
        body: String?,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        val base = url(baseUrl).newBuilder()
        path.split('/').filter { it.isNotBlank() }.forEach(base::addPathSegment)
        query.forEach { (key, value) -> base.addQueryParameter(key, value) }

        val payload = body?.toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(base.build())
            .method(method, payload)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .build()

        api.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return text
            throw parseError(text, response.code)
        }
    }

    private fun parseError(body: String, code: Int): ImageEditException {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val nested = parsed?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
        val errorCode = (nested?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (parsed?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: fallbackCode(code)
        val detail = parsed?.get("detail")
        val validation = (detail as? JsonArray)?.firstOrNull()
            ?.let { runCatching { it.jsonObject }.getOrNull() }
        val message = (nested?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (parsed?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (detail as? JsonPrimitive)?.contentOrNull
            ?: validation?.let {
                val location = (it["loc"] as? JsonArray)?.joinToString(".") {
                    (it as? JsonPrimitive)?.contentOrNull.orEmpty()
                }.orEmpty()
                val text = (it["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                "参数不合法：$location $text".trim()
            }
            ?: readable(code, body)
        return ImageEditException(errorCode, message, code)
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
            401 -> "Token 不对，检查「AI 修图 · 服务设置」里的 Token"
            429 -> "队列已满，稍后再试"
            502, 503, 504 -> "网关或模型暂时不可用"
            else -> null
        }
        val detail = body.trim().take(120).ifBlank { "HTTP $code" }
        return if (hint != null) "$hint（$detail）" else detail
    }
}

@kotlinx.serialization.Serializable
private data class ImageEditBatchBody(val jobs: List<ImageEditRequest>)
