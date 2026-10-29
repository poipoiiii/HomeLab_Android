package com.poi.homelab.feature.weather

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 首页天气卡的数据，只依赖 Open-Meteo，不经过 Homarr。 */
data class HomeWeather(
    val city: String = "",
    val temperature: Double = 0.0,
    val code: Int = 0,
    val high: Double = 0.0,
    val low: Double = 0.0,
)

/**
 * 手填城市名先过 Open-Meteo 的地理编码，再取当前天气。
 * 城市结果会缓存，避免每次刷新都重新编码。
 */
class WeatherRepository(http: OkHttpClient, private val json: Json) {

    private val client = http.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var cached: Pair<String, Place>? = null

    suspend fun current(city: String): HomeWeather = withContext(Dispatchers.IO) {
        val query = city.trim()
        if (query.isBlank()) throw IOException("还没填城市")
        val place = cached?.takeIf { it.first == query }?.second ?: geocode(query).also {
            cached = query to it
        }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("latitude", place.latitude.toString())
            .addQueryParameter("longitude", place.longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
            .addQueryParameter("forecast_days", "1")
            .addQueryParameter("timezone", "auto")
            .build()
        val payload = json.decodeFromString<ForecastPayload>(get(url.toString()))
        val current = payload.current ?: throw IOException("天气接口没有返回当前天气")
        HomeWeather(
            city = place.name,
            temperature = current.temperature ?: 0.0,
            code = current.code ?: 0,
            high = payload.daily?.max?.firstOrNull() ?: current.temperature ?: 0.0,
            low = payload.daily?.min?.firstOrNull() ?: current.temperature ?: 0.0,
        )
    }

    private fun geocode(query: String): Place {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "zh")
            .addQueryParameter("format", "json")
            .build()
        val place = json.decodeFromString<GeocodePayload>(get(url.toString()))
            .results.firstOrNull()
            ?: throw IOException("没找到「$query」这个城市")
        return Place(name = place.name.ifBlank { query }, latitude = place.latitude, longitude = place.longitude)
    }

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("天气接口 HTTP ${response.code}")
            return body
        }
    }

    private data class Place(val name: String, val latitude: Double, val longitude: Double)
}

@Serializable
private data class GeocodePayload(val results: List<GeocodePlace> = emptyList())

@Serializable
private data class GeocodePlace(
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

@Serializable
private data class ForecastPayload(
    val current: CurrentWeather? = null,
    val daily: DailyWeather? = null,
)

@Serializable
private data class CurrentWeather(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("weather_code") val code: Int? = null,
)

@Serializable
private data class DailyWeather(
    @SerialName("temperature_2m_max") val max: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val min: List<Double> = emptyList(),
)
