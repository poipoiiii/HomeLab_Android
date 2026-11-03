package com.poi.homelab.feature.watch

import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一段连续假期，例如 2026 年劳动节 5-1 ~ 5-5。 */
data class HolidaySpan(val name: String, val start: LocalDate, val end: LocalDate)

/**
 * 法定节假日表，用 timor.tech 的公开接口（含调休补班标记），按年缓存。
 * 只读、每天最多取一次，失败时返回空，由调用方提示。
 */
object HolidayCalendar {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = mutableMapOf<Int, List<HolidaySpan>>()

    /** 距离今天最近、且还没结束的假期（含正在进行中的）。 */
    suspend fun nearest(today: LocalDate = LocalDate.now()): HolidaySpan? {
        val spans = mutableListOf<HolidaySpan>()
        spans += year(today.year)
        spans += year(today.year + 1)
        spans.filter { it.end >= today }.minByOrNull { it.start }?.let { return it }
        // 下一年的放假安排通常 11 月才公布；空档期先按元旦 1 月 1 日单日推算
        val newYear = LocalDate.of(today.year + 1, 1, 1)
        return if (newYear > today) {
            HolidaySpan("元旦（按 1 月 1 日推算）", newYear, newYear)
        } else {
            null
        }
    }

    private suspend fun year(year: Int): List<HolidaySpan> = withContext(Dispatchers.IO) {
        cache[year]?.let { return@withContext it }
        val request = Request.Builder()
            .url("https://timor.tech/api/holiday/year/$year")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
            .build()
        val body = runCatching {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        val parsed = parse(body)
        if (parsed.isNotEmpty()) cache[year] = parsed
        parsed
    }

    private fun parse(body: String): List<HolidaySpan> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val holiday = root["holiday"] as? JsonObject ?: return emptyList()
        val days = mutableListOf<Pair<String, LocalDate>>()
        holiday.entries.forEach { (_, value) ->
            val obj = value as? JsonObject ?: return@forEach
            val isHoliday = (obj["holiday"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!isHoliday) return@forEach
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val dateText = (obj["date"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@forEach
            days += name to date
        }
        if (days.isEmpty()) return emptyList()
        val spans = mutableListOf<HolidaySpan>()
        days.sortedBy { it.second }.forEach { (name, date) ->
            val last = spans.lastOrNull()
            if (last != null && last.name == name && last.end.plusDays(1) == date) {
                spans[spans.size - 1] = last.copy(end = date)
            } else {
                spans += HolidaySpan(name, date, date)
            }
        }
        return spans
    }
}
