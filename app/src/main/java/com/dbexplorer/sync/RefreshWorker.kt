package com.dbexplorer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dbexplorer.DbExplorerApp
import com.dbexplorer.data.ExtraEntity
import com.dbexplorer.data.Repository
import com.dbexplorer.data.Source
import com.dbexplorer.sources.ImdbSource
import com.dbexplorer.sources.OmdbSource
import com.dbexplorer.sources.RawgSource
import com.dbexplorer.sources.SteamSource
import com.dbexplorer.sources.normalizeName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Downloads every enabled source, then enriches the most popular titles. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repo: Repository = (context.applicationContext as DbExplorerApp).repository

    override suspend fun doWork(): Result {
        try {
            setForeground(foregroundInfo("Starting…"))
        } catch (_: Exception) {
            // Background start restrictions: carry on as a normal worker.
        }
        val s = repo.settings.current()
        var anyFailed = false

        suspend fun step(source: String, block: suspend () -> String) {
            try {
                val msg = block()
                repo.settings.recordStatus(source, true, msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                anyFailed = true
                repo.settings.recordStatus(source, false, "Failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        if (s.imdbEnabled) step(Source.IMDB) {
            val start = System.currentTimeMillis()
            val n = ImdbSource(s.imdbMinVotes).fetch(::progress) { repo.insertBatch(it) }
            repo.dao.pruneSource(Source.IMDB, start)
            "$n movies & shows"
        }

        if (s.steamEnabled) step(Source.STEAM) {
            val start = System.currentTimeMillis()
            val games = SteamSource(s.steamMinReviews).fetch(::progress)
            games.chunked(2000).forEach { repo.insertBatch(it) }
            repo.dao.pruneSource(Source.STEAM, start)
            "${games.size} games"
        }

        if (s.rawgKey.isNotBlank()) step(Source.RAWG) {
            val start = System.currentTimeMillis()
            val games = RawgSource(s.rawgKey).fetch(RAWG_PAGES, ::progress)
            // Attach Metascores to matching Steam games; keep the rest as their own rows.
            val steamByName = repo.dao.idTitles(Source.STEAM).associateBy { normalizeName(it.title) }
            var merged = 0
            val standalone = games.filter { g ->
                val match = steamByName[normalizeName(g.name)] ?: return@filter true
                val old = repo.dao.getExtra(match.id) ?: ExtraEntity(id = match.id)
                repo.dao.insertExtra(
                    old.copy(
                        metascore = old.metascore ?: g.metacritic,
                        year = old.year ?: com.dbexplorer.sources.yearOf(g.released),
                    )
                )
                merged++
                false
            }
            standalone.map { RawgSource.toEntity(it, start) }.chunked(1000).forEach { repo.insertBatch(it) }
            repo.dao.pruneSource(Source.RAWG, start)
            "${games.size} games ($merged matched Steam)"
        }

        if (s.steamEnabled) step(Source.STEAM_DETAILS) {
            val ids = repo.dao.enrichCandidates(Source.STEAM, System.currentTimeMillis() - Repository.STALE_MS, STEAM_DETAILS)
            val steam = SteamSource(0)
            var done = 0
            for (id in ids) {
                if (isStopped) break
                progress("Steam details ${done + 1}/${ids.size}…")
                runCatching {
                    val appId = id.removePrefix("steam:")
                    repo.dao.insertExtra(steam.details(appId, repo.dao.getExtra(id), id))
                    done++
                }
                delay(1600) // Store API allows roughly 200 requests per 5 minutes.
            }
            "$done games"
        }

        if (s.omdbKey.isNotBlank() && s.imdbEnabled) step(Source.OMDB) {
            val ids = repo.dao.enrichCandidates(Source.IMDB, System.currentTimeMillis() - Repository.STALE_MS, OMDB_PER_RUN)
            val omdb = OmdbSource(s.omdbKey)
            var done = 0
            for (id in ids) {
                if (isStopped) break
                progress("Posters & Metascores ${done + 1}/${ids.size}…")
                try {
                    repo.dao.insertExtra(omdb.details(id.removePrefix("imdb:"), repo.dao.getExtra(id), id))
                    done++
                } catch (e: OmdbSource.LimitReached) {
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (done == 0) throw e
                }
            }
            "$done titles enriched"
        }

        progress("Indexing genres…")
        repo.rebuildGenres()
        return if (anyFailed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    private suspend fun progress(message: String) {
        setProgress(workDataOf(KEY_PROGRESS to message))
        try {
            setForeground(foregroundInfo(message))
        } catch (_: Exception) {
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Refreshing databases…")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Database refresh", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Refreshing databases")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL = "refresh"
        private const val NOTIFICATION_ID = 1001
        private const val RAWG_PAGES = 25
        private const val STEAM_DETAILS = 150
        private const val OMDB_PER_RUN = 900
    }
}
