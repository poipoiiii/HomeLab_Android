package com.poi.homelab.notify

import android.content.Context

/**
 * 采集模块自己的本地配置。
 *
 * 通知回调与广播接收器都在主线程触发，没法直接 await DataStore，所以这里用
 * SharedPreferences 存一份；服务器地址与上报凭证由 MainViewModel 在设置变化时
 * 从 AppSettings 的 billUrl / token 镜像过来，避免两处填写。
 */
object NotifyPrefs {
    private const val FILE = "homelab_capture"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "ingest_token"
    private const val KEY_SMS = "capture_sms"
    private const val KEY_NOTIFICATION = "capture_notification"
    private const val KEY_ALL_APPS = "capture_all_apps"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty().trim()

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty().trim()

    fun smsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SMS, true)

    fun notificationEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFICATION, true)

    fun allAppsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ALL_APPS, false)

    fun setSmsEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMS, value).apply()
    }

    fun setNotificationEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION, value).apply()
    }

    fun setAllAppsEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALL_APPS, value).apply()
    }

    /** 由设置页 / 启动时的 settings 流调用，只在值真正变化时落盘。 */
    fun sync(context: Context, serverUrl: String, token: String) {
        val current = prefs(context)
        val nextUrl = serverUrl.trim().trimEnd('/')
        val nextToken = token.trim()
        if (current.getString(KEY_SERVER, "") == nextUrl &&
            current.getString(KEY_TOKEN, "") == nextToken
        ) {
            return
        }
        current.edit()
            .putString(KEY_SERVER, nextUrl)
            .putString(KEY_TOKEN, nextToken)
            .apply()
    }
}
