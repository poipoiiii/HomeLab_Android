package com.rong.homelab.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.homelabDataStore: DataStore<Preferences> by preferencesDataStore(name = "homelab_settings")

data class AppSettings(
    val labUrl: String = "",
    val billUrl: String = "",
    val token: String = "",
    val deviceName: String = Build.MODEL.orEmpty(),
    val themeMode: String = "system",
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val LAB_URL = stringPreferencesKey("lab_url")
        val BILL_URL = stringPreferencesKey("bill_url")
        val TOKEN = stringPreferencesKey("api_token")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<AppSettings> = context.homelabDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                labUrl = prefs[Keys.LAB_URL].orEmpty(),
                billUrl = prefs[Keys.BILL_URL].orEmpty(),
                token = prefs[Keys.TOKEN].orEmpty(),
                deviceName = prefs[Keys.DEVICE_NAME] ?: Build.MODEL.orEmpty(),
                themeMode = prefs[Keys.THEME_MODE] ?: "system",
            )
        }

    suspend fun save(value: AppSettings) {
        context.homelabDataStore.edit { prefs ->
            prefs[Keys.LAB_URL] = value.labUrl.trim()
            prefs[Keys.BILL_URL] = value.billUrl.trim()
            prefs[Keys.TOKEN] = value.token.trim()
            prefs[Keys.DEVICE_NAME] = value.deviceName.trim()
            prefs[Keys.THEME_MODE] = value.themeMode
        }
    }
}
