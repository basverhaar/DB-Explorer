package com.dbexplorer.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType(val label: String, val doneLabel: String) {
    MOVIE("Movies", "Watched"),
    TV("TV Shows", "Watched"),
    GAME("Games", "Played"),
}

object Source {
    const val IMDB = "IMDB"
    const val STEAM = "STEAM"
    const val RAWG = "RAWG"
    const val WIKIDATA = "WIKIDATA"
    const val IGDB = "IGDB"
}

/**
 * One title from the shared catalogue. The whole table is replaced with the
 * server-built database on every update; nothing in it is edited on the phone.
 */
@Entity(
    tableName = "titles",
    indices = [Index("type"), Index("votes"), Index("rating"), Index("year"), Index("metascore")],
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
    /** Wrapped in pipes, e.g. "|Action|Drama|", so LIKE '%|Drama|%' matches exactly. */
    val genres: String,
    /** Same format as [genres], e.g. "|PC|Nintendo Switch|". */
    val platforms: String,
    val runtime: Int?,
    /** User rating normalised to 0..10. */
    val rating: Double?,
    val votes: Int,
    val metascore: Int?,
    val developer: String?,
    val publisher: String?,
    val price: Double?,
    val owners: String?,
    val imageUrl: String?,
    val plot: String?,
    /** "Label\tValue" lines shown on the detail screen. */
    val info: String?,
    val url: String?,
)

/** Genres and platforms with their title counts, per media type. */
@Entity(tableName = "facets", primaryKeys = ["kind", "type", "name"])
data class FacetEntity(
    val kind: String,
    val type: String,
    val name: String,
    val count: Int,
)

/** Titles the user has played or watched. Local only; survives catalogue updates. */
@Entity(tableName = "played")
data class PlayedEntity(
    @PrimaryKey val id: String,
    val playedAt: Long,
)

/** A title plus whether the user has played/watched it, as shown in lists. */
data class TitleRow(
    @Embedded val t: TitleEntity,
    val played: Boolean,
) {
    val genreList: List<String> get() = split(t.genres)
    val platformList: List<String> get() = split(t.platforms)

    private fun split(s: String) = s.split('|').filter { it.isNotBlank() }
}

object FacetKind {
    const val GENRE = "genre"
    const val PLATFORM = "platform"
}
