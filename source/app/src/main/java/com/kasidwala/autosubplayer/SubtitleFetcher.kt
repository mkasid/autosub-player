package com.kasidwala.autosubplayer

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Subtitle search + download with two sources:
 *  1. OpenSubtitles (api.opensubtitles.com): content-hash first, title fallback.
 *  2. SubSource (api.subsource.net): title search fallback, used when
 *     OpenSubtitles finds nothing or its downloads fail (e.g. their
 *     download service returning 503, seen 2026-10-21).
 */
class SubtitleFetcher(private val context: Context) {

    companion object {
        private const val TAG = "SubtitleFetcher"
        private const val BASE = "https://api.opensubtitles.com/api/v1"
        private const val SS_BASE = "https://api.subsource.net/api/v1"
        private const val UA = "AutoSubPlayer/1.0"
        private const val STREAM_UA = "ExoPlayerLib/2.19.1"
        private const val PREFS = "autosub"

        const val SOURCE_OS = "os"
        const val SOURCE_SS = "ss"

        /**
         * Series-level key: strips episode markers so "Show S01E01" and
         * "Show S01E02" share one remembered subtitle uploader.
         */
        fun seriesKeyFor(title: String): String {
            val cleaned = TitleCleaner.clean(title).query
            var s = cleaned
                .replace(Regex("""(?i)\bS\d{1,2}E\d{1,3}\b"""), " ")
                .replace(Regex("""(?i)\b\d{1,2}X\d{1,3}\b"""), " ")
                .replace(Regex("""(?i)\bEP?\d{1,3}\b"""), " ")
                .replace(Regex("""(?i)\bEpisode\s*\d{1,3}\b"""), " ")
            s = s.replace(Regex("""\s+"""), " ").trim()
            return s.lowercase().ifBlank { cleaned.lowercase() }
        }

        fun rememberedUploader(context: Context, seriesKey: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("subuploader_$seriesKey", null)

        fun rememberUploader(context: Context, seriesKey: String, uploader: String) {
            if (uploader.isBlank()) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("subuploader_$seriesKey", uploader).apply()
        }
    }

    data class Settings(
        val apiKey: String,
        val username: String,
        val password: String,
        val subSourceKey: String
    )

    /** One downloadable subtitle candidate for the on-screen picker. */
    data class SubtitleOption(
        val fileId: Long,
        val release: String,
        val info: String,
        val uploader: String,
        val exactMatch: Boolean,
        val source: String = SOURCE_OS
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    fun loadSettings(): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            p.getString("api_key", "") ?: "",
            p.getString("os_user", "") ?: "",
            p.getString("os_pass", "") ?: "",
            p.getString("ss_key", "") ?: ""
        )
    }

