package com.poi.homelab.feature.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate

/**
 * 开屏广告跳过。
 *
 * 只在 [SUPPORTED] 里的应用切到前台后的一小段时间内工作：
 * 找顶部区域里带「跳过」字样的小按钮，点一次就收手。
 * 规则是自己写的包名白名单 + 文字匹配，不解析第三方订阅，也不复制 GKD 的代码。
 */
object SplashSkip {

    private const val TAG = "SplashSkip"
    private const val PREFS = "splash_skip"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COUNT_DAY = "count_day"
    private const val KEY_COUNT = "count"
    private const val KEY_EXTRA = "extra_packages"

    /** 每个应用只在这些时间点尝试，给广告按钮留出渲染时间。 */
    private val ATTEMPTS = longArrayOf(200, 650, 1300, 2100, 3000)

    /** 同一应用两次自动点击至少隔这么久，避免连环误点。 */
    private const val COOLDOWN_MS = 6000L

    /** 只认这些开屏广告按钮文字。 */
    private val BUTTON_TEXTS = listOf("跳过", "跳过广告", "跳过本次广告", "关闭广告")

    /**
     * 只在这些应用里生效。都是开屏广告带「跳过」的常见应用，
     * 没有把握的包不要往里加，宁可让用户自己点。
     */
    private val SUPPORTED = setOf(
        "com.tencent.qqmusic",       // QQ 音乐
        "com.netease.cloudmusic",    // 网易云音乐
        "com.sina.weibo",            // 微博
        "com.zhihu.android",         // 知乎
        "com.taobao.taobao",         // 淘宝
        "com.jingdong.app.mall",     // 京东
        "com.xingin.xhs",            // 小红书
        "com.ss.android.ugc.aweme",  // 抖音
        "tv.danmaku.bili",           // 哔哩哔哩
        "com.autonavi.minimap",      // 高德地图
        "com.eg.android.AlipayGphone", // 支付宝
        "com.qiyi.video",            // 爱奇艺
        "com.tencent.qqlive",        // 腾讯视频
        "com.youku.phone",           // 优酷
        "com.baidu.tieba",           // 百度贴吧
        "com.xiaomi.shop",           // 小米商城
        "com.sankuai.meituan",       // 美团
        "com.xunmeng.pinduoduo",     // 拼多多
        "com.tencent.androidqqmail", // QQ 邮箱
        "com.xueqiu.android",        // 雪球
        "com.max.xiaoheihe",         // 小黑盒
        "com.taptap",                // TapTap
        "com.hpbr.bosszhipin",       // BOSS 直聘
        "com.miui.player",           // 小米音乐
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastClickAt = HashMap<String, Long>()

    /** 白名单合并结果：内置 + 手选 + 订阅，改动时清空重建。 */
    @Volatile
    private var packagesCache: Set<String>? = null

    /** 每次窗口切换换一代，旧一代里还没跑的定时任务会自己作废，避免点完又补一刀。 */
    private var generation = 0

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) generation++
    }

    /** 首页「去广告」卡片用：今天替用户点掉了几次开屏。 */
    fun todayCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_COUNT_DAY, null) != todayKey()) return 0
        return prefs.getInt(KEY_COUNT, 0)
    }

    /** 内置名单里有几个应用。 */
    fun supportedCount(): Int = SUPPORTED.size

    /** 内置名单本身，设置页用来把这几项锁成常开。 */
    fun builtinPackages(): Set<String> = SUPPORTED

    /** 内置名单 + 用户在设置里额外加的应用 + 已启用订阅里的应用。 */
    fun packages(context: Context): Set<String> =
        packagesCache ?: buildPackages(context).also { packagesCache = it }

    private fun buildPackages(context: Context): Set<String> = buildSet {
        addAll(SUPPORTED)
        addAll(extraPackages(context))
        addAll(SplashSubscriptions.enabledPackages(context))
    }

    /** 订阅或手选名单变化后调用，下次匹配时重建。 */
    fun invalidate() {
        packagesCache = null
    }

    fun extraPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXTRA, emptySet())
            .orEmpty()
            .toSet()

    fun addPackage(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_EXTRA, extraPackages(context) + pkg).apply()
        invalidate()
    }

    fun removePackage(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_EXTRA, extraPackages(context) - pkg).apply()
        invalidate()
    }

    private fun recordClick(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val count = if (prefs.getString(KEY_COUNT_DAY, null) == today) prefs.getInt(KEY_COUNT, 0) else 0
        prefs.edit().putString(KEY_COUNT_DAY, today).putInt(KEY_COUNT, count + 1).apply()
    }

    private fun todayKey(): String = LocalDate.now().toString()

    /** 由 [UiAutomationService] 在每次窗口事件时调用。 */
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg !in packages(service)) return
        if (!isEnabled(service)) return
        if (SystemClock.uptimeMillis() - (lastClickAt[pkg] ?: 0L) < COOLDOWN_MS) return

        val current = ++generation
        ATTEMPTS.forEach { delay ->
            mainHandler.postDelayed({
                if (current != generation) return@postDelayed
                if (tryClick(service, pkg)) generation++
            }, delay)
        }
    }

    /** 找到并点击一次「跳过」。返回 true 表示已经点过。 */
    private fun tryClick(service: AccessibilityService, pkg: String): Boolean {
        if (SystemClock.uptimeMillis() - (lastClickAt[pkg] ?: 0L) < COOLDOWN_MS) return true
        val root = service.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false

        val metrics = service.resources.displayMetrics
        val maxWidth = metrics.widthPixels * 0.45f
        val maxHeight = metrics.heightPixels * 0.18f

        var target: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (target != null) return@walk
            if (!node.isVisibleToUser) return@walk
            val label = (node.text?.toString() ?: node.contentDescription?.toString())?.trim().orEmpty()
            if (!isSkipButton(label)) return@walk
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return@walk
            if (bounds.width() > maxWidth || bounds.height() > maxHeight) return@walk
            target = node
        }

        val node = target ?: return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (clickNode(service, node) || tap(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
            lastClickAt[pkg] = SystemClock.uptimeMillis()
            recordClick(service)
            Log.i(TAG, "点击跳过：$pkg ${node.text ?: node.contentDescription} $bounds")
            return true
        }
        return false
    }

    private fun isSkipButton(label: String): Boolean {
        if (label.isEmpty() || label.length > 8) return false
        return BUTTON_TEXTS.any { label.contains(it) }
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 3) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val (node, depth) = queue.removeFirst()
            visited++
            visit(node)
            if (depth >= 25) continue
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child to depth + 1)
            }
        }
    }
}
