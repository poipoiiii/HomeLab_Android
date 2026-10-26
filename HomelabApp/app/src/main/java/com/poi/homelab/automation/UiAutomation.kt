package com.poi.homelab.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 无障碍服务只做一件事：把当前窗口的节点树暴露给 [UiAutomation]。
 * 真正的点击、输入、滚动都在 [UiAutomation] 里按需执行，不做常驻监听。
 */
class UiAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AutomationRestore.markConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AutomationRestore.markDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: UiAutomationService? = null
            internal set
    }
}

/** 读屏 + 点击/输入/滚动等界面操作，全部在主线程执行。 */
object UiAutomation {

    private const val MAX_NODES = 60
    private const val MAX_WALK = 2000
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean {
        if (UiAutomationService.instance != null) return true
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (enabled.isBlank()) return false
        val component = ComponentName(context, UiAutomationService::class.java)
        return enabled.split(':').any { it.equals(component.flattenToString(), true) }
    }

    suspend fun status(context: Context): JsonObject = withContext(Dispatchers.Main) {
        val service = UiAutomationService.instance
        val root = service?.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty()
        buildJsonObject {
            put("enabled", isEnabled(context))
            put("connected", service != null)
            put("foreground_package", pkg)
            if (pkg.isNotEmpty()) put("foreground_label", labelOf(context, pkg))
            if (service == null) {
                put("hint", "无障碍服务未开启：设置 → 无障碍 → 已下载的应用 → poi's home")
            }
        }
    }

