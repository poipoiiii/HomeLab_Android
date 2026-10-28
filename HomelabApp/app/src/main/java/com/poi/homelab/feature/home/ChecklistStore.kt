package com.poi.homelab.feature.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.checklistDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "homelab_checklist")

/** 今日清单：只保存当天勾选状态，跨天自动清空。 */
class ChecklistStore(private val context: Context) {

    private val key = stringPreferencesKey("today")

    suspend fun load(): Set<String> {
        val raw = context.checklistDataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .first()[key]
            .orEmpty()
        val parts = raw.split('|')
        if (parts.size != 2 || parts[0] != today()) return emptySet()
        return parts[1].split(',').filter { it.isNotBlank() }.toSet()
    }

    suspend fun save(ids: Set<String>) {
        context.checklistDataStore.edit { prefs ->
            prefs[key] = today() + "|" + ids.sorted().joinToString(",")
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
}
