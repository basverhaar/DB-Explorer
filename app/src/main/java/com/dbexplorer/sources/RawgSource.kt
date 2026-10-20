package com.dbexplorer.sources

import android.util.JsonToken
import com.dbexplorer.data.MediaType
import com.dbexplorer.data.Source
import com.dbexplorer.data.TitleEntity
import com.dbexplorer.data.genresField
import kotlinx.coroutines.delay

/**
 * RAWG (https://rawg.io/apidocs) — free API key. Gives Metacritic scores and console
 * games that are not on Steam. Games ranked by Metascore are fetched page by page.
 */
class RawgSource(private val key: String) {
    class Game(
        val id: String,
        val slug: String,
        val name: String,
        val released: String?,
        val rating: Double?,
        val ratingsCount: Int,
        val metacritic: Int?,
        val genres: List<String>,
        val platforms: List<String>,
        val image: String?,
    )

    suspend fun fetch(pages: Int, progress: suspend (String) -> Unit): List<Game> {
        val out = mutableListOf<Game>()
        for (page in 1..pages) {
            progress("RAWG: page $page/$pages…")
            val (games, hasNext) = readPage(
                "https://api.rawg.io/api/games?key=$key&ordering=-metacritic&metacritic=1,100&page_size=40&page=$page"
            )
            out += games
            if (!hasNext) break
            delay(250)
        }
        return out
    }

    private fun readPage(url: String): Pair<List<Game>, Boolean> {
        val games = mutableListOf<Game>()
        var hasNext = false
        Http.json(url) { r ->
            r.forEachKey { k ->
                when (k) {
                    "next" -> hasNext = r.nextScalar() != null
                    "results" -> r.forEachElement {
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return@forEachElement }
                        val f = HashMap<String, String?>()
                        var genres = emptyList<String>()
                        var platforms = emptyList<String>()
                        r.forEachKey { g ->
                            when (g) {
                                "genres" -> genres = r.namesOf("name")
                                "platforms" -> platforms = r.namesOf("name", nestedIn = "platform")
                                else -> f[g] = r.nextScalar()
                            }
                        }
                        val name = f["name"]?.trim().orEmpty()
                        val id = f["id"] ?: return@forEachElement
                        if (name.isEmpty()) return@forEachElement
                        games += Game(
                            id = id,
                            slug = f["slug"] ?: id,
                            name = name,
                            released = f["released"],
                            rating = f["rating"]?.toDoubleOrNull()?.takeIf { it > 0 },
                            ratingsCount = f["ratings_count"]?.toIntOrNull() ?: 0,
                            metacritic = f["metacritic"]?.toIntOrNull(),
                            genres = genres,
                            platforms = platforms,
                            image = f["background_image"],
                        )
                    }
                    else -> r.skipValue()
                }
            }
        }
        return games to hasNext
    }

    companion object {
        fun toEntity(g: Game, now: Long) = TitleEntity(
            id = "rawg:${g.id}",
            type = MediaType.GAME.name,
            source = Source.RAWG,
            externalId = g.slug,
            title = g.name,
            searchName = g.name.lowercase(),
            year = yearOf(g.released),
            endYear = null,
            genres = genresField(g.genres.map { if (it == "Massively Multiplayer") "MMO" else it }),
            runtime = null,
            rating = g.rating?.let { it * 2 },
            votes = g.ratingsCount,
            metascore = g.metacritic,
            developer = null,
            publisher = null,
            platforms = g.platforms.joinToString(", ").ifBlank { null },
            price = null,
            owners = null,
            imageUrl = g.image,
            updatedAt = now,
        )
    }
}
