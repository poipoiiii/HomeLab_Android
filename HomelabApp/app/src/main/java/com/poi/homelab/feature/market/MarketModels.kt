package com.poi.homelab.feature.market

import kotlinx.serialization.Serializable

/** 东方财富批量接口能覆盖 A 股、港股、美股、指数、黄金；纳指 100 只有腾讯的代码是对的。 */
const val MARKET_SOURCE_EASTMONEY = "eastmoney"
const val MARKET_SOURCE_TENCENT = "tencent"

/** 一条自选行情；[code] 在东财是 secid（如 1.000001），在腾讯是代码（如 usNDX）。 */
@Serializable
data class MarketSymbol(
    val id: String,
    val label: String,
    val code: String,
    val source: String = MARKET_SOURCE_EASTMONEY,
    val unit: String = "",
    /** 留空时按代码自动判断，一般不用改。 */
    val region: String = "",
    /** 用户手动分类；留空时按地区自动分组。 */
    val category: String = "",
)

/** 一次刷新后的报价，price 为空表示这一条没取到。 */
data class MarketQuote(
    val id: String,
    val label: String,
    val price: Double?,
    val change: Double?,
    val changePercent: Double?,
    val unit: String = "",
    val error: String? = null,
    /** 美股盘前 / 盘后价，其他市场为空。 */
    val afterPrice: Double? = null,
    val afterChangePercent: Double? = null,
    val afterLabel: String = "盘后",
    /** 地区分组，用于把 A 股、港股、美股分开展示。 */
    val region: String = MARKET_REGION_OTHER,
    /** 用户手动分类，留空时界面按地区显示。 */
    val category: String = "",
    /** 行情时间戳（秒），用于判断这份数据是不是今天开盘前的。 */
    val quoteTime: Long = 0,
    /** true 表示当前展示的是上一个交易日的涨跌幅（还没开盘）。 */
    val previousSession: Boolean = false,
)

const val MARKET_REGION_CN = "CN"
const val MARKET_REGION_HK = "HK"
const val MARKET_REGION_US = "US"
const val MARKET_REGION_OTHER = "OTHER"

/** 按代码前缀判断地区；以后要手动覆盖时给 region 赋值即可。 */
fun MarketSymbol.regionKey(): String {
    if (region.isNotBlank()) return region
    if (source == MARKET_SOURCE_TENCENT) {
        return when {
            code.startsWith("us", ignoreCase = true) -> MARKET_REGION_US
            code.startsWith("hk", ignoreCase = true) -> MARKET_REGION_HK
            code.startsWith("sh", ignoreCase = true) || code.startsWith("sz", ignoreCase = true) -> MARKET_REGION_CN
            else -> MARKET_REGION_OTHER
        }
    }
    return when (code.substringBefore('.').toIntOrNull()) {
        0, 1 -> MARKET_REGION_CN
        116 -> MARKET_REGION_HK
        105, 106, 107 -> MARKET_REGION_US
        else -> MARKET_REGION_OTHER
    }
}

fun marketRegionLabel(region: String): String = when (region) {
    MARKET_REGION_CN -> "中国"
    MARKET_REGION_HK -> "港股"
    MARKET_REGION_US -> "美股"
    else -> "其他"
}

fun marketRegionOrder(region: String): Int = when (region) {
    MARKET_REGION_CN -> 0
    MARKET_REGION_HK -> 1
    MARKET_REGION_US -> 2
    else -> 3
}

/** 搜索接口的一行结果，[market] 只用来给用户区分「美股 / 港股 / 指数」。 */
data class MarketSearchResult(
    val symbol: MarketSymbol,
    val market: String,
)

/** 默认自选：国内金价、KOSPI；科创 50 和大盘指数一起置顶。 */
val defaultMarketSymbols: List<MarketSymbol> = listOf(
    MarketSymbol("em:118.AU9999", "国内金价", "118.AU9999", unit = "元/克"),
    MarketSymbol("em:100.KS11", "KOSPI", "100.KS11"),
)

