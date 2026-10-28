package com.poi.homelab.feature.bills

import com.poi.homelab.core.AppContainer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BillRepository(
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** 图片识图在服务端最长 180s，上传写超时单独放宽。 */
    private val uploadClient = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * commit 必须把 11 个字段全部发出去。
     *
     * BillImportItem 的字段都有默认值（例如 type 默认 withdrawal），而 AppContainer 的 Json
     * 没开 encodeDefaults，默认值会被省略，服务端会认为缺少必填的 type/amount 并返回 422。
     */
    private val commitJson = Json { encodeDefaults = true }

    suspend fun health(baseUrl: String): String = withContext(Dispatchers.IO) {
        get(baseUrl, "/healthz", emptyList(), token = null)
    }

    /** 核对列表用的账户/分类候选，返回中文名。 */
    suspend fun meta(baseUrl: String): BillMeta = withContext(Dispatchers.IO) {
        json.decodeFromString(get(baseUrl, "/api/meta", emptyList(), token = null))
    }

    /** 上传一张截图或一份 CSV/XLSX，只解析不落库。 */
    suspend fun extract(
        baseUrl: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
    ): BillExtractResponse = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName.ifBlank { "bill" },
                bytes.toRequestBody(mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url(urlFor(baseUrl, "/api/extract"))
            .post(body)
            .build()
        uploadClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorDetail(text, response.code))
            json.decodeFromString(text)
        }
    }

    /** 整批确认入库；服务端按 order_id 或字段指纹去重。 */
    suspend fun commit(
        baseUrl: String,
        items: List<BillImportItem>,
    ): BillCommitResult = withContext(Dispatchers.IO) {
        val payload = commitJson.encodeToString(BillCommitRequest(items))
        val request = Request.Builder()
            .url(urlFor(baseUrl, "/api/commit"))
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        uploadClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorDetail(text, response.code))
            json.decodeFromString(text)
        }
    }

    suspend fun overview(baseUrl: String, token: String, month: String?): BillOverview =
        withContext(Dispatchers.IO) {
            val body = get(
                baseUrl,
                "/api/overview",
                listOfNotNull(month?.let { "month" to it }),
                token,
            )
            json.decodeFromString<BillOverview>(body)
        }

    suspend fun transactions(
        baseUrl: String,
        token: String,
        month: String?,
        limit: Int = 100,
        offset: Int = 0,
    ): TransactionPage = withContext(Dispatchers.IO) {
        val params = buildList {
            month?.let { add("month" to it) }
            add("limit" to limit.toString())
            if (offset > 0) add("offset" to offset.toString())
        }
        val body = get(baseUrl, "/api/transactions", params, token)
        json.decodeFromString<TransactionPage>(body)
    }

    suspend fun analyze(baseUrl: String, token: String, days: Int = 90): AnalysisResponse =
        withContext(Dispatchers.IO) {
            val body = get(
                baseUrl,
                "/api/analyze",
                listOf("days" to days.toString()),
                token,
            )
            json.decodeFromString<AnalysisResponse>(body)
        }

    private fun get(
        baseUrl: String,
        path: String,
        params: List<Pair<String, String>>,
        token: String?,
    ): String {
        val builder = urlFor(baseUrl, path).newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }

        val request = Request.Builder()
            .url(builder.build())
            .get()
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
            }
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(errorDetail(body, response.code))
            }
            return body
        }
    }

    private fun urlFor(baseUrl: String, path: String): HttpUrl {
        val builder = parseBase(baseUrl).newBuilder()
        path.split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun parseBase(value: String): HttpUrl {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) throw IOException("请先在设置里填写账本地址")
        return trimmed.toHttpUrlOrNull() ?: throw IOException("账本地址格式不正确")
    }

    private fun errorDetail(body: String, code: Int): String {
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            (root["detail"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return detail ?: "HTTP $code${if (body.isBlank()) "" else "：${body.take(200)}"}"
    }

    @Suppress("unused")
    private fun JsonObject.detail(): String? =
        (this["detail"] as? JsonPrimitive)?.contentOrNull
}
