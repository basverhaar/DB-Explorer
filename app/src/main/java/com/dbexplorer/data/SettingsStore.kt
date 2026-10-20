package com.dbexplorer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val wifiOnly: Boolean = false,
    /** Public GitHub repository whose "data" release holds the catalogue. */
    val repo: String = DEFAULT_REPO,
) {
    companion object {
        const val DEFAULT_REPO = "basverhaar/DB-Explorer"
    }
}

/** What the phone knows about the catalogue it has installed. */
data class SyncStatus(
    val version: String = "",
    val builtAt: Long = 0,
    val lastCheck: Long = 0,
    val ok: Boolean = true,
    val message: String = "Not downloaded yet",
    /** Raw "sources" object from the server manifest. */
    val serverSources: String = "",
)

class SettingsStore(private val context: Context) {
    private object K {
        val wifi = booleanPreferencesKey("wifi_only")
        val repo = stringPreferencesKey("repo")
        val version = stringPreferencesKey("db_version")
        val builtAt = longPreferencesKey("db_built_at")
        val lastCheck = longPreferencesKey("last_check")
        val ok = booleanPreferencesKey("last_ok")
        val message = stringPreferencesKey("last_message")
        val sources = stringPreferencesKey("server_sources")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            wifiOnly = p[K.wifi] ?: d.wifiOnly,
            repo = p[K.repo] ?: d.repo,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[K.wifi] = next.wifiOnly
            p[K.repo] = next.repo.trim().ifEmpty { AppSettings.DEFAULT_REPO }
        }
    }

    val status: Flow<SyncStatus> = context.dataStore.data.map { p ->
        SyncStatus(
            version = p[K.version] ?: "",
            builtAt = p[K.builtAt] ?: 0,
            lastCheck = p[K.lastCheck] ?: 0,
            ok = p[K.ok] ?: true,
            message = p[K.message] ?: "Not downloaded yet",
            serverSources = p[K.sources] ?: "",
        )
    }

    suspend fun currentStatus(): SyncStatus = status.first()

    suspend fun recordCheck(ok: Boolean, message: String) {
        context.dataStore.edit { p ->
            p[K.lastCheck] = System.currentTimeMillis()
            p[K.ok] = ok
            p[K.message] = message
        }
    }

    suspend fun recordInstalled(version: String, builtAt: Long, sources: String) {
        context.dataStore.edit { p ->
            p[K.version] = version
            p[K.builtAt] = builtAt
            p[K.sources] = sources
        }
    }

    suspend fun clearInstalled() {
        context.dataStore.edit { p ->
            p.remove(K.version); p.remove(K.builtAt); p.remove(K.sources)
        }
    }
}
