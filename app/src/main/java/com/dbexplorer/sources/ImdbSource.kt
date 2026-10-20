package com.dbexplorer.sources

import com.dbexplorer.data.MediaType
import com.dbexplorer.data.Source
import com.dbexplorer.data.TitleEntity
import com.dbexplorer.data.genresField
import java.util.zip.GZIPInputStream

/**
 * Streams IMDb's official non-commercial datasets (https://datasets.imdbws.com/),
 * which IMDb regenerates daily. Only movies and series above a vote threshold are kept
 * so the database stays phone-sized.
 */
class ImdbSource(private val minVotes: Int) {
    private class Rating(val rating: Float, val votes: Int)

    suspend fun fetch(
        progress: suspend (String) -> Unit,
        emit: suspend (List<TitleEntity>) -> Unit,
    ): Int {
        progress("IMDb: downloading ratings…")
        val ratings = HashMap<String, Rating>(200_000)
        Http.get("$BASE/title.ratings.tsv.gz").use { r ->
            GZIPInputStream(r.body!!.byteStream(), 1 shl 16).bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val a = line.split('\t')
                    if (a.size < 3) return@forEach
                    val votes = a[2].toIntOrNull() ?: return@forEach
                    if (votes >= minVotes) {
                        ratings[a[0]] = Rating(a[1].toFloatOrNull() ?: 0f, votes)
                    }
                }
            }
        }

        progress("IMDb: downloading titles (${ratings.size} candidates)…")
        val now = System.currentTimeMillis()
        var batch = ArrayList<TitleEntity>(BATCH)
        var total = 0
        var scanned = 0L
        Http.get("$BASE/title.basics.tsv.gz").use { r ->
            val reader = GZIPInputStream(r.body!!.byteStream(), 1 shl 16).bufferedReader()
            reader.readLine() // header
            while (true) {
                val line = reader.readLine() ?: break
                scanned++
                val tab = line.indexOf('\t')
                if (tab < 0) continue
                val rating = ratings[line.substring(0, tab)] ?: continue
                // tconst titleType primaryTitle originalTitle isAdult startYear endYear runtimeMinutes genres
                val a = line.split('\t')
                if (a.size < 9 || a[4] == "1") continue
                val type = when (a[1]) {
                    "movie", "tvMovie" -> MediaType.MOVIE
                    "tvSeries", "tvMiniSeries" -> MediaType.TV
                    else -> continue
                }
                val title = a[2]
                batch += TitleEntity(
                    id = "imdb:${a[0]}",
                    type = type.name,
                    source = Source.IMDB,
                    externalId = a[0],
                    title = title,
                    searchName = title.lowercase(),
                    year = a[5].toIntOrNull(),
                    endYear = a[6].toIntOrNull(),
                    genres = genresField(a[8].takeIf { it != "\\N" }?.split(',').orEmpty()),
                    runtime = a[7].toIntOrNull(),
                    rating = rating.rating.toDouble(),
                    votes = rating.votes,
                    metascore = null,
                    developer = null,
                    publisher = null,
                    platforms = null,
                    price = null,
                    owners = null,
                    imageUrl = null,
                    updatedAt = now,
                )
                if (batch.size >= BATCH) {
                    emit(batch)
                    total += batch.size
                    batch = ArrayList(BATCH)
                    progress("IMDb: $total titles imported…")
                }
            }
        }
        if (batch.isNotEmpty()) {
            emit(batch)
            total += batch.size
        }
        if (scanned < 1000) error("IMDb title file looked truncated ($scanned lines)")
        return total
    }

    companion object {
        private const val BASE = "https://datasets.imdbws.com"
        private const val BATCH = 2000
    }
}
