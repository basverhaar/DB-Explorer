package com.dbexplorer.sources

import android.util.JsonToken
import com.dbexplorer.data.ExtraEntity
import com.dbexplorer.data.MediaType
import com.dbexplorer.data.Source
import com.dbexplorer.data.TitleEntity
import com.dbexplorer.data.genresField
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * Steam catalogue via SteamSpy (https://steamspy.com/api.php), which needs no API key.
 * Genre listings are fetched one by one (SteamSpy allows ~1 request/second) and merged,
 * so each game ends up with all the genres it appears under.
 */
class SteamSource(private val minReviews: Int) {
    private class App(
        val appId: String,
        val name: String,
        val developer: String?,
        val publisher: String?,
        val positive: Int,
        val negative: Int,
        val owners: String?,
        val priceCents: Int?,
        val genres: MutableSet<String> = linkedSetOf(),
    )

    suspend fun fetch(progress: suspend (String) -> Unit): List<TitleEntity> {
        val apps = HashMap<String, App>()
        progress("Steam: top games…")
        readSpy("$SPY?request=all&page=0", null, apps)
        GENRES.forEachIndexed { i, genre ->
            delay(1100)
            progress("Steam: $genre (${i + 1}/${GENRES.size})…")
            try {
                readSpy("$SPY?request=genre&genre=${URLEncoder.encode(genre, "UTF-8")}", genre, apps)
            } catch (e: Exception) {
                // One failing genre shouldn't drop the whole Steam refresh.
                if (apps.isEmpty() && i == GENRES.lastIndex) throw e
            }
        }
        if (apps.isEmpty()) error("SteamSpy returned no games")
        val now = System.currentTimeMillis()
        return apps.values.map { a ->
            val votes = a.positive + a.negative
            TitleEntity(
                id = "steam:${a.appId}",
                type = MediaType.GAME.name,
                source = Source.STEAM,
                externalId = a.appId,
                title = a.name,
                searchName = a.name.lowercase(),
                year = null,
                endYear = null,
                genres = genresField(a.genres),
                runtime = null,
                rating = if (votes > 0) a.positive * 10.0 / votes else null,
                votes = votes,
                metascore = null,
                developer = a.developer,
                publisher = a.publisher,
                platforms = "PC",
                price = a.priceCents?.let { it / 100.0 },
                owners = a.owners,
                imageUrl = "https://cdn.akamai.steamstatic.com/steam/apps/${a.appId}/header.jpg",
                updatedAt = now,
            )
        }
    }

    private fun readSpy(url: String, genre: String?, into: HashMap<String, App>) {
        Http.json(url) { r ->
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return@json }
            r.forEachKey { appKey ->
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return@forEachKey }
                val f = HashMap<String, String?>()
                r.forEachKey { k -> f[k] = r.nextScalar() }
                val name = f["name"]?.trim().orEmpty()
                val positive = f["positive"]?.toIntOrNull() ?: 0
                val negative = f["negative"]?.toIntOrNull() ?: 0
                if (name.isEmpty() || positive + negative < minReviews) return@forEachKey
                val appId = f["appid"] ?: appKey
                val app = into.getOrPut(appId) {
                    App(
                        appId = appId,
                        name = name,
                        developer = f["developer"]?.takeIf { it.isNotBlank() },
                        publisher = f["publisher"]?.takeIf { it.isNotBlank() },
                        positive = positive,
                        negative = negative,
                        owners = f["owners"]?.replace(" .. ", " – "),
                        priceCents = f["price"]?.toIntOrNull(),
                    )
                }
                if (genre != null) app.genres += GENRE_LABELS[genre] ?: genre
            }
        }
    }

    /** Store page details: release date, Metacritic score, description, platforms. */
    fun details(appId: String, existing: ExtraEntity?, id: String): ExtraEntity {
        var metascore: Int? = null
        var metacriticUrl: String? = null
        var date: String? = null
        var description: String? = null
        var genres = emptyList<String>()
        var categories = emptyList<String>()
        val platforms = mutableListOf<String>()
        var developers = emptyList<String>()
        var success = false
        Http.json("https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&l=en") { r ->
            r.forEachKey { _ ->
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return@forEachKey }
                r.forEachKey { k ->
                    when (k) {
                        "success" -> success = r.nextScalar() == "true"
                        "data" -> if (r.peek() != JsonToken.BEGIN_OBJECT) r.skipValue() else r.forEachKey { d ->
                            when (d) {
                                "short_description" -> description = r.nextScalar()
                                "release_date" -> if (r.peek() == JsonToken.BEGIN_OBJECT) r.forEachKey { rd ->
                                    if (rd == "date") date = r.nextScalar() else r.skipValue()
                                } else r.skipValue()
                                "metacritic" -> if (r.peek() == JsonToken.BEGIN_OBJECT) r.forEachKey { m ->
                                    when (m) {
                                        "score" -> metascore = r.nextScalar()?.toIntOrNull()
                                        "url" -> metacriticUrl = r.nextScalar()
                                        else -> r.skipValue()
                                    }
                                } else r.skipValue()
                                "genres" -> genres = r.namesOf("description")
                                "categories" -> categories = r.namesOf("description")
                                "developers" -> {
                                    val list = mutableListOf<String>()
                                    r.forEachElement { r.nextScalar()?.let(list::add) }
                                    developers = list
                                }
                                "platforms" -> if (r.peek() == JsonToken.BEGIN_OBJECT) r.forEachKey { p ->
                                    if (r.nextScalar() == "true") platforms += p.replaceFirstChar { it.uppercase() }
                                } else r.skipValue()
                                else -> r.skipValue()
                            }
                        }
                        else -> r.skipValue()
                    }
                }
            }
        }
        val now = System.currentTimeMillis()
        val base = existing ?: ExtraEntity(id = id)
        if (!success) return base.copy(enrichedAt = now)
        val info = buildList {
            date?.let { add("Released\t$it") }
            if (genres.isNotEmpty()) add("Steam genres\t${genres.joinToString(", ")}")
            if (platforms.isNotEmpty()) add("Platforms\t${platforms.joinToString(", ")}")
            if (developers.isNotEmpty()) add("Developers\t${developers.joinToString(", ")}")
            val tags = categories.filter { it in USEFUL_CATEGORIES }
            if (tags.isNotEmpty()) add("Features\t${tags.joinToString(", ")}")
        }.joinToString("\n")
        return base.copy(
            metascore = metascore ?: base.metascore,
            metacriticUrl = metacriticUrl ?: base.metacriticUrl,
            year = yearOf(date) ?: base.year,
            plot = description?.let(::stripHtml) ?: base.plot,
            info = info.ifBlank { base.info },
            enrichedAt = now,
        )
    }

    companion object {
        private const val SPY = "https://steamspy.com/api.php"
        private val GENRES = listOf(
            "Action", "Adventure", "RPG", "Strategy", "Simulation", "Indie", "Casual",
            "Sports", "Racing", "Massively Multiplayer", "Free to Play", "Early Access",
        )
        private val GENRE_LABELS = mapOf("Massively Multiplayer" to "MMO")
        private val USEFUL_CATEGORIES = setOf(
            "Single-player", "Multi-player", "Co-op", "Online Co-op", "PvP", "Online PvP",
            "Full controller support", "Steam Deck Verified", "VR Support", "Cross-Platform Multiplayer",
        )

        fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").trim()
    }
}
