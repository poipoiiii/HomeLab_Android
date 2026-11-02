package com.poi.homelab.feature.motion

import android.content.Context

/** 视频服务自己的连接配置，避免和账本/修图共用一份设置时互相覆盖。 */
object MotionPrefs {
    private const val FILE = "homelab_motion"
    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty().trim()

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty().trim()

    fun save(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }
}
