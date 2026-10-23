package com.xdownloader.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor

/**
 * Android replacement for gallery-dl's image mode for X posts.
 * Two public endpoints are tried in order (X's syndication endpoint, then the fxtwitter JSON API).
 * Neither needs a login, so sensitive/age-restricted or protected posts may not be available.
 */
class XImages {

    data class ImageItem(val url: String, val username: String, val text: String, val statusId: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"

    suspend fun fetch(url: String): List<ImageItem> = withContext(Dispatchers.IO) {
        val id = Utils.extractStatusId(url) ?: error("Could not read a status id from that URL.")
        val user = Utils.extractUsername(url) ?: "i"
        val errors = mutableListOf<String>()

        try { return@withContext viaSyndication(id) } catch (e: Exception) { errors += "syndication: ${e.message}" }
        try { return@withContext viaFxTwitter(user, id) } catch (e: Exception) { errors += "fxtwitter: ${e.message}" }

        error("ERROR: could not get images.\n" + errors.joinToString("\n"))
    }

    /** Token expected by cdn.syndication.twimg.com: (id / 1e15 * PI) in base 36, without zeros and dot. */
    private fun syndicationToken(id: String): String {
        val x = id.toDouble() / 1e15 * PI
        var intPart = floor(x)
        var frac = x - intPart
        val sb = StringBuilder(java.lang.Long.toString(intPart.toLong(), 36))
        sb.append('.')
        repeat(12) {
            frac *= 36
            val d = floor(frac).toInt()
            frac -= d
            sb.append(Character.forDigit(d, 36))
        }
        return sb.toString().replace(Regex("(0+|\\.)"), "")
    }

    private fun original(url: String): String {
        val base = url.substringBefore('?').substringBeforeLast(':').let {
            // strip an old-style ":large" suffix only if it follows the file extension
            if (url.substringBefore('?').matches(Regex(".*\\.(jpg|jpeg|png|webp):\\w+"))) it else url.substringBefore('?')
        }
        return "$base?format=jpg&name=orig"
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${body.take(120).replace('\n', ' ')}")
            return body
        }
    }

    private fun viaSyndication(id: String): List<ImageItem> {
        val json = JSONObject(get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${syndicationToken(id)}"))
        val user = json.optJSONObject("user")?.optString("screen_name").orEmpty().ifEmpty { "x" }
        val text = json.optString("text")
        val photos = json.optJSONArray("photos") ?: error("no photos in this post")
        val list = mutableListOf<ImageItem>()
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).optString("url").takeIf { it.isNotEmpty() }
                ?.let { list += ImageItem(original(it), user, text, id) }
        }
        if (list.isEmpty()) error("no photos in this post")
        return list
    }

    private fun viaFxTwitter(user: String, id: String): List<ImageItem> {
        val tweet = JSONObject(get("https://api.fxtwitter.com/$user/status/$id")).optJSONObject("tweet")
            ?: error("no tweet in response")
        val author = tweet.optJSONObject("author")?.optString("screen_name").orEmpty().ifEmpty { user }
        val text = tweet.optString("text")
        val photos = tweet.optJSONObject("media")?.optJSONArray("photos") ?: error("no photos in this post")
        val list = mutableListOf<ImageItem>()
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).optString("url").takeIf { it.isNotEmpty() }
                ?.let { list += ImageItem(original(it), author, text, id) }
        }
        if (list.isEmpty()) error("no photos in this post")
        return list
    }

    suspend fun download(imageUrl: String, target: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(imageUrl).header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Image download failed (HTTP ${resp.code}).")
            resp.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}
