package com.poi.homelab.feature.motion

import android.content.Context
import com.poi.homelab.BuildConfig

/** 视频服务自己的连接配置，避免和账本/修图共用一份设置时互相覆盖。 */
object MotionPrefs {
    private const val FILE = "homelab_motion"
    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_URL_TOUCHED = "server_url_touched"
    private const val KEY_TOKEN_TOUCHED = "token_touched"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(context: Context): String {
        val prefs = prefs(context)
        return if (prefs.getBoolean(KEY_URL_TOUCHED, false)) {
            prefs.getString(KEY_URL, "").orEmpty().trim()
        } else {
            BuildConfig.DEFAULT_MOTION_URL
        }
    }

    fun token(context: Context): String {
        val prefs = prefs(context)
        return if (prefs.getBoolean(KEY_TOKEN_TOUCHED, false)) {
            prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        } else {
            BuildConfig.DEFAULT_MOTION_TOKEN
        }
    }

    fun save(context: Context, url: String, token: String) {
        val valueUrl = url.trim().trimEnd('/')
        val valueToken = token.trim()
        prefs(context).edit()
            .putString(KEY_URL, valueUrl)
            // 只有和构建期默认值不同才算用户改过，重新构建换默认值仍能生效
            .putBoolean(KEY_URL_TOUCHED, valueUrl != BuildConfig.DEFAULT_MOTION_URL)
            .putString(KEY_TOKEN, valueToken)
            .putBoolean(KEY_TOKEN_TOUCHED, valueToken != BuildConfig.DEFAULT_MOTION_TOKEN)
            .apply()
    }
}
