package com.poi.homelab.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.poi.homelab.BuildConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.homelabDataStore: DataStore<Preferences> by preferencesDataStore(name = "homelab_settings")

private val settingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// 不写死任何实例地址：仓库里保持为空，由用户在设置页填写后存本机 DataStore
const val DEFAULT_LAB_URL = ""

data class AppSettings(
    val labUrl: String = DEFAULT_LAB_URL,
    val billUrl: String = "",
    val homarrUrl: String = "",
    val homarrApiKey: String = "",
    val homarrBoard: String = "",
    val cloudreveUrl: String = "",
    val cloudreveUser: String = "",
    val cloudrevePassword: String = "",
    val cloudreveDir: String = "手机照片",
    val token: String = "",
    val deviceName: String = Build.MODEL.orEmpty(),
    val themeMode: String = "system",
    val aiProviders: List<AiProvider> = defaultAiProviders(),
    val aiSelectedId: String = "deepseek",
    val fileTreeUri: String = "",
    val showLedgerOnHome: Boolean = true,
    val showWallpaper: Boolean = true,
    val wallpaperOpacity: Float = 0.22f,
    val wallpaperDim: Float = 0.5f,
    val settingsDetailed: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val LAB_URL = stringPreferencesKey("lab_url")
        val BILL_URL = stringPreferencesKey("bill_url")
        val HOMARR_URL = stringPreferencesKey("homarr_url")
        val HOMARR_API_KEY = stringPreferencesKey("homarr_api_key")
        val HOMARR_BOARD = stringPreferencesKey("homarr_board")
        // 区分「从没配过」和「用户主动清空」：前者用 local.properties 注入的默认值，后者尊重空值
        val HOMARR_URL_TOUCHED = booleanPreferencesKey("homarr_url_touched")
        val CLOUDREVE_URL = stringPreferencesKey("cloudreve_url")
        val CLOUDREVE_USER = stringPreferencesKey("cloudreve_user")
        val CLOUDREVE_PASSWORD = stringPreferencesKey("cloudreve_password")
        val CLOUDREVE_DIR = stringPreferencesKey("cloudreve_dir")
        val TOKEN = stringPreferencesKey("api_token")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AI_PROVIDERS = stringPreferencesKey("ai_providers")
        val AI_SELECTED = stringPreferencesKey("ai_selected")
        val FILE_TREE_URI = stringPreferencesKey("file_tree_uri")
        val SHOW_LEDGER = booleanPreferencesKey("show_ledger_home")
        val SHOW_WALLPAPER = booleanPreferencesKey("show_wallpaper")
        val WALLPAPER_OPACITY = floatPreferencesKey("wallpaper_opacity")
        val WALLPAPER_DIM = floatPreferencesKey("wallpaper_dim")
        val SETTINGS_DETAILED = booleanPreferencesKey("settings_detailed")
    }

    val settings: Flow<AppSettings> = context.homelabDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            val providers = prefs[Keys.AI_PROVIDERS]
                ?.let { raw -> runCatching { settingsJson.decodeFromString<List<AiProvider>>(raw) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: defaultAiProviders()
            AppSettings(
                labUrl = prefs[Keys.LAB_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_LAB_URL,
                billUrl = prefs[Keys.BILL_URL].orEmpty(),
                homarrUrl = if (prefs[Keys.HOMARR_URL_TOUCHED] == true) {
                    prefs[Keys.HOMARR_URL].orEmpty()
                } else {
                    BuildConfig.DEFAULT_HOMARR_URL
                },
                homarrApiKey = prefs[Keys.HOMARR_API_KEY].orEmpty(),
                homarrBoard = prefs[Keys.HOMARR_BOARD].orEmpty(),
                cloudreveUrl = prefs[Keys.CLOUDREVE_URL].orEmpty(),
                cloudreveUser = prefs[Keys.CLOUDREVE_USER].orEmpty(),
                cloudrevePassword = prefs[Keys.CLOUDREVE_PASSWORD].orEmpty(),
                cloudreveDir = prefs[Keys.CLOUDREVE_DIR]?.takeIf { it.isNotBlank() } ?: "手机照片",
                token = prefs[Keys.TOKEN].orEmpty(),
                deviceName = prefs[Keys.DEVICE_NAME] ?: Build.MODEL.orEmpty(),
                themeMode = prefs[Keys.THEME_MODE] ?: "system",
                aiProviders = providers,
                aiSelectedId = prefs[Keys.AI_SELECTED]
                    ?.takeIf { id -> providers.any { it.id == id } }
                    ?: providers.first().id,
                fileTreeUri = prefs[Keys.FILE_TREE_URI].orEmpty(),
                showLedgerOnHome = prefs[Keys.SHOW_LEDGER] ?: true,
                showWallpaper = prefs[Keys.SHOW_WALLPAPER] ?: true,
                wallpaperOpacity = (prefs[Keys.WALLPAPER_OPACITY] ?: 0.22f).coerceIn(0.05f, 0.9f),
                wallpaperDim = (prefs[Keys.WALLPAPER_DIM] ?: 0.5f).coerceIn(0f, 0.8f),
                settingsDetailed = prefs[Keys.SETTINGS_DETAILED] ?: true,
            )
        }

    suspend fun save(value: AppSettings) {
        context.homelabDataStore.edit { prefs ->
            prefs[Keys.LAB_URL] = value.labUrl.trim()
            prefs[Keys.BILL_URL] = value.billUrl.trim()
            prefs[Keys.HOMARR_URL] = value.homarrUrl.trim()
            prefs[Keys.HOMARR_URL_TOUCHED] = true
            prefs[Keys.HOMARR_API_KEY] = value.homarrApiKey.trim()
            prefs[Keys.HOMARR_BOARD] = value.homarrBoard.trim()
            prefs[Keys.CLOUDREVE_URL] = value.cloudreveUrl.trim()
            prefs[Keys.CLOUDREVE_USER] = value.cloudreveUser.trim()
            prefs[Keys.CLOUDREVE_PASSWORD] = value.cloudrevePassword
            prefs[Keys.CLOUDREVE_DIR] = value.cloudreveDir.trim()
            prefs[Keys.TOKEN] = value.token.trim()
            prefs[Keys.DEVICE_NAME] = value.deviceName.trim()
            prefs[Keys.THEME_MODE] = value.themeMode
            prefs[Keys.AI_PROVIDERS] = settingsJson.encodeToString(value.aiProviders)
            prefs[Keys.AI_SELECTED] = value.aiSelectedId
            prefs[Keys.FILE_TREE_URI] = value.fileTreeUri
            prefs[Keys.SHOW_LEDGER] = value.showLedgerOnHome
            prefs[Keys.SHOW_WALLPAPER] = value.showWallpaper
            prefs[Keys.WALLPAPER_OPACITY] = value.wallpaperOpacity
            prefs[Keys.WALLPAPER_DIM] = value.wallpaperDim
            prefs[Keys.SETTINGS_DETAILED] = value.settingsDetailed
        }
    }
}
