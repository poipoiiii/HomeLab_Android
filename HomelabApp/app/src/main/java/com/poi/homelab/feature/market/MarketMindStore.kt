package com.poi.homelab.feature.market

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.marketDataStore: DataStore<Preferences> by preferencesDataStore(name = "homelab_market")

/**
 * 行情页的本地记忆：消息评分权重、评价记录、消息缓存、财报缓存。
 * 只存手机本地，不上传。
 */
class MarketMindStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val WEIGHTS = stringPreferencesKey("news_weights")
        val FEEDBACK = stringPreferencesKey("news_feedback")
        val USEFUL_TOTAL = intPreferencesKey("news_useful_total")
        val USELESS_TOTAL = intPreferencesKey("news_useless_total")
        val LAST_CALIBRATED = stringPreferencesKey("news_last_calibrated")
        val NEWS_CACHE = stringPreferencesKey("news_cache")
        val EARNINGS_DATE = stringPreferencesKey("earnings_cache_date")
        val EARNINGS = stringPreferencesKey("earnings_cache")
    }

    suspend fun load(): MarketMind {
        val prefs = context.marketDataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()
        return MarketMind(
            weights = prefs[Keys.WEIGHTS]
                ?.let { runCatching { json.decodeFromString<NewsWeights>(it) }.getOrNull() }
                ?: NewsWeights(),
            feedback = prefs[Keys.FEEDBACK]
                ?.let { runCatching { json.decodeFromString<List<NewsFeedback>>(it) }.getOrNull() }
                .orEmpty(),
            usefulTotal = prefs[Keys.USEFUL_TOTAL] ?: 0,
            uselessTotal = prefs[Keys.USELESS_TOTAL] ?: 0,
            lastCalibrated = prefs[Keys.LAST_CALIBRATED].orEmpty(),
            newsCache = prefs[Keys.NEWS_CACHE]
                ?.let { runCatching { json.decodeFromString<List<MarketNews>>(it) }.getOrNull() }
                .orEmpty(),
            earningsDate = prefs[Keys.EARNINGS_DATE].orEmpty(),
            earnings = prefs[Keys.EARNINGS]
                ?.let { runCatching { json.decodeFromString<List<MarketEvent>>(it) }.getOrNull() }
                .orEmpty(),
        )
    }

    suspend fun save(value: MarketMind) {
        context.marketDataStore.edit { prefs ->
            prefs[Keys.WEIGHTS] = json.encodeToString(value.weights)
            prefs[Keys.FEEDBACK] = json.encodeToString(value.feedback)
            prefs[Keys.USEFUL_TOTAL] = value.usefulTotal
            prefs[Keys.USELESS_TOTAL] = value.uselessTotal
            prefs[Keys.LAST_CALIBRATED] = value.lastCalibrated
            prefs[Keys.NEWS_CACHE] = json.encodeToString(value.newsCache)
            prefs[Keys.EARNINGS_DATE] = value.earningsDate
            prefs[Keys.EARNINGS] = json.encodeToString(value.earnings)
        }
    }

    /** 每天最多校准一次：把上一轮评价折进权重，样本标记为已校准、版本号 +1。 */
    suspend fun calibrateIfNeeded(today: String): MarketMind {
        val mind = load()
        val pending = mind.feedback.filter { !it.calibrated }
        if (mind.lastCalibrated == today || pending.isEmpty()) return mind
        val weights = MarketScorer.calibrate(mind.weights, pending)
        val updated = mind.copy(
            weights = weights,
            feedback = mind.feedback
                .map { if (it.calibrated) it else it.copy(calibrated = true) }
                .takeLast(200),
            usefulTotal = mind.usefulTotal + pending.count { it.useful },
            uselessTotal = mind.uselessTotal + pending.count { !it.useful },
            lastCalibrated = today,
        )
        save(updated)
        return updated
    }
}
