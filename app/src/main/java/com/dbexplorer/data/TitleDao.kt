package com.dbexplorer.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class TypeCount(val type: String, val n: Int)
data class FacetFields(val genres: String, val platforms: String)

@Dao
interface TitleDao {
    @RawQuery(observedEntities = [TitleEntity::class, PlayedEntity::class])
    fun page(query: SupportSQLiteQuery): PagingSource<Int, TitleRow>

    @RawQuery(observedEntities = [TitleEntity::class, PlayedEntity::class])
    fun count(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun facetRows(query: SupportSQLiteQuery): List<FacetFields>

    @Query(
        """SELECT t.*, (p.id IS NOT NULL) AS played
           FROM titles t LEFT JOIN played p ON p.id = t.id WHERE t.id = :id"""
    )
    fun observeRow(id: String): Flow<TitleRow?>

    @Query("SELECT * FROM facets WHERE kind = :kind AND type = :type ORDER BY count DESC")
    fun observeFacets(kind: String, type: String): Flow<List<FacetEntity>>

    @Query("SELECT type, COUNT(*) AS n FROM titles GROUP BY type")
    fun observeTypeCounts(): Flow<List<TypeCount>>

    @Query("SELECT COUNT(*) FROM played")
    fun observePlayedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markPlayed(item: PlayedEntity)

    @Query("DELETE FROM played WHERE id = :id")
    suspend fun unmarkPlayed(id: String)
}
