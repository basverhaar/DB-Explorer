package com.dbexplorer.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

data class RefreshState(val running: Boolean, val waitingForNetwork: Boolean, val message: String?)

object RefreshScheduler {
    private const val PERIODIC = "daily-refresh"
    private const val NOW = "refresh-now"

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /**
     * The server rebuilds the database once a day; checking twice a day means the
     * phone picks it up within hours. A check only downloads when there is a new build.
     */
    fun schedulePeriodic(context: Context, wifiOnly: Boolean, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun refreshNow(context: Context, wifiOnly: Boolean, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInputData(workDataOf(RefreshWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun state(context: Context): Flow<RefreshState> {
        val wm = WorkManager.getInstance(context)
        return combine(
            wm.getWorkInfosForUniqueWorkFlow(PERIODIC),
            wm.getWorkInfosForUniqueWorkFlow(NOW),
        ) { periodic, now ->
            val running = (periodic + now).firstOrNull { it.state == WorkInfo.State.RUNNING }
            RefreshState(
                running = running != null,
                waitingForNetwork = running == null && now.any { it.state == WorkInfo.State.ENQUEUED },
                message = running?.progress?.getString(RefreshWorker.KEY_PROGRESS),
            )
        }
    }
}
