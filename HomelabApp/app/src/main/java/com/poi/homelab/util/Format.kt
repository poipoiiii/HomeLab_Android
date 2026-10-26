package com.poi.homelab.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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
