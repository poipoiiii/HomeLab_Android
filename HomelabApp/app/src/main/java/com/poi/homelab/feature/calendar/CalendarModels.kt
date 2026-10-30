package com.poi.homelab.feature.calendar

import kotlinx.serialization.Serializable

const val CALENDAR_TRAVEL = "travel"
const val CALENDAR_IMPORTANT = "important"
const val CALENDAR_ANNIVERSARY = "anniversary"
const val CALENDAR_TODO = "todo"

/**
 * 一条私人日历记录。date 使用 yyyy-MM-dd，time 使用 HH:mm（空表示不排时间）。
 * reminder 为 true 时到点发本地通知；done 只对待办有意义。
 */
@Serializable
data class CalendarEntry(
    val id: Long = 0L,
    val date: String,
    val title: String,
    val type: String = CALENDAR_TRAVEL,
    val location: String = "",
    val note: String = "",
    val time: String = "",
    val reminder: Boolean = false,
    val done: Boolean = false,
    val createdAt: Long = 0L,
)

/** Agent 批量新增日历时使用的输入结构。 */
@Serializable
data class CalendarEntryInput(
    val date: String,
    val title: String,
    val type: String = CALENDAR_IMPORTANT,
    val location: String = "",
    val note: String = "",
)

fun calendarTypeLabel(type: String): String = when (type) {
    CALENDAR_TRAVEL -> "旅行"
    CALENDAR_ANNIVERSARY -> "纪念日"
    CALENDAR_TODO -> "待办"
    else -> "重要"
}
