package com.poi.homelab.feature.market

import java.time.LocalDate
import java.time.temporal.WeekFields

/**
 * 把日 K 聚合成周 K / 月 K 的区间涨跌幅。
 * 区间收益用「区间最后收盘 / 区间前一日收盘 - 1」，首根柱子用当天涨跌幅反推前收。
 */
fun periodReturns(
    bars: List<DailyBar>,
    period: MarketPeriod,
    limit: Int,
): List<PeriodReturn> {
    if (bars.isEmpty()) return emptyList()
    if (period == MarketPeriod.Day) {
        return bars.takeLast(limit).map { bar ->
            PeriodReturn(bar.date.takeLast(5), bar.changePercent)
        }
    }
    val weekFields = WeekFields.ISO
    val groups = LinkedHashMap<Int, MutableList<DailyBar>>()
    for (bar in bars) {
        val date = runCatching { LocalDate.parse(bar.date) }.getOrNull() ?: continue
        val key = if (period == MarketPeriod.Week) {
            date.get(weekFields.weekBasedYear()) * 100 + date.get(weekFields.weekOfWeekBasedYear())
        } else {
            date.year * 100 + date.monthValue
        }
        groups.getOrPut(key) { mutableListOf() } += bar
    }
    return groups.entries
        .sortedBy { it.key }
        .mapNotNull { (_, group) ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val last = group.lastOrNull() ?: return@mapNotNull null
            if (first.changePercent <= -100.0) return@mapNotNull null
            val base = first.close / (1 + first.changePercent / 100.0)
            if (base == 0.0) return@mapNotNull null
            val percent = (last.close / base - 1) * 100
            val label = if (period == MarketPeriod.Week) {
                last.date.takeLast(5)
            } else {
                last.date.take(7)
            }
            PeriodReturn(label, percent)
        }
        .takeLast(limit)
}
