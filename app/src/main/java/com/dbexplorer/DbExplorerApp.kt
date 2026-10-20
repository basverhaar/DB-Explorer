package com.dbexplorer

import android.app.Application
import com.dbexplorer.data.AppDatabase
import com.dbexplorer.data.Repository
import com.dbexplorer.data.SettingsStore
import com.dbexplorer.sync.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            RefreshScheduler.schedulePeriodic(this@DbExplorerApp, s.wifiOnly)
            // Check on launch too when the last check is old (or there is no data yet).
            val status = repository.settings.currentStatus()
            if (status.version.isEmpty() || System.currentTimeMillis() - status.lastCheck > 6 * HOUR) {
                RefreshScheduler.refreshNow(this@DbExplorerApp, s.wifiOnly)
            }
        }
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
