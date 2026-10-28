package com.poi.homelab.feature.bills

import android.content.Context
import org.json.JSONObject

/**
 * 每个账户上次成功写入账本的最大日期。
 *
 * 截图导入前用它提示「上次同步到哪天」，避免重复截同一段账单。
 */
object BillImportHistory {
    private const val FILE = "bill_import_history"
    private const val KEY = "last_sync"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val date = obj.optString(key)
                    if (key.isNotBlank() && date.isNotBlank()) put(key, date)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun record(context: Context, dates: Map<String, String>): Map<String, String> {
        val merged = load(context).toMutableMap()
        dates.forEach { (account, date) ->
            val name = account.trim()
            val day = date.trim()
            if (name.isEmpty() || day.isEmpty()) return@forEach
            val old = merged[name]
            if (old.isNullOrBlank() || day > old) merged[name] = day
        }
        val obj = JSONObject()
        merged.forEach { (account, date) -> obj.put(account, date) }
        prefs(context).edit().putString(KEY, obj.toString()).apply()
        return merged
    }
}
