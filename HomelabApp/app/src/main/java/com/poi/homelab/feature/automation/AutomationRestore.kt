package com.poi.homelab.feature.automation

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * HyperOS/MIUI 会在重启时收回 adb 安装应用的无障碍开关。
 * 这里只做一件事：如果上一次服务确实连上过，开机后再把它写回系统设置。
 */
class AutomationRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                runCatching { AutomationRestore.restore(context) }
        }
    }
}

object AutomationRestore {

    private const val PREFS = "automation_state"
    private const val KEY_WAS_ENABLED = "was_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markConnected(context: Context) {
        prefs(context).edit().putBoolean(KEY_WAS_ENABLED, true).apply()
    }

    fun markDisconnected(context: Context) {
        prefs(context).edit().putBoolean(KEY_WAS_ENABLED, false).apply()
    }

    /** 用户上次确实开过，且已经通过 adb 授予 WRITE_SECURE_SETTINGS。 */
    fun canAutoRestore(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAS_ENABLED, false) && hasWritePermission(context)

    fun restore(context: Context): Boolean {
        if (!prefs(context).getBoolean(KEY_WAS_ENABLED, false)) return false
        if (!hasWritePermission(context)) return false

        val resolver = context.contentResolver
        val component = ComponentName(context, UiAutomationService::class.java).flattenToString()
        val current = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val entries = current.split(':')
            .filter { it.isNotBlank() }
            .toMutableList()
        if (entries.none { it.equals(component, true) }) {
            entries += component
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                entries.joinToString(":"),
            )
        }
        if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return true
    }

    private fun hasWritePermission(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
}
