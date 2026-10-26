package com.poi.homelab.util

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    }
    if (mode == AppOpsManager.MODE_ALLOWED) return true

    // 部分厂商把 AppOps 状态置为 default，但 UsageStats 实际可用，做一次兜底探测。
    val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return false
    val end = System.currentTimeMillis()
    val start = end - 24 * 60 * 60 * 1000L
    return runCatching {
        usageStats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)?.isNotEmpty() == true
    }.getOrDefault(false)
}
