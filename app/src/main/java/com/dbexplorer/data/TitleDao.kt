package com.dbexplorer.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class IdTitle(val id: String, val title: String)
data class TypeCount(val type: String, val n: Int)

@Dao
interface TitleDao {
    @RawQuery(observedEntities = [TitleEntity::class, ExtraEntity::class])
    fun page(query: SupportSQLiteQuery): PagingSource<Int, TitleRow>

    @RawQuery(observedEntities = [TitleEntity::class, ExtraEntity::class])
    fun count(query: SupportSQLiteQuery): Flow<Int>

    @Query(
        """SELECT t.*, COALESCE(e.metascore, t.metascore) AS metaEff,
           COALESCE(t.year, e.year) AS yearEff, COALESCE(e.posterUrl, t.imageUrl) AS imageEff
           FROM titles t LEFT JOIN extras e ON e.id = t.id WHERE t.id = :id"""
    )
    fun observeRow(id: String): Flow<TitleRow?>

    @Query("SELECT * FROM extras WHERE id = :id")
    fun observeExtra(id: String): Flow<ExtraEntity?>

    @Query("SELECT * FROM extras WHERE id = :id")
    suspend fun getExtra(id: String): ExtraEntity?

    @Query("SELECT * FROM titles WHERE id = :id")
    suspend fun getTitle(id: String): TitleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTitles(items: List<TitleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtra(item: ExtraEntity)

    @Query("DELETE FROM titles WHERE source = :source AND updatedAt < :before")
    suspend fun pruneSource(source: String, before: Long): Int

    @Query("SELECT id, title FROM titles WHERE source = :source")
    suspend fun idTitles(source: String): List<IdTitle>

    @Query(
        """SELECT t.id FROM titles t LEFT JOIN extras e ON e.id = t.id
           WHERE t.source = :source AND (e.enrichedAt IS NULL OR e.enrichedAt < :staleBefore)
           ORDER BY t.votes DESC LIMIT :limit"""
    )
    suspend fun enrichCandidates(source: String, staleBefore: Long, limit: Int): List<String>

    @Query("SELECT genres FROM titles WHERE type = :type")
    suspend fun genreFields(type: String): List<String>

    @Query("DELETE FROM genres WHERE type = :type")
    suspend fun clearGenres(type: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGenres(items: List<GenreEntity>)

    @Transaction
    suspend fun replaceGenres(type: String, items: List<GenreEntity>) {
        clearGenres(type)
        insertGenres(items)
    }

    @Query("SELECT * FROM genres WHERE type = :type ORDER BY count DESC")
    fun observeGenres(type: String): Flow<List<GenreEntity>>

    @Query("SELECT type, COUNT(*) AS n FROM titles GROUP BY type")
    fun observeTypeCounts(): Flow<List<TypeCount>>

    @Query("DELETE FROM titles")
    suspend fun clearTitles()

    @Query("DELETE FROM extras")
    suspend fun clearExtras()

    @Query("DELETE FROM genres")
    suspend fun clearAllGenres()
}
