package com.dbexplorer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class Repository(val db: AppDatabase, val settings: SettingsStore) {
    val dao = db.dao()

    fun pages(filters: Filters): Flow<PagingData<TitleRow>> =
        // Rows are read from the database 30 at a time as the list scrolls.
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 15, initialLoadSize = 30, enablePlaceholders = false)) {
            dao.page(QueryBuilder.page(filters))
        }.flow

    fun count(filters: Filters): Flow<Int> = dao.count(QueryBuilder.count(filters))

    fun facets(kind: String, type: MediaType) = dao.observeFacets(kind, type.name)

    /**
     * How many titles each genre/platform option would give with the current filters.
     * Genres combine with AND, so they are counted within the current results; platforms
     * combine with OR, so they are counted as if no platform were selected.
     */
    suspend fun facetCounts(filters: Filters): FacetCounts {
        val forGenres = dao.facetRows(QueryBuilder.facetFields(filters))
        val forPlatforms = if (filters.platforms.isEmpty()) forGenres
        else dao.facetRows(QueryBuilder.facetFields(filters.copy(platforms = emptySet())))
        return withContext(Dispatchers.Default) {
            FacetCounts(
                genres = tally(forGenres.map { it.genres }),
                platforms = tally(forPlatforms.map { it.platforms }),
            )
        }
    }

    private fun tally(fields: List<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        fields.forEach { field ->
            field.split('|').forEach { if (it.isNotEmpty()) counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts
    }

    suspend fun setPlayed(id: String, played: Boolean) {
        if (played) dao.markPlayed(PlayedEntity(id, System.currentTimeMillis())) else dao.unmarkPlayed(id)
    }
}

data class FacetCounts(val genres: Map<String, Int>, val platforms: Map<String, Int>)