    suspend fun openApp(context: Context, target: String): JsonObject = withContext(Dispatchers.Main) {
        val service = UiAutomationService.instance ?: return@withContext needAccessibility()
        val pkg = resolvePackage(context, target)
            ?: return@withContext buildJsonObject { put("error", "没有找到应用：$target") }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return@withContext buildJsonObject { put("error", "这个应用没有可用的启动入口：$pkg") }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val failure = runCatching { context.startActivity(intent) }.exceptionOrNull()
        if (failure != null) {
            return@withContext buildJsonObject {
                put("error", "启动失败：${failure.message ?: failure.javaClass.simpleName}")
            }
        }

        var foreground = ""
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            delay(250)
            foreground = service.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (foreground == pkg) break
        }
        buildJsonObject {
            put("package", pkg)
            put("label", labelOf(context, pkg))
            put("foreground_package", foreground)
            put("opened", foreground == pkg)
            if (foreground != pkg) put("hint", "应用已启动但前台仍是 $foreground，可以稍后用 list_screen 再确认")
        }
    }

    suspend fun readScreen(context: Context): JsonObject = withContext(Dispatchers.Main) {
        val service = UiAutomationService.instance ?: return@withContext needAccessibility()
        val root = service.rootInActiveWindow ?: return@withContext noWindow()
        val pkg = root.packageName?.toString().orEmpty()
        val nodes = visibleNodes(root)
        val rows = nodes.mapIndexed { index, node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            buildJsonObject {
                put("index", index)
                if (text.isNotEmpty()) put("text", text.take(100))
                if (desc.isNotEmpty()) put("desc", desc.take(100))
                node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { put("id", it) }
                put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
                put(
                    "bounds",
                    JsonArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { JsonPrimitive(it) }),
                )
                if (node.isClickable) put("clickable", true)
                if (node.isEditable) put("editable", true)
                if (node.isScrollable) put("scrollable", true)
                if (node.isFocused) put("focused", true)
                if (!node.isEnabled) put("enabled", false)
                if (node.isCheckable) put("checked", node.isChecked)
            }
        }
        buildJsonObject {
            put("package", pkg)
            put("app", labelOf(context, pkg))
            put("count", rows.size)
            put("truncated", nodes.size >= MAX_NODES)
            put("nodes", JsonArray(rows))
        }
    }

    suspend fun click(context: Context, target: String, by: String?): JsonObject =
        withContext(Dispatchers.Main) {
            val service = UiAutomationService.instance ?: return@withContext needAccessibility()
            val root = service.rootInActiveWindow ?: return@withContext noWindow()
            val mode = by?.trim()?.lowercase().orEmpty().ifBlank { "text" }
            val node = when (mode) {
                "index" -> {
                    val wanted = target.trim().toIntOrNull()
                        ?: return@withContext buildJsonObject { put("error", "index 必须是 list_screen 里的序号") }
                    visibleNodes(root).getOrNull(wanted)
                }
                else -> findNode(root, target, mode)
            } ?: return@withContext buildJsonObject {
                put("error", "当前界面没有找到「$target」")
                put("hint", "先调用 list_screen 看真实文字和序号，再点")
            }

            val name = node.text?.toString().takeUnless { it.isNullOrBlank() }
                ?: node.contentDescription?.toString().takeUnless { it.isNullOrBlank() }
                ?: target
            val clicked = clickNode(service, node)
            buildJsonObject {
                put("clicked", clicked)
                put("target", name.take(100))
                if (!clicked) put("hint", "节点不可点击且坐标点击也失败，界面可能已变化，请重新 list_screen")
            }
        }

    suspend fun inputText(context: Context, text: String, target: String?): JsonObject =
        withContext(Dispatchers.Main) {
            val service = UiAutomationService.instance ?: return@withContext needAccessibility()
            val root = service.rootInActiveWindow ?: return@withContext noWindow()
            val located = target?.takeIf { it.isNotBlank() }?.let { findNode(root, it, "text") }
            val field = located?.let { editableOf(it) } ?: firstEditable(root)
                ?: return@withContext buildJsonObject {
                    put("error", "当前界面没有找到可输入的输入框")
                    put("hint", "先用 click_ui 点一下输入框，或用 list_screen 确认输入框位置")
                }

            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            var method = "set_text"
            var applied = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!applied) {
                // 自定义输入框（如微信）常常忽略 ACTION_SET_TEXT，退化成聚焦 + 粘贴。
                val bounds = Rect()
                field.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    tap(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
                delay(250)
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("homelab", text))
                delay(150)
                applied = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                method = "paste"
            }
            buildJsonObject {
                put("applied", applied)
                put("method", method)
                put("length", text.length)
                if (!applied) put("hint", "输入失败，确认输入框已聚焦，或改用 click_ui 后重试")
            }
        }

    suspend fun press(key: String): JsonObject = withContext(Dispatchers.Main) {
        val service = UiAutomationService.instance ?: return@withContext needAccessibility()
        val action = when (key.trim().lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext buildJsonObject {
                put("error", "不支持的按键：$key，只能是 back/home/recents/notifications")
            }
        }
        buildJsonObject {
            put("performed", service.performGlobalAction(action))
            put("key", key)
        }
    }

    suspend fun scroll(direction: String?): JsonObject = withContext(Dispatchers.Main) {
        val service = UiAutomationService.instance ?: return@withContext needAccessibility()
        val root = service.rootInActiveWindow ?: return@withContext noWindow()
        val forward = direction?.trim()?.lowercase() != "up" &&
            direction?.trim()?.lowercase() != "backward"
        val scrollable = firstScrollable(root)

        var performed = false
        var method = "node"
        if (scrollable != null) {
            performed = scrollable.performAction(
                if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                },
            )
        }
        if (!performed) {
            val metrics = service.resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            val fromY = height * if (forward) 0.72f else 0.28f
            val toY = height * if (forward) 0.28f else 0.72f
            performed = swipe(service, width / 2f, fromY, width / 2f, toY)
            method = "gesture"
        }
        buildJsonObject {
            put("scrolled", performed)
            put("direction", if (forward) "down" else "up")
            put("method", method)
        }
    }

    suspend fun waitFor(text: String, timeoutMs: Int): JsonObject = withContext(Dispatchers.Main) {
        val service = UiAutomationService.instance ?: return@withContext needAccessibility()
        val timeout = timeoutMs.coerceIn(500, 15_000)
        val deadline = SystemClock.uptimeMillis() + timeout
        var found = false
        var foreground = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            foreground = root?.packageName?.toString().orEmpty()
            if (root != null && findNode(root, text, "text") != null) {
                found = true
                break
            }
            delay(250)
        }
        buildJsonObject {
            put("found", found)
            put("text", text)
            put("timeout_ms", timeout)
            put("foreground_package", foreground)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun needAccessibility(): JsonObject = buildJsonObject {
        put("error", "无障碍服务未开启，无法读取或操作其他应用的界面")
        put("need_accessibility", true)
        put("hint", "到 设置 → 无障碍 → 已下载的应用 里打开「poi's home」，然后重试")
    }

    private fun noWindow(): JsonObject = buildJsonObject {
        put("error", "当前没有可读取的窗口，可能处于锁屏或应用正在启动")
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        limit: Int,
        visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val (node, depth) = queue.removeFirst()
            visited++
            visit(node)
            if (depth >= 40) continue
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child to depth + 1)
            }
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        target: String,
        by: String,
    ): AccessibilityNodeInfo? {
        val wanted = target.trim()
        if (wanted.isEmpty()) return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walk(root, MAX_WALK) { node ->
            if (matches(node, wanted, by)) candidates += node
        }
        return candidates.firstOrNull { exact(it, wanted, by) } ?: candidates.firstOrNull()
    }

    private fun matches(node: AccessibilityNodeInfo, target: String, by: String): Boolean =
        when (by) {
            "id" -> node.viewIdResourceName?.let {
                it == target || it.endsWith("/$target") || it.contains(target)
            } == true
            "desc" -> node.contentDescription?.toString()?.contains(target, true) == true
            else -> node.text?.toString()?.contains(target, true) == true ||
                node.contentDescription?.toString()?.contains(target, true) == true
        }

    private fun exact(node: AccessibilityNodeInfo, target: String, by: String): Boolean =
        when (by) {
            "id" -> node.viewIdResourceName == target || node.viewIdResourceName?.endsWith("/$target") == true
            "desc" -> node.contentDescription?.toString()?.trim() == target
            else -> node.text?.toString()?.trim() == target ||
                node.contentDescription?.toString()?.trim() == target
        }

    /** list_screen 与按 index 点击共用同一套过滤，保证序号一致。 */
    private fun visibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        walk(root, MAX_WALK) { node ->
            if (nodes.size >= MAX_NODES) return@walk
            if (!node.isVisibleToUser && !node.isEditable) return@walk
            val hasText = node.text?.toString()?.trim()?.isNotEmpty() == true ||
                node.contentDescription?.toString()?.trim()?.isNotEmpty() == true
            val keep = hasText || node.isEditable || node.isScrollable ||
                (node.isClickable && node.childCount == 0)
            if (!keep) return@walk
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return@walk
            nodes += node
        }
        return nodes
    }

    private fun editableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        var found: AccessibilityNodeInfo? = null
        walk(node, 40) { current ->
            if (found == null && current.isEditable) found = current
        }
        if (found != null) return found
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isEditable) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, MAX_WALK) { node ->
            if (found == null && node.isEditable && node.isVisibleToUser) found = node
        }
        return found
    }

    private fun firstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, MAX_WALK) { node ->
            if (found == null && node.isScrollable) found = node
        }
        return found
    }

    private suspend fun clickNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            current = current.parent
            depth++
        }
        val bounds = Rect()
        (current ?: node).getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return tap(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private suspend fun tap(
        service: AccessibilityService,
        x: Float,
        y: Float,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            mainHandler,
        )
        if (!dispatched && continuation.isActive) continuation.resume(false)
    }

    private suspend fun swipe(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            mainHandler,
        )
        if (!dispatched && continuation.isActive) continuation.resume(false)
    }

    private fun resolvePackage(context: Context, target: String): String? {
        val keyword = target.trim()
        if (keyword.isEmpty()) return null
        val packageManager = context.packageManager
        if (keyword.contains('.')) {
            val exact = runCatching { packageManager.getApplicationInfo(keyword, 0) }.getOrNull()
            if (exact != null) return exact.packageName
        }
        val packages = runCatching { packageManager.getInstalledPackages(0) }.getOrNull().orEmpty()
        val named = packages.firstOrNull { info ->
            val app = info.applicationInfo ?: return@firstOrNull false
            packageManager.getApplicationLabel(app).toString().contains(keyword, true)
        }
        if (named != null) return named.packageName
        return packages.firstOrNull { it.packageName.contains(keyword, true) }?.packageName
    }

    private fun labelOf(context: Context, packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val packageManager = context.packageManager
            val app = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(app).toString()
        }.getOrDefault(packageName)
    }
}
