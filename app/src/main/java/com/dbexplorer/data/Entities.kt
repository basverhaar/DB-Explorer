package com.dbexplorer.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType(val label: String) {
    MOVIE("Movies"),
    TV("TV Shows"),
    GAME("Games"),
}

object Source {
    const val IMDB = "IMDB"
    const val STEAM = "STEAM"
    const val STEAM_DETAILS = "STEAM_DETAILS"
    const val RAWG = "RAWG"
    const val OMDB = "OMDB"
}

/**
 * Base row of the catalogue. Rewritten on every refresh, so anything fetched
 * per title (posters, plots, Metascores) lives in [ExtraEntity] instead.
 */
@Entity(
    tableName = "titles",
    indices = [
        Index("type"),
        Index("source"),
        Index("votes"),
        Index("rating"),
        Index("year"),
    ],
)
data class TitleEntity(
    @PrimaryKey val id: String,
    val type: String,
    val source: String,
    val externalId: String,
    val title: String,
    /** Lower-cased title used for searching. */
    val searchName: String,
    val year: Int?,
    val endYear: Int?,
    /** Genres wrapped in pipes, e.g. "|Action|Drama|", so a LIKE '%|Drama|%' matches exactly. */
    val genres: String,
    val runtime: Int?,
    /** User rating normalised to 0..10. */
    val rating: Double?,
    val votes: Int,
    val metascore: Int?,
    val developer: String?,
    val publisher: String?,
    val platforms: String?,
    val price: Double?,
    val owners: String?,
    val imageUrl: String?,
    val updatedAt: Long,
)

/** Per-title enrichment that survives catalogue refreshes. */
@Entity(tableName = "extras")
data class ExtraEntity(
    @PrimaryKey val id: String,
    val metascore: Int? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val plot: String? = null,
    /** "Label\tValue" lines shown on the detail screen. */
    val info: String? = null,
    val metacriticUrl: String? = null,
    val enrichedAt: Long = 0,
)

@Entity(tableName = "genres", primaryKeys = ["type", "name"])
data class GenreEntity(
    val type: String,
    val name: String,
    val count: Int,
)

/** A title joined with its extras, as shown in lists. */
data class TitleRow(
    @Embedded val t: TitleEntity,
    val metaEff: Int?,
    val yearEff: Int?,
    val imageEff: String?,
) {
    val genreList: List<String> get() = t.genres.split('|').filter { it.isNotBlank() }
}

fun genresField(list: Collection<String>): String =
    if (list.isEmpty()) "" else list.joinToString("|", prefix = "|", postfix = "|")