    private fun storedToken(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("os_token", null)

    private fun storeToken(token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("os_token", token).apply()
    }

    /**
     * Returns a local .srt File (best match), or null when nothing found /
     * not configured. Attempts alternate between sources — OpenSubtitles
     * first pick, SubSource first pick, then seconds — so a dead download
     * endpoint on one source cannot block the other.
     */
    fun fetch(videoUrl: String, titleHint: String): File? {
        val options = searchOptions(videoUrl, titleHint)
        val os = options.filter { it.source == SOURCE_OS }
        val ss = options.filter { it.source == SOURCE_SS }
        val attemptOrder = mutableListOf<SubtitleOption>()
        for (i in 0 until maxOf(os.size, ss.size)) {
            if (i < os.size) attemptOrder.add(os[i])
            if (i < ss.size) attemptOrder.add(ss[i])
        }
        for (opt in attemptOrder.take(6)) {
            val f = downloadOption(opt)
            if (f != null) return f
            Log.i(TAG, "auto-download failed for '${opt.release}' (${opt.source}), trying next")
        }
        return null
    }

    /**
     * Full candidate list for the on-screen picker.
     * OpenSubtitles first: content-hash (exact release match), title fallback.
     * SubSource candidates are appended when a SubSource key is configured.
     */
    fun searchOptions(videoUrl: String, titleHint: String): List<SubtitleOption> {
        val s = loadSettings()
        val cleaned = TitleCleaner.clean(titleHint.ifBlank { videoUrl })
        Log.i(TAG, "subtitle query: '${cleaned.query}'")
        val out = mutableListOf<SubtitleOption>()

        if (s.apiKey.isNotBlank()) {
            // 1) hash — identifies the exact release, like VLC does
            val hash = computeHash(videoUrl)
            if (hash != null) {
                Log.i(TAG, "hash $hash.first, searching exact matches")
                collectOptions(s, "$BASE/subtitles?moviehash=${hash.first}&languages=en", out, true)
            } else {
                Log.i(TAG, "hash unavailable (ranges unsupported or too small)")
            }

            // 2) title fallback when the hash found nothing
            if (out.isEmpty() && cleaned.query.isNotBlank()) {
                val url = "$BASE/subtitles?query=${URLEncoder.encode(cleaned.query, "UTF-8")}&languages=en"
                collectOptions(s, url, out, false)
            }
        } else {
            Log.w(TAG, "no OpenSubtitles API key configured")
        }
        val osCount = out.size

        // 3) SubSource fallback source (independent database)
        if (s.subSourceKey.isNotBlank() && cleaned.query.isNotBlank()) {
            collectSubSourceOptions(s, cleaned, out)
        }
        Log.i(TAG, "subtitle candidates: $osCount OpenSubtitles, ${out.size - osCount} SubSource")
        return preferRememberedUploader(out.take(23), titleHint)
    }

    /**
     * Stable reorder: candidates uploaded by the submitter the user last
     * chose for this show come first (original relative order kept).
     * Only explicit user picks are remembered — auto-picks never overwrite.
     */
    private fun preferRememberedUploader(
        options: List<SubtitleOption>,
        titleHint: String
    ): List<SubtitleOption> {
        val remembered = rememberedUploader(context, seriesKeyFor(titleHint))
        if (remembered.isNullOrBlank()) return options
        val (mine, rest) = options.partition { it.uploader.equals(remembered, ignoreCase = true) }
        if (mine.isEmpty()) return options
        Log.i(TAG, "preferring remembered uploader '$remembered' for this show")
        return mine + rest
    }

    /** Result of a subtitle download attempt, with the failure reason when it fails. */
    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Failure(val reason: String) : DownloadResult()
    }

    /** Downloads one picked candidate to cache. */
    fun downloadOption(fileId: Long): File? {
        return when (val r = downloadOptionDetailed(fileId)) {
            is DownloadResult.Success -> r.file
            is DownloadResult.Failure -> null
        }
    }

    fun downloadOption(option: SubtitleOption): File? {
        return when (val r = downloadOptionDetailed(option)) {
            is DownloadResult.Success -> r.file
            is DownloadResult.Failure -> null
        }
    }

    /** OpenSubtitles download by file id (kept for compatibility). */
    fun downloadOptionDetailed(fileId: Long): DownloadResult {
        val s = loadSettings()
        if (s.apiKey.isBlank()) {
            return DownloadResult.Failure("No OpenSubtitles API key set — add it in Settings")
        }
        val link = getDownloadLink(s, fileId)
            ?: return DownloadResult.Failure("OpenSubtitles gave no download link (sign-in may have failed)")
        return downloadToCacheDetailed(link)
    }

    /** Source-aware download: routes to OpenSubtitles or SubSource. */
    fun downloadOptionDetailed(option: SubtitleOption): DownloadResult {
        return if (option.source == SOURCE_SS) {
            downloadSubSourceDetailed(option.fileId)
        } else {
            downloadOptionDetailed(option.fileId)
        }
    }

    private fun headers(s: Settings): Headers.Builder = Headers.Builder()
        .add("Api-Key", s.apiKey)
        .add("User-Agent", UA)
        .add("Accept", "application/json")

