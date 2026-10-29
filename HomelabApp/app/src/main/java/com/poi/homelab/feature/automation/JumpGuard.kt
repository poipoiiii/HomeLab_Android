package com.poi.homelab.feature.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 跳转守卫：应用之间直接切换时，如果不是白名单里的跳转，就按返回退回去。
 *
 * 判断依据只有「前台包名变化」：
 * - 用户自己切应用一般会经过桌面或最近任务（SystemUI），此时来源算白名单，不拦。
 * - 广告、分享、推广位那种 A 直接拉起 B 的跳转，来源是普通应用，目标又不是白名单，就退回。
 *
 * 局限：目标应用会闪一下，这是非 root 方案做不到无感的地方。
 */
object JumpGuard {

    private const val TAG = "JumpGuard"
    private const val PREFS = "jump_guard"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BLOCKED = "blocked_count"
    private const val KEY_LAST_SOURCE = "last_source"
    private const val KEY_LAST_TARGET = "last_target"

    /** 同一个目标连续触发时最多回退这么多次，防止和应用打架。 */
    private const val MAX_BACK_PER_TARGET = 3
    private const val REPEAT_WINDOW_MS = 8000L

    /**
     * 这些应用作为「来源」时放行：浏览器是用户主动打开的链接，
     * 游戏要能拉起微信/支付宝登录充值，微信支付宝本身也要拉起银行 App。
     */
    private val ALLOW_SOURCES = setOf(
        "com.poi.homelab",
        "com.miui.home",
        "com.android.systemui",
        "com.android.chrome",
        "com.microsoft.emmx",
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.tmgp.sgame",
        "com.hypergryph.arknights",
        "com.happyelements.AndroidAnimal",
        "com.tencent.jkchess",
        "com.netease.gl",
    )

    /** 跳到这些目标永远放行：登录、支付、系统弹窗、安装器、桌面。 */
    private val ALLOW_TARGETS = setOf(
        "com.poi.homelab",
        "com.miui.home",
        "com.android.systemui",
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.android.chrome",
        "com.microsoft.emmx",
        "com.android.settings",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.aod",
        "com.tencent.tmgp.sgame",
        "com.hypergryph.arknights",
        "com.happyelements.AndroidAnimal",
        "com.tencent.jkchess",
        "com.netease.gl",
    )

    private var lastPackage = ""
    private var expectReturnTo: String? = null
    private var blockedTarget = ""
    private var blockedCount = 0
    private var blockedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var kickJob: Runnable? = null

    /** 最近一次经过桌面 / 通知栏 / 最近任务的时间，用来识别“用户自己切的应用”。 */
    private var lastNeutralAt = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun blockedCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BLOCKED, 0)

    fun lastBlock(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val from = prefs.getString(KEY_LAST_SOURCE, null) ?: return null
        val to = prefs.getString(KEY_LAST_TARGET, null) ?: return null
        return from to to
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        val expected = expectReturnTo
        if (expected != null && pkg == expected) {
            // 刚被我们退回来，这一跳是回程，不算新的来源
            expectReturnTo = null
            lastPackage = pkg
            return
        }

        val source = lastPackage
        lastPackage = pkg
        if (pkg == "com.android.systemui" || pkg == "com.miui.home") {
            lastNeutralAt = SystemClock.uptimeMillis()
        }
        if (source.isEmpty() || source == pkg) return
        if (!isEnabled(service)) return
        if (source in ALLOW_SOURCES) return
        if (pkg in ALLOW_TARGETS) return

        val now = SystemClock.uptimeMillis()
        // 刚经过通知栏或桌面，说明是用户自己点的通知 / 图标，不是广告跳转
        if (lastNeutralAt > 0 && now - lastNeutralAt < 3000) return
        if (pkg == blockedTarget && now - blockedAt < REPEAT_WINDOW_MS && blockedCount >= MAX_BACK_PER_TARGET) {
            // 对方不死心，短时间内不再无限回退
            return
        }
        if (pkg != blockedTarget || now - blockedAt > REPEAT_WINDOW_MS) blockedCount = 0

        blockedTarget = pkg
        blockedAt = now
        blockedCount++
        record(service, source, pkg)
        Log.i(TAG, "退回跳转：$source -> $pkg")
        kickBack(service, source, pkg)
    }

    /**
     * 目标应用刚启动时，一次返回往往只是关掉它自己的引导页（淘宝就是先关红包弹窗）。
     * 隔 600ms 检查一次前台，还在目标应用就再按一次，最多三次，回到来源就停手。
     */
    private fun kickBack(service: AccessibilityService, source: String, target: String) {
        kickJob?.let { handler.removeCallbacks(it) }
        var attempts = 0
        val job = object : Runnable {
            override fun run() {
                if (attempts >= 3) return
                val current = service.rootInActiveWindow?.packageName?.toString().orEmpty()
                if (current.isNotEmpty() && current != target) return
                attempts++
                expectReturnTo = source
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                handler.postDelayed(this, 600)
            }
        }
        kickJob = job
        handler.postDelayed(job, 400)
    }

    private fun record(context: Context, from: String, to: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_BLOCKED, prefs.getInt(KEY_BLOCKED, 0) + 1)
            .putString(KEY_LAST_SOURCE, from)
            .putString(KEY_LAST_TARGET, to)
            .apply()
    }
}
