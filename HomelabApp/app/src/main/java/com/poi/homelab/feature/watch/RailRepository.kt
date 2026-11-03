package com.poi.homelab.feature.watch

import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class RailStation(val code: String, val name: String, val pinyin: String)

data class TrainQuote(
    val trainCode: String,
    val startTime: String,
    val arriveTime: String,
    val duration: String,
    val priceYuan: Int,
)

/**
 * 12306 只读查询。
 * - 必须先访问一次 init 拿到 cookie，否则接口直接返回错误页；
 * - 余票列表里每个车次是 `|` 分隔的字符串，第 2 位是 train_no，第 3 位是车次号；
 * - 票价单独走 leftTicketPrice，价格单位是「角」，除以 10 得到元。
 */
class RailRepository(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(MemoryCookieJar())
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var warmed = false

    @Volatile
    private var stationCache: List<RailStation> = emptyList()

    suspend fun stations(): List<RailStation> = withContext(Dispatchers.IO) {
        stationCache.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val request = Request.Builder()
            .url("$RAIL_BASE/otn/resources/js/framework/station_name.js?station_version=1.9377")
            .header("User-Agent", RAIL_UA)
            .header("Referer", "$RAIL_BASE/otn/leftTicket/init")
            .build()
        val body = execute(request)
        val list = body.split('@').mapNotNull { record ->
            val parts = record.split('|')
            if (parts.size < 4) return@mapNotNull null
            val name = parts[1].trim()
            val code = parts[2].trim()
            if (name.isBlank() || code.isBlank()) return@mapNotNull null
            RailStation(code = code, name = name, pinyin = parts[3].trim())
        }
        if (list.isNotEmpty()) stationCache = list
        list
    }

    /** 查某天该线路的最低价车次（最多返回 6 条，按席别价格升序）。 */
    suspend fun quotes(target: TrainTarget, date: LocalDate): List<TrainQuote> =
        withContext(Dispatchers.IO) {
            if (!target.valid) return@withContext emptyList()
            warmUp()
            val trains = trainList(target, date)
            if (trains.isEmpty()) return@withContext emptyList()

            val published = publicPrices(target, date)
            val candidates = trains
                .filter { published[it.code] != null }
                .sortedBy { published[it.code] }
                .take(6)
                .ifEmpty { trains.take(6) }

            val quotes = mutableListOf<TrainQuote>()
            candidates.forEachIndexed { index, train ->
                if (index > 0) delay(150)
                val price = runCatching { singlePrice(target, date, train) }.getOrNull()
                if (price != null && price > 0) {
                    quotes += TrainQuote(
                        trainCode = train.code,
                        startTime = train.start,
                        arriveTime = train.arrive,
                        duration = train.duration,
                        priceYuan = price,
                    )
                }
            }
            quotes.sortedBy { it.priceYuan }
        }

    private data class RawTrain(
        val trainNo: String,
        val code: String,
        val start: String,
        val arrive: String,
        val duration: String,
    )

    private fun trainList(target: TrainTarget, date: LocalDate): List<RawTrain> {
        val url = "$RAIL_BASE/otn/leftTicket/query".toHttpUrl().newBuilder()
            .addQueryParameter("leftTicketDTO.train_date", date.toString())
            .addQueryParameter("leftTicketDTO.from_station", target.fromCode)
            .addQueryParameter("leftTicketDTO.to_station", target.toCode)
            .addQueryParameter("purpose_codes", "ADULT")
            .build()
        val root = fetchJson(url)
        val result = root?.get("data")?.obj()?.get("result") as? JsonArray ?: return emptyList()
        return result.mapNotNull { element ->
            val line = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val f = line.split('|')
            if (f.size < 11) return@mapNotNull null
            RawTrain(
                trainNo = f[2],
                code = f[3],
                start = f[8],
                arrive = f[9],
                duration = f[10],
            )
        }
    }

    /** 公布票价，用来看哪个车次便宜，减少单车询价的次数。 */
    private fun publicPrices(target: TrainTarget, date: LocalDate): Map<String, Int> {
        val url = "$RAIL_BASE/otn/leftTicketPrice/queryAllPublicPrice".toHttpUrl().newBuilder()
            .addQueryParameter("leftTicketDTO.train_no", "")
            .addQueryParameter("leftTicketDTO.train_date", date.toString())
            .addQueryParameter("leftTicketDTO.from_station", target.fromCode)
            .addQueryParameter("leftTicketDTO.to_station", target.toCode)
            .addQueryParameter("randCode", "")
            .build()
        val root = fetchJson(url) ?: return emptyMap()
        val data = root["data"] as? JsonArray ?: return emptyMap()
        val map = mutableMapOf<String, Int>()
        data.forEach { element ->
            val dto = (element as? JsonObject)?.get("queryLeftNewDTO")?.obj() ?: return@forEach
            val code = dto.text("station_train_code") ?: return@forEach
            priceYuan(dto.text("${target.seat}_price"))?.let { map[code] = it }
        }
        return map
    }

    private fun singlePrice(target: TrainTarget, date: LocalDate, train: RawTrain): Int? {
        val url = "$RAIL_BASE/otn/leftTicketPrice/query".toHttpUrl().newBuilder()
            .addQueryParameter("leftTicketDTO.train_no", train.trainNo)
            .addQueryParameter("leftTicketDTO.train_date", date.toString())
            .addQueryParameter("leftTicketDTO.from_station", target.fromCode)
            .addQueryParameter("leftTicketDTO.to_station", target.toCode)
            .addQueryParameter("randCode", "")
            .build()
        val root = fetchJson(url) ?: return null
        val first = (root["data"] as? JsonArray)?.firstOrNull()?.obj() ?: return null
        val dto = first["queryLeftNewDTO"]?.obj() ?: return null
        return priceYuan(dto.text("${target.seat}_price"))
    }

    private fun warmUp() {
        if (warmed) return
        runCatching {
            val request = Request.Builder()
                .url("$RAIL_BASE/otn/leftTicket/init")
                .header("User-Agent", RAIL_UA)
                .build()
            http.newCall(request).execute().use { it.body?.string() }
            warmed = true
        }
    }

    private fun fetchJson(url: HttpUrl): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RAIL_UA)
            .header("Referer", "$RAIL_BASE/otn/leftTicket/init")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val body = execute(request).trim('\uFEFF')
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("12306 HTTP ${response.code}")
            if (body.isBlank()) throw IOException("12306 没有返回内容")
            return body
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { cookie ->
                list.removeAll { it.name == cookie.name }
                list += cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
    }

    companion object {
        private const val RAIL_BASE = "https://kyfw.12306.cn"
        private const val RAIL_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

/** 12306 价格是「角」，转成元。 */
private fun priceYuan(raw: String?): Int? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "--") return null
    val value = text.toIntOrNull() ?: return null
    return if (value <= 0) null else (value + 5) / 10
}

private fun kotlinx.serialization.json.JsonElement.obj(): JsonObject? = this as? JsonObject
