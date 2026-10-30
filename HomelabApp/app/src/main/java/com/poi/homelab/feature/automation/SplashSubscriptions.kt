package com.poi.homelab.feature.automation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 开屏广告的规则订阅。
 *
 * 只用第三方订阅回答一个问题：哪些应用有开屏广告。
 * 具体的点击动作仍然由 [SplashSkip] 自己判断（顶部、小尺寸、带「跳过」、单次、冷却），
 * 所以不解析 matches 选择器，也不复制任何 GKD 代码。
 *
 * 订阅默认全部关闭：启用得越多，白名单越大，无障碍事件里的匹配面也越大。
 */
object SplashSubscriptions {

    data class Source(
        val id: String,
        val name: String,
        val desc: String,
        val url: String,
        val custom: Boolean = false,
    )

    /**
     * 只放能长期访问、体积可控的订阅；用户还可以自己加地址。
     * 地址走 jsDelivr 的 GitHub 镜像，国内直连可用。
     */
    val presets = listOf(
        Source(
            id = "adpro",
            name = "Adpro-Team",
            desc = "社区维护，作者已停更、规则稳定；约 140 个应用有独立开屏规则",
            url = "https://cdn.jsdelivr.net/gh/Adpro-Team/GKD_subscription@main/dist/Adpro_gkd.json5",
        ),
        Source(
            id = "aisouler",
            name = "AIsouler",
            desc = "社区维护，针对国内应用优化；约 150 个应用有独立开屏规则",
            url = "https://cdn.jsdelivr.net/gh/AIsouler/GKD_subscription@main/dist/AIsouler_gkd.json5",
        ),
    )

    private const val PREFS = "splash_subs"
    private const val KEY_ENABLED = "enabled_sources"
    private const val KEY_CUSTOM = "custom_sources"
    private const val KEY_PACKAGES = "packages_"
    private const val KEY_SYNCED_AT = "synced_at_"

    /** 订阅文件本身不大，超过这个体积直接放弃，避免把内存吃满。 */
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cache: Set<String>? = null

    fun sources(context: Context): List<Source> = presets + customSources(context)

    fun customSources(context: Context): List<Source> =
        prefs(context).getStringSet(KEY_CUSTOM, emptySet()).orEmpty()
            .mapNotNull { raw ->
                val parts = raw.split('\u0001')
                if (parts.size < 3) return@mapNotNull null
                Source(parts[0], parts[1], "自定义订阅", parts[2], custom = true)
            }
            .sortedBy { it.name.lowercase() }

    fun enabledSources(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet()

    fun isEnabled(context: Context, id: String): Boolean = id in enabledSources(context)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val current = enabledSources(context)
        val next = if (enabled) current + id else current - id
        prefs(context).edit().putStringSet(KEY_ENABLED, next).apply()
        cache = null
        SplashSkip.invalidate()
    }

    fun addCustomSource(context: Context, url: String): Source? {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
        val id = "c" + clean.hashCode()
        val name = runCatching { java.net.URI(clean).host ?: "自定义订阅" }.getOrDefault("自定义订阅")
        val all = prefs(context).getStringSet(KEY_CUSTOM, emptySet()).orEmpty()
        if (all.any { it.startsWith("$id\u0001") }) return null
        prefs(context).edit().putStringSet(KEY_CUSTOM, all + "$id\u0001$name\u0001$clean").apply()
        return Source(id, name, "自定义订阅", clean, custom = true)
    }

    fun removeSource(context: Context, id: String) {
        val all = prefs(context).getStringSet(KEY_CUSTOM, emptySet()).orEmpty()
        val next = all.filterNot { it.startsWith("$id\u0001") }.toSet()
        prefs(context).edit()
            .putStringSet(KEY_CUSTOM, next)
            .putStringSet(KEY_ENABLED, enabledSources(context) - id)
            .remove(KEY_PACKAGES + id)
            .remove(KEY_SYNCED_AT + id)
            .apply()
        cache = null
        SplashSkip.invalidate()
    }

    fun syncedAt(context: Context, id: String): Long =
        prefs(context).getLong(KEY_SYNCED_AT + id, 0L)

    /** 某个订阅里解析出来的包，含未安装的。 */
    fun packages(context: Context, id: String): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES + id, emptySet()).orEmpty().toSet()

    /** 所有已启用订阅的包名并集，[SplashSkip] 直接拿去用。 */
    fun enabledPackages(context: Context): Set<String> =
        cache ?: run {
            val merged = enabledSources(context)
                .flatMap { packages(context, it) }
                .toSet()
            cache = merged
            merged
        }

    fun invalidate() {
        cache = null
    }

    /** 下载并解析一个订阅，返回解析到的包数。 */
    suspend fun sync(context: Context, source: Source): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(source.url).get().build()
            val text = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > MAX_BYTES) error("订阅文件过大")
                body.string()
            }
            val packages = extractPackages(text)
            if (packages.isEmpty()) error("没有解析到开屏广告规则")
            prefs(context).edit()
                .putStringSet(KEY_PACKAGES + source.id, packages)
                .putLong(KEY_SYNCED_AT + source.id, System.currentTimeMillis())
                .apply()
            cache = null
            SplashSkip.invalidate()
            packages.size
        }
    }

    /**
     * 从 GKD 订阅（JSON5）里挑出「带开屏广告规则组的应用包名」。
     *
     * 不做完整 JSON5 解析：按文本顺序扫 `id:` 与 `name:` 两个键，
     * 记住最近的 app id，遇到名字含「开屏」的组就把这个包记下来。
     * `matches` 里的 `[id="..."]` 不会命中 `id:` 这个模式，所以不会被误当成包名。
     */
    internal fun extractPackages(json5: String): Set<String> {
        val appsStart = Regex("""\bapps\s*:\s*\[""").find(json5)?.range?.last
            ?: return emptySet()
        val body = json5.substring(appsStart)

        val idRegex = Regex("""\bid\s*:\s*['"]([A-Za-z][\w.]*\.[\w.]+)['"]""")
        val nameRegex = Regex("""\bname\s*:\s*['"]([^'"]*)['"]""")

        data class Hit(val index: Int, val value: String, val isId: Boolean)

        val hits = ArrayList<Hit>()
        idRegex.findAll(body).forEach { hits += Hit(it.range.first, it.groupValues[1], true) }
        nameRegex.findAll(body).forEach { hits += Hit(it.range.first, it.groupValues[1], false) }
        hits.sortBy { it.index }

        val result = LinkedHashSet<String>()
        var current: String? = null
        for (hit in hits) {
            if (hit.isId) {
                current = hit.value
            } else if (current != null && hit.value.contains("开屏")) {
                result += current
            }
        }
        return result
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
