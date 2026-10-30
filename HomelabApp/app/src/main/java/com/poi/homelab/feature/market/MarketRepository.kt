package com.poi.homelab.feature.market

import java.io.IOException
import java.nio.charset.Charset
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 直接问公开行情接口，不经过家里服务器，也不需要 Key。
 * 东财一次可以查多条（UTF-8 JSON），腾讯只用来补纳指 100（它的代码才对）。
 */
class MarketRepository(http: OkHttpClient, private val json: Json) {

    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var nasdaq100Cache: List<String>? = null

    suspend fun quotes(symbols: List<MarketSymbol>): List<MarketQuote> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext emptyList()
        val found = LinkedHashMap<String, MarketQuote>()
        val eastmoney = symbols.filter { it.source != MARKET_SOURCE_TENCENT }
        val tencent = symbols.filter { it.source == MARKET_SOURCE_TENCENT }

        if (eastmoney.isNotEmpty()) {
            runCatching { eastmoneyQuotes(eastmoney) }
                .onSuccess { found.putAll(it) }
                .onFailure { error -> eastmoney.forEach { found[it.id] = it.failed(error) } }
        }
        if (tencent.isNotEmpty()) {
            runCatching { tencentQuotes(tencent) }
                .onSuccess { found.putAll(it) }
                .onFailure { error -> tencent.forEach { found[it.id] = it.failed(error) } }
        }
        // 美股盘前 / 盘后：新浪的 gb_ 接口字段更全，单独补一轮
        val usStocks = symbols.filter {
            it.regionKey() == MARKET_REGION_US && it.source != MARKET_SOURCE_TENCENT
        }
        if (usStocks.isNotEmpty()) {
            runCatching { sinaAfterHours(usStocks) }.onSuccess { extra ->
                extra.forEach { (id, after) ->
                    val quote = found[id] ?: return@forEach
                    found[id] = quote.copy(
                        afterPrice = after.price,
                        afterChangePercent = after.percent,
                        afterLabel = after.label,
                    )
                }
            }
        }
        symbols.map { symbol ->
            val quote = found[symbol.id] ?: return@map symbol.failed(IOException("行情没有返回这条"))
            // 开盘前接口会把涨跌幅重置成 0，这里补成上一个交易日的涨跌幅
            if (quote.changePercent == 0.0 && needsPreviousSession(symbol, quote)) {
                val bar = lastSessionBar(symbol)
                if (bar != null && bar.changePercent != -100.0) {
                    return@map quote.copy(
                        change = bar.close * bar.changePercent / (100 + bar.changePercent),
                        changePercent = bar.changePercent,
                        previousSession = true,
                    )
                }
            }
            quote
        }
    }

    private val lastBarCache = mutableMapOf<String, Pair<String, DailyBar?>>()

    private fun exchangeZone(symbol: MarketSymbol): ZoneId? = when {
        symbol.regionKey() == MARKET_REGION_CN -> ZoneId.of("Asia/Shanghai")
        symbol.regionKey() == MARKET_REGION_HK -> ZoneId.of("Asia/Hong_Kong")
        symbol.regionKey() == MARKET_REGION_US -> ZoneId.of("America/New_York")
        symbol.code == "100.KS11" -> ZoneId.of("Asia/Seoul")
        symbol.code == "100.N225" -> ZoneId.of("Asia/Tokyo")
        else -> null
    }

    private fun needsPreviousSession(symbol: MarketSymbol, quote: MarketQuote): Boolean {
        val zone = exchangeZone(symbol) ?: return false
        val now = LocalDate.now(zone)
        val time = LocalTime.now(zone)
        val open = if (symbol.code == "100.KS11") LocalTime.of(9, 0) else LocalTime.of(9, 30)
        val weekday = now.dayOfWeek
        val tradingDay = weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY
        if (tradingDay && time.isBefore(open)) return true
        if (quote.quoteTime > 0) {
            val quoteTime = Instant.ofEpochSecond(quote.quoteTime).atZone(zone)
            // 数据还停在开盘前那一笔，也按上一交易日处理
            if (quoteTime.toLocalDate() == now && quoteTime.toLocalTime().isBefore(open)) return true
            if (quoteTime.toLocalDate().isBefore(now)) return true
        }
        return false
    }

    /** 上一个交易日的日 K；同一标的同一天只查一次。 */
    private suspend fun lastSessionBar(symbol: MarketSymbol): DailyBar? {
        val zone = exchangeZone(symbol) ?: return null
        val today = LocalDate.now(zone).toString()
        val cached = lastBarCache[symbol.id]
        if (cached != null && cached.first == today) return cached.second
        val bar = runCatching { dailyBars(symbol, 5).lastOrNull() }.getOrNull()
        lastBarCache[symbol.id] = today to bar
        return bar
    }

    /** 东财搜索：输入中文名、代码或拼音都能出结果，返回的 QuoteID 直接当 secid 用。 */
    suspend fun search(query: String): List<MarketSearchResult> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isBlank()) return@withContext emptyList()
        val url = "https://searchapi.eastmoney.com/api/suggest/get".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("input", keyword)
            .addQueryParameter("type", "14")
            .addQueryParameter("count", "10")
            .addQueryParameter("token", SEARCH_TOKEN)
            .build()
        val root = json.parseToJsonElement(get(url.toString())) as? JsonObject ?: return@withContext emptyList()
        val rows = (root["QuotationCodeTable"] as? JsonObject)?.get("Data") as? JsonArray
            ?: return@withContext emptyList()
        val results = mutableListOf<MarketSearchResult>()
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val quoteId = row.string("QuoteID") ?: continue
            val name = row.string("Name")?.takeIf { it.isNotBlank() } ?: continue
            // 东财的 100.NDX 实际返回纳斯达克综指，纳指 100 用下面补的腾讯代码
            if (quoteId == "100.NDX") continue
            val market = row.string("SecurityTypeName").orEmpty()
            results += MarketSearchResult(
                symbol = MarketSymbol(id = "em:$quoteId", label = name, code = quoteId),
                market = market,
            )
        }
        if (keyword.contains("纳斯达克") || keyword.contains("纳指") || keyword.equals("ndx", true)) {
            val ndx = MarketSearchResult(
                symbol = MarketSymbol(
                    id = "tx:usNDX",
                    label = "纳斯达克100",
                    code = "usNDX",
                    source = MARKET_SOURCE_TENCENT,
                ),
                market = "指数",
            )
            results.removeAll { it.symbol.id == ndx.symbol.id }
            results.add(0, ndx)
        }
        results.distinctBy { it.symbol.id }.take(12)
    }

    /** 日 K：纳指 100 走纳斯达克官方历史，其余走东财。 */
    suspend fun dailyBars(symbol: MarketSymbol, days: Int = 90): List<DailyBar> = withContext(Dispatchers.IO) {
        if (symbol.id == "tx:usNDX" || symbol.code.equals("usNDX", true)) {
            nasdaqHistory("NDX", "index", days)
        } else {
            eastmoneyBars(symbol.code, days)
        }
    }

    /** 分时：普通标的一分钟一根，纳指 100 用腾讯的分钟接口。 */
    suspend fun intraday(symbol: MarketSymbol): IntradaySeries = withContext(Dispatchers.IO) {
        if (symbol.id == "tx:usNDX" || symbol.code.equals("usNDX", true)) {
            tencentIntraday("usNDX")
        } else {
            eastmoneyIntraday(symbol.code)
        }
    }

    private fun eastmoneyIntraday(secid: String): IntradaySeries {
        val url = "https://push2his.eastmoney.com/api/qt/stock/trends2/get?secid=$secid" +
            "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13" +
            "&fields2=f51,f53,f56,f58&ndays=1&iscr=0"
        val root = json.parseToJsonElement(get(url)) as? JsonObject
            ?: return IntradaySeries(0.0, emptyList())
        val data = root["data"] as? JsonObject ?: return IntradaySeries(0.0, emptyList())
        val preClose = (data["preClose"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val rows = data["trends"] as? JsonArray ?: return IntradaySeries(preClose, emptyList())
        val points = rows.mapNotNull { element ->
            val parts = (element as? JsonPrimitive)?.contentOrNull?.split(',') ?: return@mapNotNull null
            if (parts.size < 2) return@mapNotNull null
            val price = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            MinutePoint(parts[0].takeLast(5), price)
        }
        return IntradaySeries(
            preClose = preClose.takeIf { it > 0.0 } ?: points.firstOrNull()?.price ?: 0.0,
            points = points,
        )
    }

    private fun tencentIntraday(code: String): IntradaySeries {
        val root = json.parseToJsonElement(
            get("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$code"),
        ) as? JsonObject ?: return IntradaySeries(0.0, emptyList())
        val node = (root["data"] as? JsonObject)?.get(code) as? JsonObject
            ?: return IntradaySeries(0.0, emptyList())
        val preClose = (((node["qt"] as? JsonObject)?.get(code) as? JsonArray)
            ?.getOrNull(4) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val rows = ((node["data"] as? JsonObject)?.get("data") as? JsonArray)
            ?: return IntradaySeries(preClose, emptyList())
        val points = rows.mapNotNull { element ->
            val parts = (element as? JsonPrimitive)?.contentOrNull?.split(' ') ?: return@mapNotNull null
            if (parts.size < 2) return@mapNotNull null
            val raw = parts[0]
            if (raw.length < 3) return@mapNotNull null
            val time = raw.padStart(4, '0').let { "${it.take(2)}:${it.takeLast(2)}" }
            val price = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            MinutePoint(time, price)
        }
        return IntradaySeries(
            preClose = preClose.takeIf { it > 0.0 } ?: points.firstOrNull()?.price ?: 0.0,
            points = points,
        )
    }

    private fun eastmoneyBars(secid: String, days: Int): List<DailyBar> {
        val begin = LocalDate.now().minusDays(days * 2L + 10).format(DateTimeFormatter.BASIC_ISO_DATE)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=$secid" +
            "&klt=101&fqt=1&beg=$begin&end=20500101&fields1=f1,f2&fields2=f51,f53,f59"
        val root = json.parseToJsonElement(get(url)) as? JsonObject ?: return emptyList()
        val rows = (root["data"] as? JsonObject)?.get("klines") as? JsonArray ?: return emptyList()
        val out = mutableListOf<DailyBar>()
        for (element in rows) {
            val parts = (element as? JsonPrimitive)?.contentOrNull?.split(',') ?: continue
            if (parts.size < 3) continue
            val close = parts[1].toDoubleOrNull() ?: continue
            out += DailyBar(parts[0], close, parts[2].toDoubleOrNull() ?: 0.0)
        }
        return out.takeLast(days)
    }

    private fun nasdaqHistory(symbol: String, assetClass: String, days: Int): List<DailyBar> {
        val from = LocalDate.now().minusDays(days * 2L + 10).toString()
        val url = "https://api.nasdaq.com/api/quote/$symbol/historical" +
            "?assetclass=$assetClass&fromdate=$from&limit=$days"
        val root = json.parseToJsonElement(get(url, NASDAQ_HEADERS)) as? JsonObject ?: return emptyList()
        val rows = (((root["data"] as? JsonObject)?.get("tradesTable") as? JsonObject)
            ?.get("rows") as? JsonArray) ?: return emptyList()
        val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        val parsed = mutableListOf<Pair<String, Double>>()
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val date = row.string("date")?.let { raw ->
                runCatching { LocalDate.parse(raw, formatter).toString() }.getOrNull()
            } ?: continue
            val close = row.string("close")
                ?.replace("$", "")
                ?.replace(",", "")
                ?.toDoubleOrNull() ?: continue
            parsed += date to close
        }
        val ascending = parsed.sortedBy { it.first }
        val out = mutableListOf<DailyBar>()
        for (index in ascending.indices) {
            val previous = ascending.getOrNull(index - 1)?.second
            val percent = if (previous != null && previous != 0.0) {
                (ascending[index].second - previous) / previous * 100
            } else {
                0.0
            }
            out += DailyBar(ascending[index].first, ascending[index].second, percent)
        }
        return out.takeLast(days)
    }

    /** 纳斯达克官方财报日历，一次取一天。 */
    suspend fun earnings(date: String): List<MarketEvent> = withContext(Dispatchers.IO) {
        val url = "https://api.nasdaq.com/api/calendar/earnings?date=$date"
        val root = json.parseToJsonElement(get(url, NASDAQ_HEADERS)) as? JsonObject
            ?: return@withContext emptyList()
        val rows = (root["data"] as? JsonObject)?.get("rows") as? JsonArray
            ?: return@withContext emptyList()
        val out = mutableListOf<MarketEvent>()
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val symbol = row.string("symbol") ?: continue
            val name = row.string("name")?.takeIf { it.isNotBlank() } ?: symbol
            val eps = row.string("epsForecast")?.takeIf { it.isNotBlank() && it != "--" }
            val quarter = row.string("fiscalQuarterEnding").orEmpty()
            val whenLabel = when (row.string("time")) {
                "time-pre-market" -> "盘前"
                "time-after-hours" -> "盘后"
                else -> "时间待定"
            }
            val detail = buildList {
                if (eps != null) add("预期 EPS $eps")
                add(whenLabel)
                if (quarter.isNotBlank()) add("财季 $quarter")
            }.joinToString(" · ")
            out += MarketEvent(
                id = "earnings:$date:$symbol",
                date = date,
                title = "$name 财报",
                detail = detail,
                symbol = symbol,
            )
        }
        out
    }

    /** 纳指 100 成分股，用来过滤「即将发财报」的名单。 */
    suspend fun nasdaq100(): List<String> = withContext(Dispatchers.IO) {
        nasdaq100Cache?.let { return@withContext it }
        val url = "https://api.nasdaq.com/api/quote/list-type/nasdaq100"
        val root = json.parseToJsonElement(get(url, NASDAQ_HEADERS)) as? JsonObject
            ?: return@withContext emptyList()
        val rows = (((root["data"] as? JsonObject)?.get("data") as? JsonObject)
            ?.get("rows") as? JsonArray) ?: return@withContext emptyList()
        val symbols = rows.mapNotNull { (it as? JsonObject)?.string("symbol") }.distinct()
        if (symbols.isNotEmpty()) nasdaq100Cache = symbols
        symbols
    }

    /** 东方财富 7x24 快讯，原始数据；评分在本地做。 */
    suspend fun news(pageSize: Int = 50): List<MarketNews> = withContext(Dispatchers.IO) {
        val url = "https://np-weblist.eastmoney.com/comm/web/getFastNewsList" +
            "?client=web&biz=web_724&fastColumn=102&sortEnd=&pageSize=$pageSize" +
            "&req_trace=${System.currentTimeMillis()}"
        val root = json.parseToJsonElement(get(url)) as? JsonObject ?: return@withContext emptyList()
        val rows = (root["data"] as? JsonObject)?.get("fastNewsList") as? JsonArray
            ?: return@withContext emptyList()
        val out = mutableListOf<MarketNews>()
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val id = row.string("code") ?: continue
            val summary = row.string("summary").orEmpty()
            val title = row.string("title")?.takeIf { it.isNotBlank() }
                ?: summary.take(40)
            if (title.isBlank()) continue
            val time = row.string("showTime").orEmpty()
            val stocks = (row["stockList"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val heat = ((row["share"] as? JsonPrimitive)?.intOrNull ?: 0) +
                ((row["pinglun_Num"] as? JsonPrimitive)?.intOrNull ?: 0)
            out += MarketNews(
                id = id,
                title = title,
                summary = summary,
                time = time,
                date = time.take(10),
                stocks = stocks,
                heat = heat,
            )
        }
        out.distinctBy { it.id }
    }

    private data class AfterHours(val price: Double, val percent: Double, val label: String)

    /**
     * 新浪美股行情里 21 是盘前/盘后价，22 是涨跌幅，24 是那笔成交时间。
     * 只对美股个股调用，指数和 A/港股跳过。
     */
    private fun sinaAfterHours(symbols: List<MarketSymbol>): Map<String, AfterHours> {
        val mapping = symbols.mapNotNull { symbol ->
            val code = symbol.code.substringAfter('.', "")
            if (code.isBlank()) return@mapNotNull null
            ("gb_" + code.lowercase()) to symbol
        }.toMap()
        if (mapping.isEmpty()) return emptyMap()
        val url = "https://hq.sinajs.cn/list=" + mapping.keys.joinToString(",")
        val text = String(
            getBytes(url, mapOf("Referer" to "https://finance.sina.com.cn")),
            Charset.forName("GBK"),
        )
        val out = mutableMapOf<String, AfterHours>()
        for (line in text.split(';')) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("var hq_str_")) continue
            val key = trimmed.substringAfter("var hq_str_").substringBefore('=')
            val symbol = mapping[key] ?: continue
            val fields = trimmed.substringAfter('"', "").substringBefore('"').split(',')
            if (fields.size < 25) continue
            val price = fields[21].toDoubleOrNull() ?: continue
            if (price <= 0.0) continue
            val percent = fields[22].toDoubleOrNull() ?: continue
            // 美股白天（北京时间）看盘时，最近一笔盘后往往已经是美东昨天了；
            // 不隐藏，改成「昨盘后 / 昨盘前」，避免看起来像没有盘后数据。
            val afterDate = runCatching {
                MonthDay.parse(fields[24].take(6).trim(), DateTimeFormatter.ofPattern("MMM dd", Locale.US))
            }.getOrNull()
            val sameDay = afterDate == null ||
                afterDate == MonthDay.from(LocalDate.now(ZoneId.of("America/New_York")))
            // 时间带 AM 的是盘前，其余按盘后显示
            val base = if (fields[24].contains("AM", ignoreCase = true)) "盘前" else "盘后"
            out[symbol.id] = AfterHours(price, percent, if (sameDay) base else "昨$base")
        }
        return out
    }

    private fun eastmoneyQuotes(symbols: List<MarketSymbol>): Map<String, MarketQuote> {
        val out = mutableMapOf<String, MarketQuote>()
        symbols.chunked(20).forEach { chunk ->
            val secids = chunk.joinToString(",") { it.code }
            val url = "https://push2.eastmoney.com/api/qt/ulist.np/get" +
                "?fltt=2&fields=f2,f3,f4,f12,f13,f14,f124&secids=$secids"
            val root = json.parseToJsonElement(get(url)) as? JsonObject ?: return@forEach
            val diff = (root["data"] as? JsonObject)?.get("diff") as? JsonArray ?: return@forEach
            for (element in diff) {
                val row = element as? JsonObject ?: continue
                val code = row.string("f12") ?: continue
                val market = (row["f13"] as? JsonPrimitive)?.intOrNull ?: continue
                val symbol = chunk.firstOrNull { it.id == "em:$market.$code" } ?: continue
                out[symbol.id] = MarketQuote(
                    id = symbol.id,
                    label = symbol.label,
                    price = (row["f2"] as? JsonPrimitive)?.doubleOrNull,
                    change = (row["f4"] as? JsonPrimitive)?.doubleOrNull,
                    changePercent = (row["f3"] as? JsonPrimitive)?.doubleOrNull,
                    unit = symbol.unit,
                    region = symbol.regionKey(),
                    category = symbol.category,
                    quoteTime = (row["f124"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }
        }
        return out
    }

    private fun tencentQuotes(symbols: List<MarketSymbol>): Map<String, MarketQuote> {
        val query = symbols.joinToString(",") { it.code }
        // 腾讯返回 GBK 文本：v_usNDX="200~纳斯达克100~.NDX~29402.51~...";
        val text = String(getBytes("https://qt.gtimg.cn/q=$query"), Charset.forName("GBK"))
        val out = mutableMapOf<String, MarketQuote>()
        for (line in text.split(';')) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("v_")) continue
            val name = trimmed.substringAfter("v_").substringBefore('=')
            val symbol = symbols.firstOrNull { it.code.equals(name, ignoreCase = true) } ?: continue
            val fields = trimmed.substringAfter('"', "").substringBefore('"').split('~')
            if (fields.size < 33) continue
            out[symbol.id] = MarketQuote(
                id = symbol.id,
                label = symbol.label,
                price = fields[3].toDoubleOrNull(),
                change = fields[31].toDoubleOrNull(),
                changePercent = fields[32].toDoubleOrNull(),
                unit = symbol.unit,
                region = symbol.regionKey(),
                category = symbol.category,
            )
        }
        return out
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun MarketSymbol.failed(error: Throwable) = MarketQuote(
        id = id,
        label = label,
        price = null,
        change = null,
        changePercent = null,
        unit = unit,
        error = error.message ?: "请求失败",
        region = regionKey(),
        category = category,
    )

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        String(getBytes(url, headers), Charsets.UTF_8)

    private fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("行情接口 HTTP ${response.code}")
            return response.body.bytes()
        }
    }

    private companion object {
        const val SEARCH_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val NASDAQ_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
        )
    }
}
