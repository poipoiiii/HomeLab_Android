package com.poi.homelab.feature.watch

import java.util.UUID
import kotlinx.serialization.Serializable

/** 藏宝阁的商品类目：kindid 和网页版左侧的四个入口一致。 */
data class CbgCategory(val kindId: Int, val label: String)

val cbgCategories = listOf(
    CbgCategory(3, "英雄皮肤"),
    CbgCategory(4, "兵器皮肤"),
    CbgCategory(5, "道具"),
    CbgCategory(6, "礼包"),
)

/** 监控目标：一个具体商品类型（例如「谪星·叠玉绮纱」）。 */
@Serializable
data class CbgTarget(
    val kindId: Int = 3,
    val searchType: String = "role_skin",
    val equipType: String = "",
    val equipTypeName: String = "",
    /** 「金 | 崔三娘」这类稀有度 + 所属英雄的描述。 */
    val equipTypeDesc: String = "",
    val minPriceYuan: Int? = null,
) {
    /** 稀有度，例如“金”“红”。 */
    val rarity: String get() = equipTypeDesc.substringBefore('|').trim()

    /** 所属英雄或武器，例如“季莹莹”。 */
    val owner: String get() = equipTypeDesc.substringAfter('|', "").trim()

    val ownerLabel: String
        get() = listOf(rarity, owner).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 规则类型：藏宝阁商品 / 火车票。 */
@Serializable
enum class WatchKind { CbgEquip, TrainTicket }

/** 火车票监控目标；code 用 12306 的车站电报码。 */
@Serializable
data class TrainTarget(
    val fromCode: String = "",
    val fromName: String = "",
    val toCode: String = "",
    val toName: String = "",
    val seat: String = "ze",
) {
    val route: String get() = "$fromName→$toName"

    val valid: Boolean get() = fromCode.isNotBlank() && toCode.isNotBlank() && fromCode != toCode
}

/** 12306 席别代码，和价格接口里的 *_price 字段对应。 */
val trainSeatOptions = listOf(
    "ze" to "二等座",
    "zy" to "一等座",
    "yz" to "硬座",
    "yw" to "硬卧",
    "rw" to "软卧",
    "swz" to "商务座",
)

fun seatLabel(code: String): String =
    trainSeatOptions.firstOrNull { it.first == code }?.second ?: "二等座"

/**
 * 一条监控规则。藏宝阁和火车票共用一套调度、去重和通知。
 */
@Serializable
data class WatchRule(
    val id: String = UUID.randomUUID().toString(),
    val kind: WatchKind = WatchKind.CbgEquip,
    val title: String = "",
    val enabled: Boolean = true,
    val intervalMinutes: Int = 60,
    /** 固定每天几点检测（0-23）；null 表示按 [intervalMinutes]。 */
    val dailyHour: Int? = null,
    val target: CbgTarget = CbgTarget(),
    val train: TrainTarget = TrainTarget(),
    val minPriceYuan: Int? = null,
    val maxPriceYuan: Int? = null,
    /** null 不限 / 1 只看公示期 / 0 排除公示期。 */
    val fairShow: Int? = null,
    /** null 不限，1..4 表示星格数量。 */
    val star: Int? = null,
    val lastRunAt: Long = 0L,
    val lastHitAt: Long = 0L,
    val lastResult: String = "",
    val seen: List<String> = emptyList(),
) {
    val isTrain: Boolean get() = kind == WatchKind.TrainTicket

    val displayTitle: String
        get() = title.ifBlank {
            if (isTrain) {
                if (train.valid) "火车票 ${train.route}" else "未命名规则"
            } else {
                target.equipTypeName.ifBlank { "未命名规则" }
            }
        }

    val listUrl: String
        get() = "https://yjwujian.cbg.163.com/cgi/mweb/category/detail" +
            "?search_type=${target.searchType}&equip_type=${target.equipType}&order_by=recommd"

    /** 给界面和推送用的一行筛选摘要。 */
    fun filterTags(): List<String> {
        if (isTrain) {
            return buildList {
                add(train.route)
                add(seatLabel(train.seat))
                add(dailyHour?.let { "每天 ${"%02d".format(it)}:00" } ?: "每${intervalLabel(intervalMinutes)}")
                add("最近假期往返")
            }
        }
        return buildList {
            priceLabel()?.let(::add)
            when (fairShow) {
                1 -> add("只看公示期")
                0 -> add("排除公示期")
            }
            star?.let { add("${it}星") }
            add(dailyHour?.let { "每天 ${"%02d".format(it)}:00" } ?: "每${intervalLabel(intervalMinutes)}")
        }
    }

    fun priceLabel(): String? {
        val min = minPriceYuan
        val max = maxPriceYuan
        return when {
            min != null && max != null -> "¥$min-$max"
            min != null -> "≥¥$min"
            max != null -> "≤¥$max"
            else -> null
        }
    }
}

/** 本地保存的全部规则。 */
@Serializable
data class WatchSettings(
    val rules: List<WatchRule> = emptyList(),
)

/** 一次查询命中的一条在售商品。 */
data class WatchItem(
    val eid: String,
    val name: String,
    val priceCents: Long,
    val serverName: String,
    val collectNum: Int,
    val fairShow: Boolean,
    val url: String,
) {
    val priceText: String
        get() = if (priceCents % 100L == 0L) {
            (priceCents / 100L).toString()
        } else {
            String.format(java.util.Locale.CHINA, "%.2f", priceCents / 100.0)
        }
}

/** 一次检测的结果，用来给界面显示一句话。 */
data class WatchReport(
    val checked: Int,
    val matched: Int,
    val pushed: Int,
    val text: String,
)

fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}分钟"
    minutes % 60 == 0 && minutes < 1440 -> "${minutes / 60}小时"
    minutes == 1440 -> "24小时"
    else -> "${minutes}分钟"
}

val watchIntervalOptions = listOf(15, 30, 60, 120, 180, 360, 720, 1440)
