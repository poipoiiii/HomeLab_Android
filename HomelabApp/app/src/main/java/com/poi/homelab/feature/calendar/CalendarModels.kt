package com.poi.homelab.feature.calendar

import kotlinx.serialization.Serializable

const val CALENDAR_TRAVEL = "travel"
const val CALENDAR_IMPORTANT = "important"

/** 一条私人日历记录：旅行或重要事项。date 使用 yyyy-MM-dd。 */
@Serializable
data class CalendarEntry(
    val id: Long = 0L,
    val date: String,
    val title: String,
    val type: String = CALENDAR_TRAVEL,
    val location: String = "",
    val note: String = "",
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
