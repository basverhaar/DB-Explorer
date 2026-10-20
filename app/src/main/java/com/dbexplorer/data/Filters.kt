package com.dbexplorer.data

import androidx.sqlite.db.SimpleSQLiteQuery

enum class SortField(val label: String) {
    POPULARITY("Popularity"),
    RATING("User rating"),
    METASCORE("Metascore"),
    YEAR("Year"),
    TITLE("Title"),
}

data class Filters(
    val type: MediaType,
    val query: String = "",
    val genres: Set<String> = emptySet(),
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
            minYear != null || maxYear != null,
            minRating > 0,
            minMetascore > 0,
            minVotes > 0,
            maxPrice != null,
        ).count { it }

    fun cleared() = Filters(type = type, query = query, sort = sort, descending = descending)
}

object QueryBuilder {
    private const val META = "COALESCE(e.metascore, t.metascore)"
    private const val YEAR = "COALESCE(t.year, e.year)"
    private const val FROM = "FROM titles t LEFT JOIN extras e ON e.id = t.id"

    private fun where(f: Filters): Pair<String, Array<Any>> {
        val clauses = mutableListOf("t.type = ?")
        val args = mutableListOf<Any>(f.type.name)
        val q = f.query.trim().lowercase()
        if (q.isNotEmpty()) {
            // Every word must appear somewhere in the title.
            q.split(Regex("\\s+")).forEach { word ->
                clauses += "t.searchName LIKE ? ESCAPE '\\'"
                args += "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            }
        }
        f.genres.forEach { g ->
            clauses += "t.genres LIKE ?"
            args += "%|$g|%"
        }
        f.minYear?.let { clauses += "$YEAR >= ?"; args += it }
        f.maxYear?.let { clauses += "$YEAR <= ?"; args += it }
        if (f.minRating > 0) { clauses += "t.rating >= ?"; args += f.minRating }
        if (f.minMetascore > 0) { clauses += "$META >= ?"; args += f.minMetascore }
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
            SortField.METASCORE -> "($META IS NULL), $META $dir, t.votes DESC"
            SortField.YEAR -> "($YEAR IS NULL), $YEAR $dir, t.votes DESC"
            SortField.TITLE -> "t.searchName $dir"
        }
        val sql = """SELECT t.*, $META AS metaEff, $YEAR AS yearEff,
            COALESCE(e.posterUrl, t.imageUrl) AS imageEff $FROM $w ORDER BY $order"""
        return SimpleSQLiteQuery(sql, args)
    }

    fun count(f: Filters): SimpleSQLiteQuery {
        val (w, args) = where(f)
        return SimpleSQLiteQuery("SELECT COUNT(*) $FROM $w", args)
    }
}
