package com.poi.homelab.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Homarr `/api/boards` 的返回项，只保留 App 需要判断的字段。 */
@Serializable
data class HomarrBoardSummary(
    val id: String = "",
    val name: String = "",
    val isHome: Boolean = false,
    val isMobileHome: Boolean = false,
)

/** Homarr `/api/apps` 的返回项。href 可能是绝对地址，也可能是 `/branding/...` 这种站内路径。 */
@Serializable
data class HomarrApp(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val iconUrl: String = "",
    val href: String? = null,
    val pingUrl: String? = null,
)

/** 看板布局：Mobile 的 breakpoint 是 0，Base 通常 768。取「不超过屏宽的最大 breakpoint」。 */
@Serializable
data class HomarrLayout(
    val id: String = "",
    val name: String = "",
    val columnCount: Int = 6,
    val breakpoint: Int = 0,
)

@Serializable
data class HomarrSection(
    val id: String = "",
    val kind: String = "",
    val xOffset: Int = 0,
    val yOffset: Int = 0,
)

/** 每个 item 在某套布局里的网格坐标，单位是格子而不是像素。 */
@Serializable
data class HomarrItemLayout(
    val layoutId: String = "",
    val xOffset: Int = 0,
    val yOffset: Int = 0,
    val width: Int = 1,
    val height: Int = 1,
    val sectionId: String? = null,
)

@Serializable
data class HomarrItem(
    val id: String = "",
    val kind: String = "",
    val options: JsonObject = JsonObject(emptyMap()),
    val layouts: List<HomarrItemLayout> = emptyList(),
    val integrationIds: List<String> = emptyList(),
    val advancedOptions: JsonObject? = null,
)

/** tRPC `board.getBoardByName` 返回的看板结构。 */
@Serializable
data class HomarrBoard(
    val id: String = "",
    val name: String = "",
    val items: List<HomarrItem> = emptyList(),
    val layouts: List<HomarrLayout> = emptyList(),
    val sections: List<HomarrSection> = emptyList(),
)
