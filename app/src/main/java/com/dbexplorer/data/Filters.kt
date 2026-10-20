package com.dbexplorer.data

import androidx.sqlite.db.SimpleSQLiteQuery

enum class SortField(val label: String) {
    POPULARITY("Popularity"),
    RATING("User rating"),
    METASCORE("Metascore"),
    YEAR("Year"),
    TITLE("Title"),
}

enum class PlayedFilter { ANY, DONE, NOT_DONE }

data class Filters(
    val type: MediaType,
    val query: String = "",
    /** A title must have every selected genre. */
    val genres: Set<String> = emptySet(),
    /** A title must be on at least one selected platform. */
    val platforms: Set<String> = emptySet(),
    val played: PlayedFilter = PlayedFilter.ANY,
    val minYear: Int? = null,
    val maxYear: Int? = null,
    val minRating: Double = 0.0,
    val minMetascore: Int = 0,
    val minVotes: Int = 0,
    val maxPrice: Double? = null,
    val sort: SortField = SortField.POPULARITY,
    val descending: Boolean = true,
) {
    /** Number of active filters (not counting search text and sort). */
    val activeCount: Int
        get() = listOf(
            genres.isNotEmpty(),
            platforms.isNotEmpty(),
            played != PlayedFilter.ANY,
            minYear != null || maxYear != null,
            minRating > 0,
            minMetascore > 0,
            minVotes > 0,
            maxPrice != null,
        ).count { it }

    fun cleared() = Filters(type = type, query = query, sort = sort, descending = descending)
}

object QueryBuilder {
    private const val FROM = "FROM titles t LEFT JOIN played p ON p.id = t.id"

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun where(f: Filters): Pair<String, Array<Any>> {
        val clauses = mutableListOf("t.type = ?")
        val args = mutableListOf<Any>(f.type.name)
        val q = f.query.trim().lowercase()
        if (q.isNotEmpty()) {
            // Every word must appear somewhere in the title.
            q.split(Regex("\\s+")).forEach { word ->
                clauses += "t.searchName LIKE ? ESCAPE '\\'"
                args += "%" + escapeLike(word) + "%"
            }
        }
        f.genres.forEach { g ->
            clauses += "t.genres LIKE ? ESCAPE '\\'"
            args += "%|" + escapeLike(g) + "|%"
        }
        if (f.platforms.isNotEmpty()) {
            clauses += f.platforms.joinToString(" OR ", "(", ")") { "t.platforms LIKE ? ESCAPE '\\'" }
            f.platforms.forEach { args += "%|" + escapeLike(it) + "|%" }
        }
        when (f.played) {
            PlayedFilter.ANY -> Unit
            PlayedFilter.DONE -> clauses += "p.id IS NOT NULL"
            PlayedFilter.NOT_DONE -> clauses += "p.id IS NULL"
        }
        f.minYear?.let { clauses += "t.year >= ?"; args += it }
        f.maxYear?.let { clauses += "t.year <= ?"; args += it }
        if (f.minRating > 0) { clauses += "t.rating >= ?"; args += f.minRating }
        if (f.minMetascore > 0) { clauses += "t.metascore >= ?"; args += f.minMetascore }
        if (f.minVotes > 0) { clauses += "t.votes >= ?"; args += f.minVotes }
        f.maxPrice?.let { clauses += "t.price IS NOT NULL AND t.price <= ?"; args += it }
        return "WHERE " + clauses.joinToString(" AND ") to args.toTypedArray()
    }

    fun page(f: Filters): SimpleSQLiteQuery {
        val (w, args) = where(f)
        val dir = if (f.descending) "DESC" else "ASC"
        val order = when (f.sort) {
            SortField.POPULARITY -> "t.votes $dir"
            SortField.RATING -> "(t.rating IS NULL), t.rating $dir, t.votes DESC"
            SortField.METASCORE -> "(t.metascore IS NULL), t.metascore $dir, t.votes DESC"
            SortField.YEAR -> "(t.year IS NULL), t.year $dir, t.votes DESC"
            SortField.TITLE -> "t.searchName $dir"
        }
        return SimpleSQLiteQuery("SELECT t.*, (p.id IS NOT NULL) AS played $FROM $w ORDER BY $order", args)
    }

    /** Genre and platform fields of every matching title, for live per-option counts. */
    fun facetFields(f: Filters): SimpleSQLiteQuery {
        val (w, args) = where(f)
        return SimpleSQLiteQuery("SELECT t.genres, t.platforms $FROM $w", args)
    }

    fun count(f: Filters): SimpleSQLiteQuery {
        val (w, args) = where(f)
        return SimpleSQLiteQuery("SELECT COUNT(*) $FROM $w", args)
    }
}
