package com.xdownloader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Fmt(val label: String) {
        MP4("MP4 (Video)"), GIF("GIF (Animation)"), MP3("MP3 (Audio)"), IMAGE("Image (JPG)")
    }

    data class Quality(val label: String, val id: String)
    data class Msg(val title: String, val text: String)

    private val ctx = app
    val cookies = CookieStore(app)
    val history = DownloadHistory(app)
    private val images = XImages()

    // ---- UI state ----
    val url = MutableStateFlow("")
    val useCookies = MutableStateFlow(cookies.hasCookies())
    val cookieStatus = MutableStateFlow(cookies.statusText())
    val format = MutableStateFlow(Fmt.MP4)
    val qualities = MutableStateFlow(listOf(Quality("Best", "best")))
    val qualityIndex = MutableStateFlow(0)
    val qualityEnabled = MutableStateFlow(false)
    val logText = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val downloading = MutableStateFlow(false)
    val message = MutableStateFlow<Msg?>(null)
    val historyLabel = MutableStateFlow(history.label())
    val historyPicked = MutableStateFlow(history.isPicked())

    private var job: Job? = null
    @Volatile private var cancelled = false
    private val pid = "xdl"

    // ---- helpers ----
    private fun log(s: String) {
        logText.update { old ->
            val n = old + s + "\n"
            if (n.length > 80_000) n.takeLast(60_000) else n
        }
    }

    private fun show(title: String, text: String) { message.value = Msg(title, text) }
    fun dismissMessage() { message.value = null }

    fun setUrl(s: String) { url.value = s }
    fun setFormat(f: Fmt) { format.value = f }
    fun setUseCookies(b: Boolean) { useCookies.value = b }

    // ---- history file ----
    private fun refreshHistoryInfo() {
        historyLabel.value = history.label()
        historyPicked.value = history.isPicked()
    }

    /** "Pick existing history file": use a file chosen with the system file picker. */
    fun useHistoryFile(uri: Uri) {
        if (busy.value) { show("Please wait", "Finish or cancel the current operation first."); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { history.usePickedFile(uri) }
            refreshHistoryInfo()
            show(if (result.ok) "History file" else "Could not use that file", result.message)
        }
    }

    fun useDefaultHistory() {
        if (busy.value) { show("Please wait", "Finish or cancel the current operation first."); return }
        history.useDefault()
        refreshHistoryInfo()
        show("History file", "Using the app's own ${DownloadHistory.NAME} in Downloads/${MediaExporter.FOLDER} again.")
    }

    fun importCookies(uri: Uri) {
        try {
            cookieStatus.value = cookies.import(uri)
            useCookies.value = true
        } catch (e: Exception) {
            show("Cookies", e.message ?: "Could not import cookies.txt")
        }
    }

    fun removeCookies() {
        cookies.clear()
        useCookies.value = false
        cookieStatus.value = cookies.statusText()
    }

    private fun launchJob(block: suspend () -> Unit) {
        busy.value = true
        job = viewModelScope.launch {
            try {
                val err = App.ready.await()
                if (err != null) { show("Error", err); return@launch }
                block()
            } finally {
                busy.value = false
            }
        }
    }

    private fun cookiesOk(): Boolean {
        if (useCookies.value && !cookies.hasCookies()) {
            show("Cookies", "Import your cookies.txt first, or turn off 'Use cookies.txt'.")
            return false
        }
        return true
    }

    private suspend fun ytdlp(target: String, configure: YoutubeDLRequest.() -> Unit): String =
        withContext(Dispatchers.IO) {
            val req = YoutubeDLRequest(target)
            req.configure()
            if (useCookies.value && cookies.hasCookies()) {
                req.addOption("--cookies", cookies.file.absolutePath)
            }
            val response = YoutubeDL.getInstance().execute(req, pid) { progress, _, line ->
                // Skip per-percent spam, keep the meaningful lines.
                if (line.isNotBlank() && !(line.contains("ETA") && !line.contains("100%"))) log(line.trim())
            }
            response.out
        }

    // ---- fetch formats ----
    fun fetchFormats() {
        val u = url.value.trim()
        if (u.isEmpty()) { show("Input Required", "Please enter an X post URL."); return }
        if (busy.value || !cookiesOk()) return
        cancelled = false
        downloading.value = false
        logText.value = ""
        qualityEnabled.value = false
        qualities.value = listOf(Quality("Fetching...", "best"))
        qualityIndex.value = 0
        launchJob {
            try {
                val out = ytdlp(u) { addOption("--list-formats") }
                val parsed = parseFormats(out)
                if (parsed.isEmpty()) log("\nCould not parse specific formats, will use 'best'.")
                qualities.value = listOf(Quality("Best", "best")) + parsed
            } catch (e: Exception) {
                qualities.value = listOf(Quality("Best", "best"))
                log("ERROR: ${e.message}")
                show("Error", "Failed to fetch formats. See log for details.")
            } finally {
                qualityIndex.value = 0
                qualityEnabled.value = true
            }
        }
    }

    private fun parseFormats(output: String): List<Quality> {
        val result = mutableListOf<Quality>()
        var inSection = false
        for (line in output.lines()) {
            if ("ID" in line && "EXT" in line && "RESOLUTION" in line) { inSection = true; continue }
            if (inSection && "---" !in line && line.isNotBlank()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val id = parts[0]
                val ext = parts[1]
                val res = parts.getOrElse(2) { "" }
                if (res == "audio") continue
                val note = parts.drop(3).joinToString(" ")
                val text = if (res.isNotEmpty()) "$id: $ext @ $res ($note)" else "$id: $ext ($note)"
                result += Quality(text, id)
            }
        }
        return result
    }

    // ---- download / cancel ----
    fun onDownloadClicked() {
        if (downloading.value) cancel() else startDownload()
    }

    private fun cancel() {
        cancelled = true
        log("\n--- Cancelling operation... Please wait ---")
        try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Exception) {}
        job?.cancel()
    }

    private fun startDownload() {
        val u = url.value.trim()
        if (u.isEmpty()) { show("Input Required", "Please enter a URL."); return }
        if (busy.value || !cookiesOk()) return

        val fmt = format.value
        val ident = when (fmt) {
            Fmt.MP4 -> qualities.value.getOrNull(qualityIndex.value)?.id ?: "best"
            Fmt.GIF -> "GIF"
            Fmt.MP3 -> "MP3"
            Fmt.IMAGE -> "Image"
        }

        if (history.contains(u, ident)) {
            show("Already Downloaded", "This URL has already been downloaded with the selected quality/format ($ident).")
            return
        }

        cancelled = false
        downloading.value = true
        logText.value = ""
        launchJob { runDownload(u, fmt, ident) }
    }

    private suspend fun runDownload(u: String, fmt: Fmt, ident: String) {
        val work = File(ctx.cacheDir, "work_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val count = if (fmt == Fmt.IMAGE) downloadImages(u, work) else downloadMedia(u, fmt, ident, work)
            history.append(u, ident)
            show("Success", "All operations finished successfully! ($count file(s) saved to Downloads/${MediaExporter.FOLDER})")
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } catch (e: Exception) {
            if (cancelled) onCancelled()
            else {
                log("\nERROR: ${e.message}")
                show("Download Failed", "The process failed. Please check the log for details.")
            }
        } finally {
            work.deleteRecursively()
            downloading.value = false
        }
    }

    private fun onCancelled() {
        log("\n--- Operation Cancelled by User ---")
        show("Cancelled", "The operation was cancelled.")
    }

    private suspend fun downloadMedia(u: String, fmt: Fmt, ident: String, work: File): Int {
        log("--- Fetching post info to create filename... ---")
        val titles = ytdlp(u) { addOption("--get-title") }
            .lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("WARNING") && !it.startsWith("ERROR") }
        if (titles.isEmpty()) error("No available video tracks detected.")

        val username = Utils.extractUsername(u)
        var saved = 0

        titles.forEachIndexed { i, rawTitle ->
            currentCoroutineContext().ensureActive()
            val idx = i + 1
            val title = rawTitle
            val base = if (titles.size > 1) {
                log("\n--- Processing Media Track $idx of ${titles.size} ---")
                if (username != null) "$username - ${title}_$idx" else "${title}_$idx"
            } else {
                if (username != null) "$username - $title" else title
            }
            val name = Utils.sanitizeFilename(base)
            log("Sanitized Filename: $name\n")

            // '%' must be escaped inside yt-dlp output templates
            val template = File(work, name.replace("%", "%%")).absolutePath + ".%(ext)s"

            when (fmt) {
                Fmt.MP4 -> {
                    val sel = if (ident == "best") "bestvideo+bestaudio/best" else "$ident+bestaudio/$ident"
                    ytdlp(u) {
                        addOption("--playlist-items", idx.toString())
                        addOption("-o", template)
                        addOption("--format", sel)
                        addOption("--merge-output-format", "mp4")
                    }
                }
                Fmt.GIF -> {
                    log("--- Starting GIF Conversion (Track $idx) ---")
                    ytdlp(u) {
                        addOption("--playlist-items", idx.toString())
                        addOption("-o", template)
                        // video-only: a GIF can't contain an audio stream
                        addOption("--format", "bv/b")
                        addOption("--recode-video", "gif")
                        // Same filter chain as the desktop app's ffmpeg call (-an drops any audio)
                        addOption(
                            "--postprocessor-args",
                            "VideoConvertor:-an -vf fps=15,scale=640:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse -loop 0"
                        )
                    }
                    log("--- GIF Conversion Complete ---")
                }
                Fmt.MP3 -> {
                    log("--- Starting MP3 Conversion (Track $idx) ---")
                    ytdlp(u) {
                        addOption("--playlist-items", idx.toString())
                        addOption("-o", template)
                        addOption("--format", "bestaudio/best")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    }
                    log("--- MP3 Conversion Complete ---")
                }
                Fmt.IMAGE -> Unit
            }
            val finalExt = when (fmt) { Fmt.GIF -> "gif"; Fmt.MP3 -> "mp3"; else -> null }
            saved += exportOutputs(work, finalExt)
        }
        return saved
    }

    private suspend fun downloadImages(u: String, work: File): Int {
        log("--- Starting image/carousel download (handles multiple photos automatically) ---")
        val items = images.fetch(u)
        log("Found ${items.size} image(s)")
        var saved = 0
        items.forEachIndexed { i, item ->
            currentCoroutineContext().ensureActive()
            // "<username> - <post text[:50] or status id>_<num>.jpg"
            val label = item.text.take(50).ifBlank { item.statusId }
            val name = Utils.sanitizeFilename("${item.username} - $label", 80) + "_${i + 1}"
            val f = File(work, "$name.jpg")
            images.download(item.url, f)
            saved += exportOutputs(work)
        }
        return saved
    }

    /** Moves finished files from the work dir into Downloads/XDownloader. */
    private fun exportOutputs(work: File, onlyExt: String? = null): Int {
        var n = 0
        work.listFiles()?.filter { f ->
            f.isFile && listOf("part", "ytdl", "temp").none { f.name.endsWith(".$it") }
        }?.forEach { f ->
            if (onlyExt != null && !f.extension.equals(onlyExt, true)) {
                log("Skipping leftover file: ${f.name}")
                f.delete()
                return@forEach
            }
            // Never overwrite: Name, Name_1, Name_2 ...
            val finalName = MediaExporter.uniqueName(ctx, f.nameWithoutExtension, f.extension)
            if (MediaExporter.export(ctx, f, finalName)) {
                log("Saved: $finalName")
                n++
            } else {
                log("ERROR: could not save $finalName")
            }
            f.delete()
        }
        return n
    }

    // ---- yt-dlp update (the X extractor breaks often) ----
    fun updateYtDlp() {
        if (busy.value) return
        logText.value = "--- Updating yt-dlp... ---"
        launchJob {
            try {
                val status = withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(ctx) }
                log("Update result: $status")
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            }
        }
    }
}
