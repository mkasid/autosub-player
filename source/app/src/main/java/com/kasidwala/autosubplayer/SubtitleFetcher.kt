package com.kasidwala.autosubplayer

import android.content.Context
import android.util.Log
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * OpenSubtitles (api.opensubtitles.com) subtitle search + download.
 * Order: (1) title query, (2) OpenSubtitles content hash via HTTP Range.
 */
class SubtitleFetcher(private val context: Context) {

    companion object {
        private const val TAG = "SubtitleFetcher"
        private const val BASE = "https://api.opensubtitles.com/api/v1"
        private const val UA = "AutoSubPlayer/1.0"
        private const val STREAM_UA = "ExoPlayerLib/2.19.1"
        private const val PREFS = "autosub"

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

    data class Settings(val apiKey: String, val username: String, val password: String)

    /** One downloadable subtitle candidate for the on-screen picker. */
    data class SubtitleOption(
        val fileId: Long,
        val release: String,
        val info: String,
        val uploader: String,
        val exactMatch: Boolean
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
            p.getString("os_pass", "") ?: ""
        )
    }

    private fun storedToken(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("os_token", null)

    private fun storeToken(token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("os_token", token).apply()
    }

    /** Returns a local .srt File (best match), or null when nothing found / not configured. */
    fun fetch(videoUrl: String, titleHint: String): File? {
        val first = searchOptions(videoUrl, titleHint).firstOrNull() ?: return null
        return downloadOption(first.fileId)
    }

    /**
     * Full candidate list for the on-screen picker.
     * Like VLC: content-hash first (exact release match), title search fallback.
     * Options from the uploader the user last picked for this show float to
     * the top, so the next episode auto-selects the same submitter's timing.
     */
    fun searchOptions(videoUrl: String, titleHint: String): List<SubtitleOption> {
        val s = loadSettings()
        if (s.apiKey.isBlank()) {
            Log.w(TAG, "no API key configured")
            return emptyList()
        }
        val cleaned = TitleCleaner.clean(titleHint.ifBlank { videoUrl })
        Log.i(TAG, "subtitle query: '${cleaned.query}'")
        val out = mutableListOf<SubtitleOption>()

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
        return preferRememberedUploader(out.take(15), titleHint)
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

    /** Downloads one picked candidate to cache. */
    fun downloadOption(fileId: Long): File? {
        val s = loadSettings()
        if (s.apiKey.isBlank()) return null
        val link = getDownloadLink(s, fileId) ?: return null
        return downloadToCache(link)
    }

    private fun headers(s: Settings): Headers.Builder = Headers.Builder()
        .add("Api-Key", s.apiKey)
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
                    out.add(SubtitleOption(fileId, release, info, uploader, exactMatch))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "subtitle search failed: ${e.message}")
        }
    }

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
        return try {
            val req = Request.Builder().url(link).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val out = File(context.cacheDir, "autosub_${System.currentTimeMillis()}.srt")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "srt download failed: ${e.message}")
            null
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
