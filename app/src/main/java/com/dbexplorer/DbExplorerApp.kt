package com.dbexplorer

import android.app.Application
import com.dbexplorer.data.AppDatabase
import com.dbexplorer.data.Repository
import com.dbexplorer.data.SettingsStore
import com.dbexplorer.sync.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DbExplorerApp : Application() {
    lateinit var repository: Repository
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        repository = Repository(AppDatabase.create(this), SettingsStore(this))
        appScope.launch {
            val s = repository.settings.current()
            RefreshScheduler.scheduleDaily(this@DbExplorerApp, s.wifiOnly)
            // First launch: start downloading straight away instead of waiting a day.
            val neverRan = repository.settings.statuses.first().all { it.lastRun == 0L }
            if (neverRan) RefreshScheduler.refreshNow(this@DbExplorerApp, s.wifiOnly)
        }
    }
}
