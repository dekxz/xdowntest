package com.xdownloader.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Keeps a private copy of the user's Firefox-exported cookies.txt (Netscape format).
 * yt-dlp reads it directly (--cookies).
 */
class CookieStore(private val context: Context) {

    val file = File(context.filesDir, "cookies.txt")

    fun hasCookies(): Boolean = file.exists() && file.length() > 0

    data class Cookie(val domain: String, val name: String, val value: String)

    private fun isXDomain(domain: String): Boolean {
        val d = domain.trimStart('.').lowercase()
        return d == "x.com" || d.endsWith(".x.com") || d == "twitter.com" || d.endsWith(".twitter.com")
    }

    /** Returns a short status message for the UI. Throws on unreadable input. */
    fun import(uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open the selected file.")
        val normalized = text.replace("\r\n", "\n")
        val body = if (normalized.lines().firstOrNull { it.isNotBlank() }?.contains("Netscape", true) == true) {
            normalized
        } else {
            "# Netscape HTTP Cookie File\n$normalized"
        }
        file.writeText(body)
        if (parse().none { isXDomain(it.domain) }) {
            file.delete()
            error("No x.com / twitter.com cookies found in that file.")
        }
        return statusText()
    }

    fun clear() { file.delete() }

    fun statusText(): String {
        if (!hasCookies()) return "No cookies.txt imported"
        val x = parse().filter { isXDomain(it.domain) }
        val loggedIn = x.any { it.name == "auth_token" }
        return "cookies.txt loaded: ${x.size} X cookies" +
            if (loggedIn) "" else " (no auth_token – you may not be logged in)"
    }

    fun parse(): List<Cookie> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            if (line.startsWith("#HttpOnly_")) line = line.removePrefix("#HttpOnly_")
            else if (line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            Cookie(f[0], f[5], f[6])
        }
    }
}
