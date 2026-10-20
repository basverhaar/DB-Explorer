package com.dbexplorer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

class Repository(val db: AppDatabase, val settings: SettingsStore) {
    val dao = db.dao()

    fun pages(filters: Filters): Flow<PagingData<TitleRow>> =
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
            dao.page(QueryBuilder.page(filters))
        }.flow

    fun count(filters: Filters): Flow<Int> = dao.count(QueryBuilder.count(filters))

    fun facets(kind: String, type: MediaType) = dao.observeFacets(kind, type.name)

    suspend fun setPlayed(id: String, played: Boolean) {
        if (played) dao.markPlayed(PlayedEntity(id, System.currentTimeMillis())) else dao.unmarkPlayed(id)
    }
}
