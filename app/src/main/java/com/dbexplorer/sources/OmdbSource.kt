package com.dbexplorer.sources

import com.dbexplorer.data.ExtraEntity
import java.io.IOException

/**
 * OMDb (https://www.omdbapi.com) — free API key, 1,000 requests/day. Adds posters,
 * plots and the Metacritic Metascore for IMDb titles.
 */
class OmdbSource(private val key: String) {
    class LimitReached : IOException("OMDb daily request limit reached")

    fun details(imdbId: String, existing: ExtraEntity?, id: String): ExtraEntity {
        val f = HashMap<String, String?>()
        val ratings = mutableListOf<Pair<String, String>>()
        Http.json("https://www.omdbapi.com/?apikey=$key&i=$imdbId&plot=full") { r ->
            r.forEachKey { k ->
                if (k == "Ratings") {
                    r.forEachElement {
                        var src: String? = null
                        var value: String? = null
                        r.forEachKey { rk ->
                            when (rk) {
                                "Source" -> src = r.nextScalar()
                                "Value" -> value = r.nextScalar()
                                else -> r.skipValue()
                            }
                        }
                        if (src != null && value != null) ratings += src!! to value!!
                    }
                } else {
                    f[k] = r.nextScalar()
                }
            }
        }
        val now = System.currentTimeMillis()
        val base = existing ?: ExtraEntity(id = id)
        if (f["Response"] != "True") {
            val err = f["Error"].orEmpty()
            if (err.contains("limit", ignoreCase = true)) throw LimitReached()
            if (err.contains("Invalid API key", ignoreCase = true)) throw IOException("OMDb: invalid API key")
            return base.copy(enrichedAt = now)
        }
        fun v(k: String) = f[k]?.takeIf { it.isNotBlank() && it != "N/A" }
        val info = buildList {
            v("Rated")?.let { add("Rated\t$it") }
            v("Released")?.let { add("Released\t$it") }
            v("Director")?.let { add("Director\t$it") }
            v("Writer")?.let { add("Writer\t$it") }
            v("Actors")?.let { add("Cast\t$it") }
            ratings.firstOrNull { it.first == "Rotten Tomatoes" }?.let { add("Rotten Tomatoes\t${it.second}") }
            v("totalSeasons")?.let { add("Seasons\t$it") }
            v("Language")?.let { add("Language\t$it") }
            v("Country")?.let { add("Country\t$it") }
            v("Awards")?.let { add("Awards\t$it") }
            v("BoxOffice")?.let { add("Box office\t$it") }
        }.joinToString("\n")
        return base.copy(
            metascore = v("Metascore")?.toIntOrNull() ?: base.metascore,
            posterUrl = v("Poster") ?: base.posterUrl,
            plot = v("Plot") ?: base.plot,
            info = info.ifBlank { base.info },
            enrichedAt = now,
        )
    }
}
