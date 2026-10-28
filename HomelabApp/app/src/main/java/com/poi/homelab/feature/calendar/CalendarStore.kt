package com.poi.homelab.feature.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.calendarDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "homelab_calendar")

private val calendarJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 私人日历记录，只保存在本机。 */
class CalendarStore(private val context: Context) {

    private val key = stringPreferencesKey("entries")

    suspend fun load(): List<CalendarEntry> {
        val raw = context.calendarDataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .first()[key]
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            calendarJson.decodeFromString<List<CalendarEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    suspend fun save(entries: List<CalendarEntry>) {
        context.calendarDataStore.edit { prefs ->
            prefs[key] = calendarJson.encodeToString(entries.takeLast(MAX_ENTRIES))
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
