package com.poi.homelab.feature.homarr

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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

    /**
     * 逐个探测 app 的 href，给磁贴右下角的状态点用。
     *
     * Homarr 自带的 pingUrl 指向容器内网名（cloudreve:5212 之类），手机访问不到，
     * 所以改探 tailnet 上的 href：任何小于 500 的响应都算在线（401/403 也说明服务活着）。
     */
    suspend fun statuses(baseUrl: String, apps: List<HomarrApp>): Map<String, Boolean> =
        coroutineScope {
            apps.filter { !it.href.isNullOrBlank() }
                .map { app ->
                    async {
                        val url = resolveHomarrUrl(baseUrl, app.href)
                        app.id to (url != null && reachable(url))
                    }
                }
                .awaitAll()
                .toMap()
        }

    private suspend fun reachable(url: String): Boolean = withContext(Dispatchers.IO) {
        val target = url.toHttpUrlOrNull() ?: return@withContext false
        runCatching {
            val request = Request.Builder().url(target).head().build()
            probe.newCall(request).execute().use { it.code < 500 }
        }.getOrDefault(false)
    }

    private val probe: OkHttpClient = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** 天气地址在公网，单独用短超时，别让看板等 200 秒。 */
    private val weatherClient: OkHttpClient = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class CachedWeather(val at: Long, val snapshot: WeatherSnapshot)

    private val weatherCache = ConcurrentHashMap<String, CachedWeather>()

    /** Homarr 的 weather 组件存了经纬度，直接问 Open-Meteo，不需要 API Key。 */
    suspend fun weather(query: WeatherQuery): WeatherSnapshot = withContext(Dispatchers.IO) {
        val forecastDays = query.forecastDays.coerceIn(0, 7)
        val key = "${query.latitude},${query.longitude},${query.fahrenheit}," +
            "${query.imperialSpeed},$forecastDays"
        weatherCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < 10 * 60_000L) return@withContext cached.snapshot
        }

        val builder = "https://api.open-meteo.com/v1/forecast".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("latitude", query.latitude.toString())
            .addQueryParameter("longitude", query.longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter(
                "temperature_unit",
                if (query.fahrenheit) "fahrenheit" else "celsius",
            )
            .addQueryParameter(
                "wind_speed_unit",
                if (query.imperialSpeed) "mph" else "kmh",
            )
        if (forecastDays > 0) {
            builder.addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
            builder.addQueryParameter("forecast_days", forecastDays.toString())
        }

        val request = Request.Builder().url(builder.build()).get().build()
        val snapshot = weatherClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("天气接口 HTTP ${response.code}")
            val payload = json.decodeFromString<WeatherPayload>(body)
            val current = payload.current ?: throw IOException("天气接口没有返回当前天气")
            WeatherSnapshot(
                city = query.city,
                temperature = current.temperature ?: 0.0,
                code = current.code ?: 0,
                humidity = current.humidity,
                windSpeed = current.wind,
                forecast = payload.daily?.let { daily ->
                    daily.time.indices.mapNotNull { index ->
                        val code = daily.codes.getOrNull(index) ?: return@mapNotNull null
                        val max = daily.max.getOrNull(index) ?: return@mapNotNull null
                        val min = daily.min.getOrNull(index) ?: return@mapNotNull null
                        WeatherDay(code = code, min = min, max = max)
                    }
                }.orEmpty(),
            )
        }
        weatherCache[key] = CachedWeather(System.currentTimeMillis(), snapshot)
        snapshot
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

@Serializable
private data class WeatherPayload(
    val current: WeatherCurrent? = null,
    val daily: WeatherDaily? = null,
)

@Serializable
private data class WeatherCurrent(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Int? = null,
    @SerialName("weather_code") val code: Int? = null,
    @SerialName("wind_speed_10m") val wind: Double? = null,
)

@Serializable
private data class WeatherDaily(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val codes: List<Int> = emptyList(),
    @SerialName("temperature_2m_max") val max: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val min: List<Double> = emptyList(),
)
