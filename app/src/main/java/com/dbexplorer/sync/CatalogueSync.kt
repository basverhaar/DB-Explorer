package com.dbexplorer.sync

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dbexplorer.data.AppSettings
import com.dbexplorer.data.Repository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Downloads the catalogue that the "Build database" GitHub workflow publishes daily
 * and swaps it into the local database. Played/watched marks are kept.
 */
class CatalogueSync(private val context: Context, private val repo: Repository) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Returns a human-readable result; throws on failure. */
    suspend fun sync(force: Boolean, progress: suspend (String) -> Unit): String {
        val s = repo.settings.current()
        val base = "https://github.com/${s.repo.ifBlank { AppSettings.DEFAULT_REPO }}/releases/download/data"

        progress("Checking for a new database…")
        val manifest = JSONObject(get("$base/manifest.json").use { it.body!!.string() })
        val schema = manifest.optInt("schemaVersion")
        if (schema != SCHEMA_VERSION) {
            throw IOException("The server database needs a newer app version (schema $schema)")
        }
        val version = manifest.getString("version")
        val installed = repo.settings.currentStatus().version
        if (!force && version == installed) return "Up to date"

        val totalMb = manifest.optLong("dbBytes") / 1_000_000
        val tmp = File(context.cacheDir, "catalogue-download.db")
        try {
            get("$base/catalogue.db.gz").use { r ->
                GZIPInputStream(r.body!!.byteStream(), 1 shl 16).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var total = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total - lastReport > 2_000_000) {
                                lastReport = total
                                val mb = total / 1_000_000
                                progress("Downloading database… $mb" + (if (totalMb > 0) " / $totalMb MB" else " MB"))
                            }
                        }
                    }
                }
            }
            progress("Installing database…")
            install(tmp)
        } finally {
            tmp.delete()
        }
        repo.settings.recordInstalled(
            version = version,
            builtAt = manifest.optLong("builtAt"),
            sources = manifest.optJSONObject("sources")?.toString() ?: "",
        )
        val counts = manifest.optJSONObject("counts")
        val total = counts?.keys()?.asSequence()?.sumOf { counts.optInt(it) } ?: 0
        return "Installed $total titles"
    }

    /** Copies the downloaded tables into the app database in one transaction. */
    private suspend fun install(file: File) {
        val db = repo.db
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
            db.withTransaction {
                val dst = db.openHelper.writableDatabase
                copy(src, dst, "titles", TITLE_COLUMNS)
                copy(src, dst, "facets", "kind, type, name, count")
            }
        }
    }

    private fun copy(src: SQLiteDatabase, dst: SupportSQLiteDatabase, table: String, columns: String) {
        dst.execSQL("DELETE FROM $table")
        val n = columns.split(',').size
        val insert = dst.compileStatement(
            "INSERT INTO $table ($columns) VALUES (${List(n) { "?" }.joinToString(",")})"
        )
        src.rawQuery("SELECT $columns FROM $table", null).use { c ->
            while (c.moveToNext()) {
                insert.clearBindings()
                for (i in 0 until n) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> insert.bindNull(i + 1)
                        Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i + 1, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(i + 1, c.getDouble(i))
                        else -> insert.bindString(i + 1, c.getString(i))
                    }
                }
                insert.executeInsert()
            }
        }
        insert.close()
    }

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url).header("User-Agent", "DBExplorer-Android").build()
    ).execute().also {
        if (!it.isSuccessful) {
            val code = it.code
            it.close()
            throw IOException(
                if (code == 404) "No database published yet — run the \"Build database\" workflow"
                else "HTTP $code from GitHub"
            )
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val TITLE_COLUMNS =
            "id, type, source, externalId, title, searchName, year, endYear, genres, platforms, runtime, " +
                "rating, votes, metascore, developer, publisher, price, owners, imageUrl, plot, info, url"
    }
}
