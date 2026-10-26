package com.poi.homelab.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class BillRepository(
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun health(baseUrl: String): String = withContext(Dispatchers.IO) {
        get(baseUrl, "/healthz", emptyList(), token = null)
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
    ): TransactionPage = withContext(Dispatchers.IO) {
        val params = buildList {
            month?.let { add("month" to it) }
            add("limit" to limit.toString())
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
        val builder = parseBase(baseUrl).newBuilder()
        path.split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
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
