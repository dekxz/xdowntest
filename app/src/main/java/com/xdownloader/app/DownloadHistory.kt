package com.xdownloader.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * xdownloader_history.log: ONE file in Downloads/XDownloader, updated after every download.
 * One "<url>|<format identifier>" line per completed download.
 *
 * Android sometimes renames a freshly created ".log" file to ".log.txt" (or adds " (1)"), so the
 * lookup matches any file whose name starts with the log's name instead of one exact name.
 * Any extra copies created by older versions are merged into the first one and removed.
 *
 * Note: Android only lets an app read files it created itself. After an uninstall, a fresh install
 * can't read the old log and starts a new one.
 */
class DownloadHistory(private val context: Context) {

    companion object {
        const val NAME = "xdownloader_history.log"
        private const val PREFIX = "xdownloader_history"
    }

    private val resolver get() = context.contentResolver

    /** All history files in our folder, oldest first. */
    private fun allLogs(): List<Uri> {
        val result = mutableListOf<Uri>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf("$PREFIX%", MediaExporter.RELATIVE_PATH),
            "${MediaStore.Downloads._ID} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                result += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }
        return result
    }

    fun uri(): Uri? = allLogs().firstOrNull()

    private fun readText(uri: Uri): String =
        try {
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        } catch (e: Exception) {
            ""
        }

    @Synchronized
    fun contains(url: String, identifier: String): Boolean {
        val entry = "$url|$identifier"
        return allLogs().any { uri -> readText(uri).lines().any { it.trim() == entry } }
    }

    @Synchronized
    fun append(url: String, identifier: String) {
        val logs = allLogs()
        val primary = logs.firstOrNull()

        if (primary == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                // generic type so Android keeps the ".log" extension
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${MediaExporter.FOLDER}")
            }
            val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(created)?.use { it.write("$url|$identifier\n".toByteArray(Charsets.UTF_8)) }
            return
        }

        // Merge extra copies (from older versions) into the first file, then delete them.
        val lines = LinkedHashSet<String>()
        readText(primary).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines += it }
        for (extra in logs.drop(1)) {
            readText(extra).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines += it }
        }
        lines += "$url|$identifier"

        // "wt" = truncate and rewrite the whole (small) file
        val ok = try {
            resolver.openOutputStream(primary, "wt")?.use {
                it.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
        if (ok) {
            for (extra in logs.drop(1)) {
                try { resolver.delete(extra, null, null) } catch (_: Exception) {}
            }
        }
    }
}
