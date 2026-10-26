package com.poi.homelab.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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

private val Context.chatDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "homelab_chat",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val chatJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 保存 AI 对话记录，退出应用后仍能看到历史对话。 */
class ChatStore(private val context: Context) {

    private val key = stringPreferencesKey("messages")

    val messages: Flow<List<ChatMessage>> = context.chatDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs[key]?.let { decode(it) }.orEmpty() }

    suspend fun load(): List<ChatMessage> = messages.first()

    suspend fun save(messages: List<ChatMessage>) {
        val trimmed = messages.takeLast(MAX_MESSAGES)
        context.chatDataStore.edit { prefs ->
            prefs[key] = chatJson.encodeToString(trimmed)
        }
    }

    suspend fun clear() {
        context.chatDataStore.edit { prefs -> prefs.remove(key) }
    }

    private fun decode(raw: String): List<ChatMessage> =
        runCatching { chatJson.decodeFromString<List<ChatMessage>>(raw) }
            .getOrDefault(emptyList())

    private companion object {
        const val MAX_MESSAGES = 200
    }
}
