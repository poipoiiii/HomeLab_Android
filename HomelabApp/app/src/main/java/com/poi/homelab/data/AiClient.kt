package com.poi.homelab.data

import android.util.Base64
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 某个 AI 服务商账户的可用余额，只用于展示。 */
data class ProviderBalance(
    val display: String,
    val detail: String = "",
)

/**
 * OpenAI 兼容的生图接口。对话与工具调用已交给 Google ADK，这里只保留 ADK 未覆盖的生图能力。
 */
class AiClient(
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** 调用 OpenAI 兼容的 /v1/images/generations，返回图片字节。 */
    suspend fun generateImage(
        provider: AiProvider,
        model: String,
        prompt: String,
        size: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank()) throw IOException("AI 服务商地址为空，请先在设置里填写")
        if (model.isBlank()) throw IOException("生图模型为空，请先在设置里填写")

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("response_format", "b64_json")
        }

        http.newCall(
            Request.Builder()
                .url(imagesEndpoint(provider.baseUrl))
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
                .apply {
                    if (provider.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${provider.apiKey.trim()}")
                    }
                }
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorText(body, response.code))
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IOException("生图接口返回的不是 JSON：${body.take(200)}")
            }
            val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IOException("生图接口没有返回图片数据")
            val base64 = (item["b64_json"] as? JsonPrimitive)?.contentOrNull
            if (!base64.isNullOrBlank()) {
                Base64.decode(base64, Base64.DEFAULT)
            } else {
                val url = (item["url"] as? JsonPrimitive)?.contentOrNull
                if (url.isNullOrBlank()) throw IOException("生图接口没有返回 b64_json 或 url")
                downloadImage(url)
            }
        }
    }

    /** 查询当前服务商的账户余额；只支持有公开余额接口的服务商。 */
    suspend fun balance(provider: AiProvider): ProviderBalance = withContext(Dispatchers.IO) {
        if (provider.apiKey.isBlank()) {
            throw IOException("还没有填写 ${provider.name} 的 API Key")
        }
        val base = provider.openAiBaseUrl()
        val uri = runCatching { URI(base) }.getOrNull()
        val origin = uri?.let { "${it.scheme}://${it.authority}" }
            ?: throw IOException("服务商地址不合法")
        when {
            base.contains("api.deepseek.com") -> deepSeekBalance(origin, provider)
            base.contains("api.moonshot.cn") -> moonShotBalance(origin, provider)
            base.contains("api.siliconflow.cn") -> siliconFlowBalance(origin, provider)
            else -> throw IOException("${provider.name} 没有公开的余额接口，无法查询")
        }
    }

    private fun deepSeekBalance(origin: String, provider: AiProvider): ProviderBalance {
        val root = getJson("$origin/user/balance", provider)
        val info = root["balance_infos"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("余额接口没有返回余额信息")
        val currency = info.text("currency") ?: "CNY"
        val total = info.text("total_balance") ?: "0"
        val granted = info.text("granted_balance")
        val topped = info.text("topped_up_balance")
        val detail = buildList {
            if (granted != null) add("赠送 ${symbol(currency)}$granted")
            if (topped != null) add("充值 ${symbol(currency)}$topped")
        }.joinToString(" · ")
        return ProviderBalance("${symbol(currency)}$total", detail)
    }

    private fun moonShotBalance(origin: String, provider: AiProvider): ProviderBalance {
        val data = getJson("$origin/v1/users/me/balance", provider)["data"]?.jsonObject
            ?: throw IOException("余额接口没有返回余额信息")
        val available = data.text("available_balance")
            ?: throw IOException("余额接口没有返回可用余额")
        val cash = data.text("cash_balance")
        return ProviderBalance("¥$available", cash?.let { "现金 ¥$it" }.orEmpty())
    }

    private fun siliconFlowBalance(origin: String, provider: AiProvider): ProviderBalance {
        val data = getJson("$origin/v1/user/info", provider)["data"]?.jsonObject
            ?: throw IOException("余额接口没有返回余额信息")
        val total = data.text("totalBalance") ?: data.text("balance")
            ?: throw IOException("余额接口没有返回余额")
        val charge = data.text("chargeBalance")
        return ProviderBalance("¥$total", charge?.let { "充值 ¥$it" }.orEmpty())
    }

    private fun getJson(url: String, provider: AiProvider): JsonObject {
        http.newCall(
            Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer ${provider.apiKey.trim()}")
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorText(body, response.code))
            return runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IOException("余额接口返回的不是 JSON：${body.take(200)}")
            }
        }
    }

    private fun symbol(currency: String): String = when (currency.uppercase()) {
        "USD" -> "\$"
        "CNY", "RMB" -> "¥"
        else -> "$currency "
    }

    private fun JsonObject.text(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.contentOrNull
    }

    private fun downloadImage(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载生成的图片失败：HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("生成的图片为空")
        }
    }

    private fun imagesEndpoint(base: String): String {
        var trimmed = base.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (trimmed.endsWith("/images/generations")) return trimmed
        if (trimmed.endsWith("/v1")) return "$trimmed/images/generations"
        return "$trimmed/v1/images/generations"
    }

    private fun errorText(body: String, code: Int): String {
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            (root["error"] as? JsonObject)?.let { error ->
                (error["message"] as? JsonPrimitive)?.contentOrNull
            } ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                ?: (root["detail"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return detail ?: "HTTP $code${if (body.isBlank()) "" else "：${body.take(200)}"}"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