/** 默认置顶的大盘指数，用户可以在编辑弹窗里排序、增删。 */
val defaultPinnedMarketSymbols: List<MarketSymbol> = listOf(
    MarketSymbol("em:1.000001", "上证指数", "1.000001"),
    MarketSymbol("em:0.399001", "深证成指", "0.399001"),
    MarketSymbol("tx:usNDX", "纳斯达克100", "usNDX", source = MARKET_SOURCE_TENCENT, region = MARKET_REGION_US),
    MarketSymbol("em:100.DJIA", "道琼斯", "100.DJIA", region = MARKET_REGION_US),
    MarketSymbol("em:100.SPX", "标普500", "100.SPX", region = MARKET_REGION_US),
    MarketSymbol("em:1.000688", "科创50", "1.000688"),
)

fun pinnedMarketSymbolIds(pinned: List<MarketSymbol>): Set<String> = pinned.map { it.id }.toSet()

/** 置顶 + 自选，去重后用于拉行情。 */
fun allMarketSymbols(
    pinned: List<MarketSymbol>,
    watchlist: List<MarketSymbol>,
): List<MarketSymbol> = (pinned + watchlist).distinctBy { it.id }

fun findMarketSymbol(
    pinned: List<MarketSymbol>,
    watchlist: List<MarketSymbol>,
    id: String?,
): MarketSymbol? = id?.let { symbolId ->
    allMarketSymbols(pinned, watchlist).firstOrNull { it.id == symbolId }
}

/** 界面分组标题：用户分类优先，没填才按地区。 */
fun MarketQuote.categoryLabel(): String =
    category.ifBlank { marketRegionLabel(region) }

/** 一根日 K；[changePercent] 是当天收盘涨跌幅。 */
data class DailyBar(
    val date: String,
    val close: Double,
    val changePercent: Double,
)

/** 分时图上的一个点，[time] 是 "HH:mm"。 */
data class MinutePoint(val time: String, val price: Double)

/** 一天的分时数据，[preClose] 是昨收，用来算分时涨跌。 */
data class IntradaySeries(
    val preClose: Double,
    val points: List<MinutePoint>,
)

enum class MarketPeriod(val label: String) {
    Day("日K"),
    Week("周K"),
    Month("月K"),
}

/** 历史条形图的一根柱子；[label] 用于展示，[percent] 是区间涨跌幅。 */
data class PeriodReturn(val label: String, val percent: Double)

const val MARKET_EVENT_EARNINGS = "earnings"

/** 日历上的一个事件，目前主要是美股财报。 */
@Serializable
data class MarketEvent(
    val id: String,
    val date: String,
    val title: String,
    val detail: String = "",
    val symbol: String = "",
    val kind: String = MARKET_EVENT_EARNINGS,
)

/** 一条 7x24 市场消息；评分与理由是本地算法算出来的。 */
@Serializable
data class MarketNews(
    val id: String,
    val title: String,
    val summary: String,
    val time: String,
    val date: String,
    val stocks: List<String> = emptyList(),
    /** 分享 + 评论数，用来衡量消息热度。 */
    val heat: Int = 0,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    /** 1 有用 / -1 没用 / 0 未评价。 */
    val feedback: Int = 0,
)

/** 评分算法的可调权重；每天根据反馈微调一次。 */
@Serializable
data class NewsWeights(
    val version: Int = 1,
    val impact: Double = 7.0,
    val uncertainty: Double = 9.0,
    val specificity: Double = 8.0,
    val heat: Double = 5.0,
)

/** 一条消息命中的特征，校准算法时按它调整权重。 */
@Serializable
data class NewsFeatures(
    val impact: Int = 0,
    val uncertainty: Int = 0,
    val specificity: Int = 0,
    val heat: Double = 0.0,
)

@Serializable
data class NewsFeedback(
    val newsId: String,
    val title: String,
    val date: String,
    val useful: Boolean,
    val features: NewsFeatures = NewsFeatures(),
    /** 已经折进权重的样本不再参与下一次校准，但保留下来给用户查。 */
    val calibrated: Boolean = false,
)

/** 本地评分与反馈记录，全部只存在手机上。 */
data class MarketMind(
    val weights: NewsWeights = NewsWeights(),
    val feedback: List<NewsFeedback> = emptyList(),
    val usefulTotal: Int = 0,
    val uselessTotal: Int = 0,
    val lastCalibrated: String = "",
    val newsCache: List<MarketNews> = emptyList(),
    val earningsDate: String = "",
    val earnings: List<MarketEvent> = emptyList(),
)

