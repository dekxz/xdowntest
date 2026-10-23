package com.xdownloader.app

import java.util.UUID

object Utils {
    /** Same pattern as the desktop app's clipboard monitor. */
    val X_URL = Regex(
        """https?://(?:www\.|mobile\.)?(?:x\.com|twitter\.com)/[a-zA-Z0-9_]+/status/\d+(?:/photo/\d+)?"""
    )

    private val BAD_CHARS = Regex("""[\\/*?:"<>|]""")
    private val SPACES = Regex("""\s+""")

    fun findXUrl(text: String?): String? =
        text?.let { X_URL.find(it)?.value }

    fun sanitizeFilename(name: String?, maxLength: Int = 60): String {
        if (name.isNullOrEmpty()) return "download_${UUID.randomUUID().toString().take(8)}"
        var s = BAD_CHARS.replace(name, "")
        s = SPACES.replace(s, "_").trim('_')
        if (s.length > maxLength) s = s.substring(0, maxLength)
        return s.trim('_').ifEmpty { "download_${UUID.randomUUID().toString().take(8)}" }
    }

    fun extractUsername(url: String): String? =
        Regex("""https?://(?:www\.|mobile\.)?(?:x\.com|twitter\.com)/([a-zA-Z0-9_]+)/status/""")
            .find(url)?.groupValues?.get(1)

    fun extractStatusId(url: String): String? =
        Regex("""/status/(\d+)""").find(url)?.groupValues?.get(1)
}
