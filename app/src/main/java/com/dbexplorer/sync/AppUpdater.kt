package com.dbexplorer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.dbexplorer.BuildConfig
import com.dbexplorer.MainActivity
import com.dbexplorer.data.AppSettings
import com.dbexplorer.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val percent: Int) : UpdateState
    /** Android must allow this app to install apps first; the user is sent to that setting. */
    data class NeedsPermission(val update: AppUpdate, val file: File) : UpdateState
    data class Failed(val update: AppUpdate, val message: String) : UpdateState
}

/**
 * Checks the "latest" GitHub release for a newer APK than the one installed, downloads it
 * and hands it to Android's package installer.
 */
class AppUpdater(private val context: Context, private val settings: SettingsStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Set when the user taps "Later", so the dialog does not reappear this session. */
    var dismissedVersion = 0

    val installedVersion: Int get() = BuildConfig.VERSION_CODE
    val installedName: String get() = BuildConfig.VERSION_NAME

    /** Returns the newer version, or null when up to date. Throws on network errors. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val repo = settings.current().repo.ifBlank { AppSettings.DEFAULT_REPO }
        val base = "https://github.com/$repo/releases/download/latest"
        val json = client.newCall(Request.Builder().url("$base/version.json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }
        val update = AppUpdate(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName"),
            notes = json.optString("notes"),
            apkUrl = "$base/" + json.optString("apk", "DB-Explorer.apk"),
        )
        if (update.versionCode > installedVersion) {
            if (_state.value is UpdateState.Idle) _state.value = UpdateState.Available(update)
            update
        } else {
            null
        }
    }

    /** Background check from the refresh worker: notifies once per new version. */
    suspend fun checkAndNotify() {
        val update = runCatching { check() }.getOrNull() ?: return
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getInt("notified", 0) >= update.versionCode) return
        prefs.edit().putInt("notified", update.versionCode).apply()
        notify(update)
    }

    fun dismiss() {
        (_state.value as? UpdateState.Available)?.let { dismissedVersion = it.update.versionCode }
        _state.value = UpdateState.Idle
    }

    suspend fun downloadAndInstall(update: AppUpdate) {
        _state.value = UpdateState.Downloading(update, 0)
        val file = try {
            download(update)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(update, e.message ?: "Download failed")
            return
        }
        install(update, file)
    }

    /** Called again after the user granted the install permission. */
    fun install(update: AppUpdate, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update, file)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // The installer takes over; if the user cancels, they can start again from the dialog.
        _state.value = UpdateState.Available(update)
    }

    private suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "DB-Explorer-${update.versionCode}.apk")
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = UpdateState.Downloading(update, percent)
                            }
                        }
                    }
                }
            }
        }
        file
    }

    private fun notify(update: AppUpdate) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DB Explorer update available")
            .setContentText(update.notes.ifBlank { "Version ${update.versionName}" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed; the dialog still appears on the next launch.
        }
    }

    companion object {
        private const val CHANNEL = "app-updates"
        private const val NOTIFICATION_ID = 2001
    }
}
