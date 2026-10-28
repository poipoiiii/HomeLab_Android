package com.poi.homelab.feature.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

private val Context.todoDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "homelab_todo")

@Serializable
data class TodoItem(val id: String, val label: String)

val defaultTodoItems = listOf(
    TodoItem("lab", "看一眼实验室状态"),
    TodoItem("download", "整理下载队列"),
    TodoItem("bill", "记一笔今天的支出"),
)

/** Todo 列表持久化；勾选状态仍由 ChecklistStore 按天保存。 */
class TodoStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("items")
    private val initializedKey = booleanPreferencesKey("initialized")

    val items: Flow<List<TodoItem>> = context.todoDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            if (prefs[initializedKey] != true) {
                return@map defaultTodoItems
            }
            prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<List<TodoItem>>(raw) }.getOrNull()
            } ?: emptyList()
        }

    suspend fun load(): List<TodoItem> = items.first()

    suspend fun save(items: List<TodoItem>) {
        context.todoDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(items)
            prefs[initializedKey] = true
        }
    }
}
