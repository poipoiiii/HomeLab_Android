package com.poi.homelab.core.util

import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val amountFormat = NumberFormat.getNumberInstance(Locale.CHINA).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}

fun money(value: String?): String {
    val number = value?.replace(",", "")?.toDoubleOrNull() ?: return "—"
    return "¥" + amountFormat.format(number)
}

fun money(value: Double): String = "¥" + amountFormat.format(value)

fun signedMoney(value: String?, type: String): String {
    val number = value?.replace(",", "")?.toDoubleOrNull() ?: return "—"
    val sign = when (type) {
        "income" -> "+"
        "expense" -> "−"
        else -> ""
    }
    return "$sign¥" + amountFormat.format(kotlin.math.abs(number))
}

fun plainAmount(value: String?): Double =
    value?.replace(",", "")?.toDoubleOrNull() ?: 0.0

/** 字节数转成 KB/MB/GB/TB，用于上传进度、速度和文件统计。 */
fun fileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L * 1024L ->
        String.format(Locale.CHINA, "%.1f TB", bytes / 1099511627776.0)
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.CHINA, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun monthLabel(month: String): String {
    return runCatching {
        val parsed = YearMonth.parse(month)
        "${parsed.year}年${parsed.monthValue}月"
    }.getOrElse { month }
}

fun todayLabel(): String =
    LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))

fun shortDate(date: String, time: String): String {
    val day = date.takeLast(5).replace("-", "/")
    return if (time.isBlank()) day else "$day $time"
}

fun typeLabel(type: String): String = when (type) {
    "expense" -> "支出"
    "income" -> "收入"
    "transfer" -> "转账"
    else -> type
}

fun stateLabel(state: String): String = when (state) {
    "running" -> "运行中"
    "exited" -> "已停止"
    "restarting" -> "重启中"
    "paused" -> "已暂停"
    "dead" -> "异常"
    "created" -> "已创建"
    else -> state.ifBlank { "未知" }
}

/** "2026-10-29T21:10:00+08:00" → "10-29 21:10"；格式不认识时原样返回。 */
fun shortIsoTimestamp(value: String?): String {
    if (value.isNullOrBlank()) return "时间未知"
    return if (value.length >= 16) {
        value.substring(5, 16).replace('T', ' ')
    } else {
        value
    }
}

/** 余额来源（account.balance_source / candidate.source）转中文。 */
fun balanceSourceLabel(source: String?): String = when (source) {
    "sms" -> "短信"
    "notification" -> "通知"
    "ocr" -> "截图识别"
    "manual" -> "手工"
    else -> "未知来源"
}
