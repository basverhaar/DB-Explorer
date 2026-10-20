package com.dbexplorer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val imdbEnabled: Boolean = true,
    val steamEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val imdbMinVotes: Int = 1000,
    val steamMinReviews: Int = 200,
    val omdbKey: String = "",
    val rawgKey: String = "",
)

data class SourceStatus(
    val source: String,
    val lastSuccess: Long,
    val lastRun: Long,
    val message: String,
    val ok: Boolean,
)

class SettingsStore(private val context: Context) {
    private object K {
        val imdb = booleanPreferencesKey("imdb_enabled")
        val steam = booleanPreferencesKey("steam_enabled")
        val wifi = booleanPreferencesKey("wifi_only")
        val imdbVotes = intPreferencesKey("imdb_min_votes")
        val steamReviews = intPreferencesKey("steam_min_reviews")
        val omdb = stringPreferencesKey("omdb_key")
        val rawg = stringPreferencesKey("rawg_key")
        fun success(s: String) = longPreferencesKey("status_${s}_success")
        fun run(s: String) = longPreferencesKey("status_${s}_run")
        fun msg(s: String) = stringPreferencesKey("status_${s}_msg")
        fun ok(s: String) = booleanPreferencesKey("status_${s}_ok")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            imdbEnabled = p[K.imdb] ?: d.imdbEnabled,
            steamEnabled = p[K.steam] ?: d.steamEnabled,
            wifiOnly = p[K.wifi] ?: d.wifiOnly,
            imdbMinVotes = p[K.imdbVotes] ?: d.imdbMinVotes,
            steamMinReviews = p[K.steamReviews] ?: d.steamMinReviews,
            omdbKey = p[K.omdb] ?: d.omdbKey,
            rawgKey = p[K.rawg] ?: d.rawgKey,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[K.imdb] = next.imdbEnabled
            p[K.steam] = next.steamEnabled
            p[K.wifi] = next.wifiOnly
            p[K.imdbVotes] = next.imdbMinVotes
            p[K.steamReviews] = next.steamMinReviews
            p[K.omdb] = next.omdbKey.trim()
            p[K.rawg] = next.rawgKey.trim()
        }
    }

    val statuses: Flow<List<SourceStatus>> = context.dataStore.data.map { p ->
        ALL_SOURCES.map { s -> readStatus(p, s) }
    }

    suspend fun status(source: String): SourceStatus = readStatus(context.dataStore.data.first(), source)

    private fun readStatus(p: Preferences, s: String) = SourceStatus(
        source = s,
        lastSuccess = p[K.success(s)] ?: 0L,
        lastRun = p[K.run(s)] ?: 0L,
        message = p[K.msg(s)] ?: "Never refreshed",
        ok = p[K.ok(s)] ?: true,
    )

    suspend fun recordStatus(source: String, ok: Boolean, message: String) {
        val now = System.currentTimeMillis()
        context.dataStore.edit { p ->
            p[K.run(source)] = now
            if (ok) p[K.success(source)] = now
            p[K.ok(source)] = ok
            p[K.msg(source)] = message
        }
    }

    suspend fun clearStatuses() {
        context.dataStore.edit { p ->
            ALL_SOURCES.forEach { s ->
                p.remove(K.success(s)); p.remove(K.run(s)); p.remove(K.msg(s)); p.remove(K.ok(s))
            }
        }
    }

    companion object {
        val ALL_SOURCES = listOf(Source.IMDB, Source.STEAM, Source.STEAM_DETAILS, Source.RAWG, Source.OMDB)
    }
}
