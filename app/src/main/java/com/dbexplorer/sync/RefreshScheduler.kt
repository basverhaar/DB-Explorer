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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

data class RefreshState(val running: Boolean, val waitingForNetwork: Boolean, val message: String?)

object RefreshScheduler {
    private const val DAILY = "daily-refresh"
    private const val NOW = "refresh-now"

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /** Schedules the once-a-day refresh. [replace] re-applies changed constraints. */
    fun scheduleDaily(context: Context, wifiOnly: Boolean, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun refreshNow(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun state(context: Context): Flow<RefreshState> {
        val wm = WorkManager.getInstance(context)
        return combine(
            wm.getWorkInfosForUniqueWorkFlow(DAILY),
            wm.getWorkInfosForUniqueWorkFlow(NOW),
        ) { daily, now ->
            val all = daily + now
            val running = all.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val waitingNow = now.any { it.state == WorkInfo.State.ENQUEUED }
            RefreshState(
                running = running != null,
                waitingForNetwork = running == null && waitingNow,
                message = running?.progress?.getString(RefreshWorker.KEY_PROGRESS),
            )
        }
    }
}