    private fun ssHeaders(s: Settings): Headers.Builder = Headers.Builder()
        .add("X-API-Key", s.subSourceKey)
        .add("User-Agent", UA)
        .add("Accept", "application/json")

    /** Appends every downloadable subtitle on the result page to [out]. */
    private fun collectOptions(
        s: Settings,
        url: String,
        out: MutableList<SubtitleOption>,
        exactMatch: Boolean
    ) {
        val req = Request.Builder().url(url).headers(headers(s).build()).get().build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "search HTTP ${resp.code}")
                    return
                }
                val data = JSONObject(resp.body?.string() ?: "").optJSONArray("data") ?: return
                for (i in 0 until data.length()) {
                    val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: continue
                    val files = attrs.optJSONArray("files") ?: continue
                    if (files.length() == 0) continue
                    val fileId = files.getJSONObject(0).optLong("file_id", 0)
                    if (fileId == 0L) continue
                    val release = attrs.optString("release", "")
                        .ifBlank { "release ${attrs.optString("subtitle_id", "?")}" }
                    val uploader = attrs.optJSONObject("uploader")?.optString("name", "") ?: ""
                    val dlCount = attrs.optLong("download_count", 0)
                    val info = listOf(
                        if (exactMatch) "✓ exact file match" else "",
                        uploader,
                        if (dlCount > 0) "$dlCount downloads" else ""
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    out.add(SubtitleOption(fileId, release, info, uploader, exactMatch, SOURCE_OS))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "subtitle search failed: ${e.message}")
        }
    }

    // ---------------- SubSource (fallback source) ----------------

    /**
     * Searches SubSource by title text and appends up to 8 candidates.
     * TV titles (S01E02 / 1x02 in the cleaned query) are searched as a
     * series and filtered to the requested episode when possible.
     */
    private fun collectSubSourceOptions(
        s: Settings,
        cleaned: TitleCleaner.Cleaned,
        out: MutableList<SubtitleOption>
    ) {
        try {
            val epMatch = Regex("""(?i)\bS(\d{1,2})E(\d{1,3})\b""").find(cleaned.query)
                ?: Regex("""\b(\d{1,2})x(\d{1,3})\b""").find(cleaned.query)
            val season = epMatch?.groupValues?.get(1)?.toIntOrNull()
            val episode = epMatch?.groupValues?.get(2)?.toIntOrNull()
            val isSeries = epMatch != null
            val queryText = if (epMatch != null) {
                cleaned.query.replace(epMatch.value, " ")
                    .replace(Regex("""\s+"""), " ").trim()
            } else {
                cleaned.query
            }
            if (queryText.isBlank()) return

            val searchUrl = StringBuilder(
                "$SS_BASE/movies/search?searchType=text&q=" +
                    URLEncoder.encode(queryText, "UTF-8")
            )
            if (isSeries) {
                searchUrl.append("&type=series")
                if (season != null) searchUrl.append("&season=$season")
            } else {
                searchUrl.append("&type=movie")
            }
            val searchReq = Request.Builder().url(searchUrl.toString())
                .headers(ssHeaders(s).build()).get().build()
            val movieId = client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SubSource search HTTP ${resp.code}")
                    return
                }
                val data = JSONObject(resp.body?.string() ?: "").optJSONArray("data")
                    ?: return
                if (data.length() == 0) return
                // Prefer an entry whose year matches the cleaned title year.
                var firstId = 0L
                var pickedId = 0L
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val id = m.optLong("movieId", 0)
                    if (id == 0L) continue
                    if (firstId == 0L) firstId = id
                    val y = m.optInt("year", 0).toString()
                    if (cleaned.year != null && y == cleaned.year) {
                        pickedId = id
                        break
                    }
                }
                if (pickedId == 0L) pickedId = firstId
                pickedId
            }
            if (movieId == 0L) {
                Log.i(TAG, "SubSource: no title match for '$queryText'")
                return
            }

            val subsUrl = "$SS_BASE/subtitles?movieId=$movieId&language=english&page=1&limit=30"
            val subsReq = Request.Builder().url(subsUrl)
                .headers(ssHeaders(s).build()).get().build()
            client.newCall(subsReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SubSource subtitles HTTP ${resp.code}")
                    return
                }
                val data = JSONObject(resp.body?.string() ?: "").optJSONArray("data")
                    ?: return
                data class SsCand(
                    val id: Long, val release: String, val uploader: String,
                    val downloads: Long, val haystack: String
                )
                val cands = mutableListOf<SsCand>()
                for (i in 0 until data.length()) {
                    val sub = data.getJSONObject(i)
                    val subId = sub.optLong("subtitleId", 0)
                    if (subId == 0L) continue
                    // Forced/foreign-parts-only subs are partial — skip them.
                    if (sub.optBoolean("foreignParts", false)) continue
                    val relParts = mutableListOf<String>()
                    val relArr = sub.optJSONArray("releaseInfo")
                    if (relArr != null) {
                        for (j in 0 until relArr.length()) {
                            relArr.optString(j, "").takeIf { it.isNotBlank() }
                                ?.let { relParts.add(it) }
                        }
                    }
                    val release = relParts.joinToString(" ")
                        .ifBlank { "SubSource subtitle $subId" }
                    val commentary = sub.optString("commentary", "")
                    val uploader = sub.optJSONArray("contributors")
                        ?.optJSONObject(0)?.optString("displayname", "") ?: ""
                    cands.add(
                        SsCand(
                            subId, release, uploader,
                            sub.optLong("downloads", 0),
                            "$release $commentary"
                        )
                    )
                }
                // For episodes, keep only candidates that name the episode;
                // if none do, keep them all rather than showing nothing.
                val filtered = if (isSeries && episode != null) {
                    val matches = cands.filter { ssEpisodeMatches(it.haystack, season, episode) }
                    if (matches.isNotEmpty()) matches else cands
                } else {
                    cands
                }
                filtered.sortedByDescending { it.downloads }.take(8).forEach { c ->
                    val info = listOf(
                        "via SubSource",
                        c.uploader,
                        if (c.downloads > 0) "${c.downloads} downloads" else ""
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    out.add(
                        SubtitleOption(
                            c.id, c.release, info, c.uploader,
                            exactMatch = false, source = SOURCE_SS
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SubSource search failed: ${e.message}")
        }
    }

    /** True when a release string names the given episode (S01E02, 1x02, Ep02...). */
    private fun ssEpisodeMatches(text: String, season: Int?, episode: Int): Boolean {
        val t = text.lowercase()
        val pats = mutableListOf(
            """\bep\s*0*$episode\b""",
            """\bepisode\s+$episode\b""",
            """\be$episode\b""",
            """-\s*0*$episode\b"""
        )
        if (season != null) {
            pats.add(0, """\bs0*${season}e0*${episode}\b""")
            pats.add(1, """\b${season}x0*${episode}\b""")
        }
        return pats.any { Regex(it).containsMatchIn(t) }
    }

    private fun downloadSubSourceDetailed(subtitleId: Long): DownloadResult {
        val s = loadSettings()
        if (s.subSourceKey.isBlank()) {
            return DownloadResult.Failure("No SubSource API key set — add it in Settings")
        }
        val url = "$SS_BASE/subtitles/$subtitleId/download"
        val req = Request.Builder().url(url).headers(ssHeaders(s).build()).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return DownloadResult.Failure("SubSource download failed (HTTP ${resp.code})")
                }
                val bytes = resp.body?.bytes()
                    ?: return DownloadResult.Failure("SubSource returned an empty response")
                processSubSourceBytes(bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SubSource download failed: ${e.message}")
            DownloadResult.Failure("SubSource download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * SubSource downloads arrive as a ZIP in the normal case; be liberal
     * and also accept a raw SRT body or a JSON envelope with a base64 body.
     */
    private fun processSubSourceBytes(bytes: ByteArray): DownloadResult {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return unzipSubtitle(bytes)
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return DownloadResult.Failure("SubSource returned an unreadable file")
        }
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            val obj = try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                return DownloadResult.Failure("SubSource returned an unreadable file")
            }
            val b64 = obj.optString("body", "")
            if (b64.isNotBlank()) {
                val decoded = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: Exception) {
                    return DownloadResult.Failure("SubSource file could not be decoded")
                }
                return processSubSourceBytes(decoded)
            }
            val link = obj.optString("link", "")
                .ifBlank { obj.optString("download_url", "") }
                .ifBlank { obj.optString("url", "") }
            if (link.isNotBlank()) {
                val req = Request.Builder().url(link).header("User-Agent", UA).get().build()
                return try {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            DownloadResult.Failure("SubSource file download failed (HTTP ${resp.code})")
                        } else {
                            val b = resp.body?.bytes()
                                ?: return DownloadResult.Failure("SubSource file was empty")
                            processSubSourceBytes(b)
                        }
                    }
                } catch (e: Exception) {
                    DownloadResult.Failure("SubSource file download failed: ${e.message}")
                }
            }
            return DownloadResult.Failure("SubSource returned an unreadable file")
        }
        if (trimmed.contains("-->")) {
            return writeSubtitleFile(trimmed.toByteArray(Charsets.UTF_8))
        }
        if (trimmed.startsWith("[Script Info]")) {
            val srt = assToSrt(trimmed)
            if (srt != null) return writeSubtitleFile(srt.toByteArray(Charsets.UTF_8))
            return DownloadResult.Failure("SubSource file was ASS format with no dialogue lines")
        }
        return DownloadResult.Failure("SubSource returned an unreadable file")
    }

    /** Extracts the best subtitle file from a ZIP: largest SRT wins; ASS is converted. */
    private fun unzipSubtitle(bytes: ByteArray): DownloadResult {
        var bestSrt: ByteArray? = null
        var bestAss: ByteArray? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory &&
                        (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".ssa"))
                    ) {
                        val content = zip.readBytes()
                        if (name.endsWith(".srt")) {
                            if (bestSrt == null || content.size > bestSrt!!.size) bestSrt = content
                        } else {
                            if (bestAss == null || content.size > bestAss!!.size) bestAss = content
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SubSource unzip failed: ${e.message}")
            return DownloadResult.Failure("SubSource ZIP could not be read: ${e.message}")
        }
        if (bestSrt != null) return writeSubtitleFile(bestSrt!!)
        if (bestAss != null) {
            val srt = assToSrt(String(bestAss!!, Charsets.UTF_8))
            if (srt != null) return writeSubtitleFile(srt.toByteArray(Charsets.UTF_8))
            return DownloadResult.Failure("SubSource file was ASS format with no dialogue lines")
        }
        return DownloadResult.Failure("SubSource ZIP had no subtitle file inside")
    }

    private fun writeSubtitleFile(bytes: ByteArray): DownloadResult {
        if (bytes.isEmpty()) return DownloadResult.Failure("Downloaded file is empty (0 bytes)")
        val out = File(context.cacheDir, "autosub_${System.currentTimeMillis()}.srt")
        FileOutputStream(out).use { it.write(bytes) }
        return DownloadResult.Success(out)
    }

    /** Minimal ASS/SSA → SRT conversion: Dialogue lines only, override tags stripped. */
    private fun assToSrt(ass: String): String? {
        fun convTime(t: String): String? {
            val m = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{1,3})""").find(t.trim()) ?: return null
            val (h, min, sec, frac) = m.destructured
            val ms = (frac.toIntOrNull() ?: 0) * (if (frac.length == 2) 10 else 1)
            return String.format("%02d:%02d:%02d,%03d", h.toInt(), min.toInt(), sec.toInt(), ms)
        }
        val sb = StringBuilder()
        var idx = 0
        for (line in ass.lines()) {
            if (!line.startsWith("Dialogue:")) continue
            val parts = line.removePrefix("Dialogue:").split(",", limit = 10)
            if (parts.size < 10) continue
            val start = convTime(parts[1]) ?: continue
            val end = convTime(parts[2]) ?: continue
            var text = parts[9]
                .replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", "\n").replace("\\n", "\n")
                .replace("\\h", " ")
                .trim()
            if (text.isBlank()) continue
            idx++
            sb.append(idx).append('\n')
                .append(start).append(" --> ").append(end).append('\n')
                .append(text).append("\n\n")
        }
        return if (idx > 0) sb.toString() else null
    }

    // ---------------- OpenSubtitles internals ----------------

    private fun login(s: Settings): String? {
        if (s.username.isBlank() || s.password.isBlank()) return null
        return try {
            val json = JSONObject()
                .put("username", s.username)
                .put("password", s.password)
                .toString()
            val req = Request.Builder().url("$BASE/login")
                .headers(headers(s).add("Content-Type", "application/json").build())
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "login HTTP ${resp.code}")
                    return null
                }
                val token = JSONObject(resp.body?.string() ?: "").optString("token", "")
                if (token.isNotBlank()) {
                    storeToken(token)
                    token
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "login failed: ${e.message}")
            null
        }
    }

    private fun getDownloadLink(s: Settings, fileId: Long): String? {
        var token = storedToken()
        if (token == null && s.username.isNotBlank()) token = login(s)
        return try {
            val json = JSONObject().put("file_id", fileId).toString()
            val hb = headers(s).add("Content-Type", "application/json")
            if (token != null) hb.add("Authorization", "Bearer $token")
            val req = Request.Builder().url("$BASE/download")
                .headers(hb.build())
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "download-link HTTP ${resp.code}")
                    return null
                }
                JSONObject(resp.body?.string() ?: "")
                    .optString("link", "").ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "download-link failed: ${e.message}")
            null
        }
    }

    private fun downloadToCache(link: String): File? {
        return when (val r = downloadToCacheDetailed(link)) {
            is DownloadResult.Success -> r.file
            is DownloadResult.Failure -> null
        }
    }

    private fun downloadToCacheDetailed(link: String): DownloadResult {
        return try {
            val req = Request.Builder().url(link).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return DownloadResult.Failure("Subtitle download failed (HTTP ${resp.code})")
                }
                val out = File(context.cacheDir, "autosub_${System.currentTimeMillis()}.srt")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                if (out.length() == 0L) {
                    return DownloadResult.Failure("Downloaded file is empty (0 bytes)")
                }
                DownloadResult.Success(out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "srt download failed: ${e.message}")
            DownloadResult.Failure("Download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * OpenSubtitles movie hash: file size + LE-uint64 sum of the first
     * 65536 and last 65536 bytes. Fetched via HTTP Range requests.
     * Returns (hexHash, size) or null when the server ignores ranges.
     */
    private fun computeHash(url: String): Pair<String, Long>? {
        return try {
            val headReq = Request.Builder().url(url).header("User-Agent", STREAM_UA).head().build()
            val size: Long = client.newCall(headReq).execute().use { r ->
                if (!r.isSuccessful) return null
                r.header("Content-Length")?.toLongOrNull() ?: return null
            }
            if (size < 131072L) return null

            fun rangeBytes(start: Long, end: Long): ByteArray? {
                val req = Request.Builder().url(url)
                    .header("User-Agent", STREAM_UA)
                    .header("Range", "bytes=$start-$end")
                    .get().build()
                client.newCall(req).execute().use { r ->
                    if (r.code != 206) return null
                    return r.body?.bytes()
                }
            }

            val head64k = rangeBytes(0, 65535) ?: return null
            val tail64k = rangeBytes(size - 65536, size - 1) ?: return null

            var h = size // Long addition wraps, matching unsigned 64-bit math
            fun feed(b: ByteArray) {
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 8) h += bb.long
            }
            feed(head64k)
            feed(tail64k)
            Pair(String.format("%016x", h), size)
        } catch (e: Exception) {
            Log.w(TAG, "hash failed: ${e.message}")
            null
        }
    }
}
