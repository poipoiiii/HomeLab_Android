package com.poi.homelab.feature.watch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.watchDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "homelab_watch")

/** 监控规则的本地存储，只存在本机。 */
class WatchStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val key = stringPreferencesKey("settings")

    val settings: Flow<WatchSettings> = context.watchDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            prefs[key]
                ?.let { raw -> runCatching { json.decodeFromString<WatchSettings>(raw) }.getOrNull() }
                ?: WatchSettings()
        }

    suspend fun load(): WatchSettings = settings.first()

    suspend fun save(value: WatchSettings) {
        context.watchDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(value)
        }
    }
}
