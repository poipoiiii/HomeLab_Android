package com.poi.homelab.feature.market

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 本地消息评分：不调用 AI，只看消息本身的可验证特征。
 * 权重存在手机上，用户点「有用 / 没用」后每天微调一次。
 */
object MarketScorer {

    private val impactWords = listOf(
        "财报", "业绩", "营收", "净利", "亏损", "指引", "分红", "派息", "回购",
        "增持", "减持", "并购", "收购", "重组", "退市", "停牌", "复牌",
        "涨停", "跌停", "暴涨", "暴跌", "加息", "降息", "降准", "美联储",
        "通胀", "非农", "GDP", "关税", "制裁", "监管", "调查", "罚款",
        "破产", "违约", "裁员", "订单", "中标", "上市", "IPO", "涨价",
        "降价", "禁令", "危机", "战争",
    )

    private val uncertainWords = listOf(
        "传闻", "据悉", "消息人士", "知情人士", "网传", "爆料", "据说",
        "或将", "可能", "预计", "有望", "拟", "疑似", "不排除",
    )

    private val numberRegex = Regex("\\d")

    fun score(news: MarketNews, weights: NewsWeights): MarketNews {
        val text = news.title + " " + news.summary
        val impact = impactWords.count { text.contains(it) }.coerceAtMost(4)
        val uncertain = uncertainWords.count { text.contains(it) }.coerceAtMost(3)
        val hasNumber = if (numberRegex.containsMatchIn(text) || text.contains("亿") || text.contains("万")) 1 else 0
        val heatValue = min(log10(news.heat + 1.0), 2.0)

        var score = 38.0
        score += weights.impact * min(impact, 3)
        score += weights.specificity * hasNumber
        score += weights.heat * heatValue
        score -= weights.uncertainty * min(uncertain, 2)
        val finalScore = score.roundToInt().coerceIn(5, 98)

        val reasons = buildList {
            if (impact > 0) add("强影响词 ${min(impact, 3)} 个")
            if (hasNumber > 0) add("带具体数字")
            if (news.heat >= 50) add("讨论热度高")
            if (uncertain > 0) add("含不确定表述")
            if (isEmpty()) add("信息量偏少")
        }
        return news.copy(score = finalScore, reasons = reasons)
    }

    fun features(news: MarketNews): NewsFeatures {
        val text = news.title + " " + news.summary
        return NewsFeatures(
            impact = impactWords.count { text.contains(it) },
            uncertainty = uncertainWords.count { text.contains(it) },
            specificity = if (numberRegex.containsMatchIn(text) || text.contains("亿") || text.contains("万")) 1 else 0,
            heat = min(log10(news.heat + 1.0), 2.0),
        )
    }

    /** 有用样本抬高影响 / 具体 / 热度权重，没用样本反过来并加重不确定词惩罚。 */
    fun calibrate(weights: NewsWeights, feedback: List<NewsFeedback>): NewsWeights {
        var impact = weights.impact
        var uncertainty = weights.uncertainty
        var specificity = weights.specificity
        var heat = weights.heat
        for (item in feedback) {
            val sign = if (item.useful) 1.0 else -1.0
            impact = (impact + sign * 0.35 * item.features.impact).coerceIn(3.0, 16.0)
            uncertainty = (uncertainty - sign * 0.3 * item.features.uncertainty).coerceIn(3.0, 18.0)
            specificity = (specificity + sign * 0.3 * item.features.specificity).coerceIn(3.0, 16.0)
            heat = (heat + sign * 0.2 * item.features.heat).coerceIn(1.0, 12.0)
        }
        return NewsWeights(
            version = weights.version + 1,
            impact = impact,
            uncertainty = uncertainty,
            specificity = specificity,
            heat = heat,
        )
    }

    fun grade(score: Int): String = when {
        score >= 75 -> "高"
        score >= 55 -> "中"
        else -> "低"
    }
}
