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
import kotlinx.coroutines.CancellationException

/** Fetches the latest server-built catalogue if there is a newer one. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as DbExplorerApp

    override suspend fun doWork(): Result {
        val repo = app.repository
        val force = inputData.getBoolean(KEY_FORCE, false)
        // Piggy-back the app update check on the regular background check.
        app.updater.checkAndNotify()
        return try {
            val message = CatalogueSync(applicationContext, repo).sync(force, ::progress)
            repo.settings.recordCheck(true, message)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.settings.recordCheck(false, "Failed: ${e.message ?: e.javaClass.simpleName}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun progress(message: String) {
        setProgress(workDataOf(KEY_PROGRESS to message))
        if (message.startsWith("Downloading") || message.startsWith("Installing")) {
            try {
                setForeground(foregroundInfo(message))
            } catch (_: Exception) {
                // Background start restrictions: carry on without the notification.
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Updating database…")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Database updates", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Updating database")
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
        const val KEY_FORCE = "force"
        private const val CHANNEL = "refresh"
        private const val NOTIFICATION_ID = 1001
    }
}
