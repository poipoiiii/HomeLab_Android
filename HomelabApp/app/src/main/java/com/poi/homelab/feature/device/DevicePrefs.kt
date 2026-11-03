package com.poi.homelab.feature.device

import android.content.Context

/** 设备接入配置单独存一份，和账本/视频/修图的设置互不覆盖。 */
object DevicePrefs {
    private const val FILE = "homelab_device"
    private const val KEY_HOST = "broker_host"
    private const val KEY_PORT = "broker_port"
    private const val KEY_USER = "broker_user"
    private const val KEY_PASSWORD = "broker_password"
    private const val KEY_SITE = "site"
    private const val KEY_DEV = "dev"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): DeviceConfig {
        val prefs = prefs(context)
        return DeviceConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty().trim(),
            port = prefs.getInt(KEY_PORT, 1883).coerceIn(1, 65535),
            user = prefs.getString(KEY_USER, "app").orEmpty().ifBlank { "app" },
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            site = prefs.getString(KEY_SITE, "dorm").orEmpty().ifBlank { "dorm" },
            dev = prefs.getString(KEY_DEV, "rong").orEmpty().ifBlank { "rong" },
        )
    }

    fun save(context: Context, config: DeviceConfig) {
        prefs(context).edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port.coerceIn(1, 65535))
            .putString(KEY_USER, config.user.trim().ifBlank { "app" })
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_SITE, config.site.trim().ifBlank { "dorm" })
            .putString(KEY_DEV, config.dev.trim().ifBlank { "rong" })
            .apply()
    }
}
