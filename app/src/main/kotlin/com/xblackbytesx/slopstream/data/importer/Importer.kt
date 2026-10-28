package com.xblackbytesx.slopstream.data.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.xblackbytesx.slopstream.youtube.ImportFormatException
import com.xblackbytesx.slopstream.youtube.SubscriptionImport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recognises an export by its bytes, not its name (files arrive as content:// URIs or LAN
 * uploads): a zip is NewPipe's "Export database", `{` is NewPipe's subscriptions JSON, anything
 * else is tried as Takeout's subscriptions.csv or a plain list of channel URLs.
 */
@Singleton
class Importer @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun parse(bytes: ByteArray): SubscriptionImport.ImportResult = withContext(Dispatchers.IO) {
        when {
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> fromNewPipeZip(bytes)
            else -> fromText(bytes.decodeToString())
        }
    }

    fun fromText(text: String): SubscriptionImport.ImportResult {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{")) return SubscriptionImport.fromNewPipeJson(trimmed)
        return runCatching { SubscriptionImport.fromTakeoutCsv(trimmed) }.getOrElse { urlList(trimmed) }
    }

    /** One channel URL, handle or id per line (the LAN page's text box). */
    private fun urlList(text: String): SubscriptionImport.ImportResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) throw ImportFormatException("Nothing to import")
        return SubscriptionImport.fromNewPipeRows(lines.map { Triple(SubscriptionImport.NEWPIPE_YOUTUBE_SERVICE_ID, it, "") })
    }

    private fun fromNewPipeZip(bytes: ByteArray): SubscriptionImport.ImportResult {
        val db = File(context.cacheDir, "import-newpipe.db")
        try {
            var found = false
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "newpipe.db") {
                        db.outputStream().use { zip.copyTo(it) }
                        found = true
                        break
                    }
                }
            }
            if (!found) throw ImportFormatException("This zip has no newpipe.db; use NewPipe's \"Export database\"")
            val rows = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                sql.rawQuery("SELECT service_id, url, name FROM subscriptions", null).use { c ->
                    buildList { while (c.moveToNext()) add(Triple(c.getInt(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) }
                }
            }
            return SubscriptionImport.fromNewPipeRows(rows)
        } finally {
            db.delete()
            File(db.path + "-journal").delete()
        }
    }
}
