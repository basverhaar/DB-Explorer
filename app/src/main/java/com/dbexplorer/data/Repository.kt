package com.dbexplorer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.dbexplorer.sources.OmdbSource
import com.dbexplorer.sources.SteamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class Repository(val db: AppDatabase, val settings: SettingsStore) {
    val dao = db.dao()

    fun pages(filters: Filters): Flow<PagingData<TitleRow>> =
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
            dao.page(QueryBuilder.page(filters))
        }.flow

    fun count(filters: Filters): Flow<Int> = dao.count(QueryBuilder.count(filters))

    fun genres(type: MediaType) = dao.observeGenres(type.name)

    suspend fun insertBatch(items: List<TitleEntity>) = db.withTransaction { dao.insertTitles(items) }

    suspend fun rebuildGenres() {
        MediaType.entries.forEach { type ->
            val counts = HashMap<String, Int>()
            dao.genreFields(type.name).forEach { field ->
                field.split('|').forEach { g -> if (g.isNotBlank()) counts[g] = (counts[g] ?: 0) + 1 }
            }
            dao.replaceGenres(type.name, counts.map { (name, n) -> GenreEntity(type.name, name, n) })
        }
    }

    /**
     * Fetches posters/plots/Metascores for one title if it has not been enriched recently.
     * Used when a detail screen opens.
     */
    suspend fun enrichIfNeeded(id: String) = withContext(Dispatchers.IO) {
        val title = dao.getTitle(id) ?: return@withContext
        val extra = dao.getExtra(id)
        if (extra != null && extra.enrichedAt > System.currentTimeMillis() - STALE_MS) return@withContext
        val s = settings.current()
        runCatching {
            when (title.source) {
                Source.STEAM -> dao.insertExtra(SteamSource(0).details(title.externalId, extra, id))
                Source.IMDB -> if (s.omdbKey.isNotBlank()) {
                    dao.insertExtra(OmdbSource(s.omdbKey).details(title.externalId, extra, id))
                }
                else -> Unit
            }
        }
        Unit
    }

    suspend fun clearAll() {
        db.withTransaction {
            dao.clearTitles()
            dao.clearExtras()
            dao.clearAllGenres()
        }
        settings.clearStatuses()
    }

    companion object {
        const val STALE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
