package com.poi.homelab.feature.imageedit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.imageEditDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "homelab_image_edit")

/**
 * 记录已经保存到相册的 job id，避免同一张结果重复写相册。
 * 单独一个 DataStore，不塞进设置，也不存图片内容与 Token。
 */
class ImageEditStore(private val context: Context) {

    private object Keys {
        val SAVED = stringSetPreferencesKey("saved_job_ids")
    }

    private companion object {
        const val MAX_SAVED = 300
    }

    private val flow = context.imageEditDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[Keys.SAVED].orEmpty() }

    suspend fun savedIds(): Set<String> = flow.first()

    suspend fun markSaved(jobId: String) {
        context.imageEditDataStore.edit { prefs ->
            val current = (prefs[Keys.SAVED].orEmpty() + jobId).toMutableList()
            // 只保留最近 300 个：Set 无序，直接按数量截断，避免无限增长
            val trimmed = if (current.size > MAX_SAVED) current.takeLast(MAX_SAVED).toSet() else current.toSet()
            prefs[Keys.SAVED] = trimmed
        }
    }

    suspend fun forget(jobId: String) {
        context.imageEditDataStore.edit { prefs ->
            prefs[Keys.SAVED] = prefs[Keys.SAVED].orEmpty() - jobId
        }
    }
}
