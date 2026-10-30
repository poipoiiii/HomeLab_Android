package com.poi.homelab.feature.watch

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

const val CBG_BASE = "https://yjwujian.cbg.163.com"

private const val CBG_UA =
    "Mozilla/5.0 (Linux; Android 15; 25053RT47C) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** 服务端固定单页 15 条，count 传更大也只会返回 15。 */
private const val PAGE_SIZE = 15

/**
 * 永劫无间藏宝阁手机站的只读接口。
 * 网页版（网易大神里的藏宝阁也是同一个 H5）不需要登录，也没有签名参数，
 * 所以这里直接按它的两个接口取「商品类型」和「在售列表」。
 */
class CbgRepository(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 某个类目下的全部商品类型；服务端每页最多 15 条，翻到空页为止。 */
    suspend fun equipTypes(kindId: Int): List<CbgTarget> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, CbgTarget>()
        for (page in 1..4) {
            val url = "$CBG_BASE/cgi/api/get_aggregate_equip_type_list".toHttpUrl().newBuilder()
                .addQueryParameter("client_type", "h5")
                .addQueryParameter("count", "15")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("order_by", "selling_time DESC")
                .addQueryParameter("query_onsale", "1")
                .addQueryParameter("kindid", kindId.toString())
                .addQueryParameter("exter", "direct")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CBG_UA)
                .header("Referer", "$CBG_BASE/cgi/mweb/category/list?kindid=$kindId")
                .header("Accept", "application/json")
                .build()
            val root = runCatching { json.parseToJsonElement(execute(request)) as? JsonObject }
                .getOrNull() ?: break
            val list = root["equip_type_list"] as? JsonArray ?: break
            if (list.isEmpty()) break
            list.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val id = item.text("equip_type") ?: return@forEach
                found[id] = CbgTarget(
                    kindId = kindId,
                    searchType = item.text("search_type") ?: "role_skin",
                    equipType = id,
                    equipTypeName = item.text("equip_type_name").orEmpty(),
                    equipTypeDesc = item.text("equip_type_desc").orEmpty(),
                    minPriceYuan = item.number("min_price")?.let { it / 100 },
                )
            }
        }
        found.values.toList()
    }

    /**
     * 按规则里的筛选条件把在售商品全部翻完（服务端单页最多 15 条），价格从低到高。
     * 只翻一页会漏掉大量结果，所以按 pager.total_pages 一直取到最后一页。
     */
    suspend fun query(rule: WatchRule, maxPages: Int = 20): List<WatchItem> =
        withContext(Dispatchers.IO) {
            val target = rule.target
            if (target.equipType.isBlank()) return@withContext emptyList()

            val url = "$CBG_BASE/cgi-bin/recommend.py".toHttpUrl().newBuilder()
                .addQueryParameter("client_type", "h5")
                .addQueryParameter("act", "recommd_by_role")
                .build()
            val collected = LinkedHashMap<String, WatchItem>()
            var page = 1
            var totalPages = 1
            while (page <= maxPages) {
                val form = FormBody.Builder()
                    .add("search_type", target.searchType)
                    .add("count", PAGE_SIZE.toString())
                    .add("order_by", "price ASC")
                    .add("kindid", target.kindId.toString())
                    .add("equip_type", target.equipType)
                    .add("page", page.toString())
                    .add("exter", "direct")
                // 不选公示期时也要显式传空，服务端才按「不限」处理
                form.add("pass_fair_show", rule.fairShow?.toString().orEmpty())
                rule.star?.let { form.add("variation_unlock_num", it.toString()) }
                rule.minPriceYuan?.let { form.add("price_min", (it * 100L).toString()) }
                rule.maxPriceYuan?.let { form.add("price_max", (it * 100L).toString()) }

                val request = Request.Builder()
                    .url(url)
                    .post(form.build())
                    .header("User-Agent", CBG_UA)
                    .header("Referer", "$CBG_BASE/cgi/mweb/")
                    .header("Origin", CBG_BASE)
                    .build()

                val root = json.parseToJsonElement(execute(request)) as? JsonObject ?: break
                if (root.text("status_code") != "OK") {
                    throw IOException(root.text("msg") ?: "藏宝阁返回异常")
                }
                val list = (root["result"] as? JsonArray).orEmpty()
                if (list.isEmpty()) break
                list.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val eid = item.text("eid") ?: item.number("equipid")?.toString() ?: return@forEach
                    collected[eid] = WatchItem(
                        eid = eid,
                        name = item.text("equip_name")
                            ?: item.text("format_equip_name")
                            ?: target.equipTypeName,
                        priceCents = item.number("price")?.toLong() ?: 0L,
                        serverName = item.text("server_name").orEmpty(),
                        collectNum = item.number("collect_num") ?: 0,
                        fairShow = item.number("pass_fair_show") == 1,
                        url = "$CBG_BASE/cgi/mweb/equip/$eid",
                    )
                }
                totalPages = (root["pager"] as? JsonObject)?.number("total_pages") ?: page
                if (page >= totalPages) break
                page++
                delay(150)
            }
            collected.values.toList()
        }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("藏宝阁 HTTP ${response.code}")
            if (body.isBlank()) throw IOException("藏宝阁没有返回内容")
            return body
        }
    }
}

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.number(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
