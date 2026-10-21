package com.kasidwala.autosubplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * Minimal Fire TV player (no media3-ui — its resources don't survive the
 * manual build). SurfaceView + custom D-pad controller + subtitles rendered
 * from Player.onCues into a TextView.
 *
 * Receives a stream URL via ACTION_VIEW (e.g. from TiviMate's external
 * player), fetches English subtitles automatically (title, then hash
 * fallback), and plays with resume.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var seekBar: SeekBar
    private lateinit var controller: View
    private lateinit var buttonRow: View
    private lateinit var subtitleDrawer: View
    private lateinit var subtitleList: ListView
    private lateinit var loading: ProgressBar
    private lateinit var btnSubtitles: Button
    private lateinit var btnSyncMinus: Button
    private lateinit var btnSyncPlus: Button
    private lateinit var btnVideoSize: Button
    private lateinit var tvSync: TextView
    private lateinit var resumeStore: ResumeStore

    private var videoSizeMode = 0 // 0=Fit, 1=Zoom, 2=16:9, 3=4:3

    private var streamUri: Uri? = null
    private var streamTitle: String = ""
    private var srtCues: List<SrtParser.Cue> = emptyList()
    private var intentInfo: String = ""
    private var subDelayMs: Long = 0L
    // Subtitle source priority: embedded track > online (auto or user-picked).
    private var hasEmbeddedSubs = false
    private var userPickedOnline = false
    private var subsDecided = false

    // Identities tried (probed first, then retried on 403) when the server rejects us.
    private lateinit var uaCandidates: List<String>
    private var uaIndex = 0
    private val triedUas = mutableListOf<String>()

    private val handler = Handler(Looper.getMainLooper())

    private val uiRunnable = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 500)
        }
    }
    private val saveRunnable = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 15_000)
        }
    }
    private val hideRunnable = Runnable { hideController() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        try {
            setContentView(R.layout.activity_player)
            surfaceView = findViewById(R.id.surface_view)
            tvSubtitle = findViewById(R.id.tv_subtitle)
            tvTitle = findViewById(R.id.tv_title)
            tvPosition = findViewById(R.id.tv_position)
            tvDuration = findViewById(R.id.tv_duration)
            btnPlayPause = findViewById(R.id.btn_play_pause)
            seekBar = findViewById(R.id.seek_bar)
            controller = findViewById(R.id.controller)
            buttonRow = findViewById(R.id.button_row)
            subtitleDrawer = findViewById(R.id.subtitle_drawer)
            subtitleList = findViewById(R.id.subtitle_list)
            loading = findViewById(R.id.loading)
            btnSubtitles = findViewById(R.id.btn_subtitles)
            btnSyncMinus = findViewById(R.id.btn_sync_minus)
            btnSyncPlus = findViewById(R.id.btn_sync_plus)
            btnVideoSize = findViewById(R.id.btn_video_size)
            tvSync = findViewById(R.id.tv_sync)
            controllerButtons = arrayOf(btnSubtitles, btnSyncMinus, btnSyncPlus, btnVideoSize)
            resumeStore = ResumeStore(this)

            val uri = intent?.data
            if (uri == null) {
                Toast.makeText(this, "No stream URL received", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            streamUri = uri
            streamTitle = extractTitle(intent, uri)
            tvTitle.text = streamTitle
            intentInfo = buildIntentInfo(intent, uri)
            subDelayMs = loadSubDelay()

            btnSubtitles.setOnClickListener { showSubtitleDrawer() }
            btnSyncMinus.setOnClickListener { adjustSync(-500) }
            btnSyncPlus.setOnClickListener { adjustSync(500) }
            btnVideoSize.setOnClickListener { cycleVideoSize() }
            btnPlayPause.setOnClickListener { togglePlayPause() }
            // Always start at Fit (letterbox): aspect choice is per-session,
            // like VLC/MX — a persisted Zoom once left video cropped with no
            // obvious way back.

            initPlayer()
            maybeResumeThenPlay()
        } catch (t: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("Player failed to start")
                .setMessage("${t.javaClass.name}: ${t.message}")
                .setPositiveButton("Close") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    /** Summarizes what the sending app gave us (host + extras keys only — never credentials). */
    private fun buildIntentInfo(intent: Intent, uri: Uri): String {
        val sb = StringBuilder()
        sb.append("Stream host: ").append(uri.host ?: "?")
        val extras = intent.extras
        val keys = extras?.keySet()?.joinToString(", ").orEmpty()
        sb.append("\nIntent extras: ").append(if (keys.isEmpty()) "(none)" else keys)
        return sb.toString()
    }

    /** Prefer a title extra from the sending app, else the URL's last segment. */
    private fun extractTitle(intent: Intent, uri: Uri): String {
        val extras = intent.extras
        val keys = listOf(
            "title", "TITLE", "Title", Intent.EXTRA_TITLE,
            "channel_name", "program_title", "media_title", "video_title"
        )
        for (k in keys) {
            val v = extras?.getString(k)
            if (!v.isNullOrBlank()) return v
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        private const val VLC_UA = "VLC/3.6.4 LibVLC/3.6.4"
        private const val EXO_UA = "ExoPlayerLib/2.19.1"
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Fire TV sleeps after ~10 min of "idle" — video playback counts
            // as busy only if we say so. Flag follows actual playback: pausing
            // lets the device sleep normally again.
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onCues(cueGroup: CueGroup) {
            if (hasEmbeddedSubs && !userPickedOnline) {
                tvSubtitle.text = cueGroup.cues
                    .joinToString("\n") { it.text?.toString().orEmpty() }
                    .trim()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // First answer wins: embedded subtitles pre-empt the online search.
            if (subsDecided) return
            subsDecided = true
            hasEmbeddedSubs = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            handler.post {
                if (hasEmbeddedSubs) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Using embedded subtitles",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    fetchSubtitlesAsync()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause
            val is403 = cause is HttpDataSource.InvalidResponseCodeException &&
                    cause.responseCode == 403
            if (is403 && uaIndex < uaCandidates.size - 1) {
                uaIndex++
                val pos = player?.currentPosition ?: 0L
                handler.post {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Server refused us, retrying as ${shortUa(uaCandidates[uaIndex])}...",
                        Toast.LENGTH_SHORT
                    ).show()
                    attachPlayer(buildPlayer())
                    replayAt(pos)
                }
            } else {
                handler.post { showPlaybackError(error) }
            }
        }
    }

    private fun shortUa(ua: String): String = when {
        ua.startsWith("VLC") -> "VLC"
        ua.startsWith("ExoPlayer") -> "ExoPlayer"
        ua.startsWith("Mozilla") -> "browser"
        ua.startsWith("Lavf") -> "ffmpeg"
        ua.startsWith("TiviMate") -> "TiviMate"
        ua.startsWith("IPTVSmarters") -> "Smarters"
        ua.startsWith("Dalvik") -> "Android"
        else -> "custom identity"
    }

    private fun showPlaybackError(error: PlaybackException) {
        val cause = error.cause
        val tried = triedUas.distinct().joinToString(", ") { shortUa(it) }
        AlertDialog.Builder(this@PlayerActivity)
            .setTitle("Playback error")
            .setMessage(
                "${error.errorCodeName}\n${error.message}" +
                    (if (cause != null) "\n\nCaused by: ${cause.javaClass.simpleName}: ${cause.message}" else "") +
                    "\n\nIdentities tried: $tried" +
                    "\n\n--- What TiviMate sent us ---\n$intentInfo"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun userAgentCandidates(): List<String> {
        val prefs = getSharedPreferences("autosub", Context.MODE_PRIVATE)
        val custom = prefs.getString("custom_ua", "").orEmpty().trim()
        val list = mutableListOf<String>()
        if (custom.isNotEmpty()) list.add(custom)
        // ExoPlayer first: closest to TiviMate's own internal player.
        list.add(EXO_UA)
        list.add(VLC_UA)
        list.add(BROWSER_UA)
        return list.distinct()
    }

    private fun customReferer(): String? {
        val r = getSharedPreferences("autosub", Context.MODE_PRIVATE)
            .getString("custom_referer", "").orEmpty().trim()
        return r.ifEmpty { null }
    }

    private fun buildPlayer(): ExoPlayer {
        val ua = uaCandidates[uaIndex]
        triedUas.add(ua)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        customReferer()?.let {
            httpFactory.setDefaultRequestProperties(mapOf("Referer" to it))
        }
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
    }

    private fun attachPlayer(p: ExoPlayer) {
        player?.release()
        player = p
        p.setVideoSurfaceView(surfaceView)
        // Letterbox by default — never stretch.
        p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .build()
        p.addListener(playerListener)
    }

    private fun initPlayer() {
        uaCandidates = userAgentCandidates()
        uaIndex = 0
        attachPlayer(buildPlayer())
    }

    /**
     * Stalker servers issue short-lived stream tokens: the FIRST request must
     * go out immediately. So we start playback at once (at the saved position
     * when there is one), fetch subtitles in parallel, and show the resume
     * dialog on top of the already-playing video.
     */
    /**
     * Stable per-video key. Stalker URLs embed a fresh token on EVERY launch,
     * so the raw URL can never be the key — use the title TiviMate sends us,
     * falling back to the URL minus query/fragment (where tokens live).
     */
    private fun resumeKey(): String {
        if (streamTitle.isNotBlank()) return "t:" + streamTitle.trim()
        val uri = streamUri ?: return "u:unknown"
        val sb = StringBuilder()
        sb.append(uri.scheme ?: "").append("://").append(uri.host ?: "")
        if (uri.port > 0) sb.append(":").append(uri.port)
        sb.append(uri.path ?: "")
        return "u:" + sb.toString()
    }

    /**
     * No resume dialog: while it sits open the stream idles on a short-lived
     * Stalker token and dies with a 403. Always resume instead — the
     * "Resumed from M:SS" toast confirms it; seek back manually to restart.
     */
    private fun maybeResumeThenPlay() {
        val mark = resumeStore.load(resumeKey())
        val startPos = if (mark != null && mark.positionMs > 10_000 &&
                (mark.durationMs <= 0 || mark.positionMs < mark.durationMs * 0.9)) {
            mark.positionMs
        } else 0L
        startPlaybackImmediately(startPos)
    }

    /** Starts the stream NOW — nothing may delay this on token-based servers. */
    private fun startPlaybackImmediately(positionMs: Long) {
        val p = player ?: return
        val uri = streamUri ?: return
        try {
            loading.visibility = View.VISIBLE
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            applyVideoSize()
            if (positionMs > 0) {
                p.seekTo(positionMs)
                Toast.makeText(this, "Resumed from ${fmt(positionMs)}", Toast.LENGTH_SHORT).show()
            }
            p.playWhenReady = true
            loading.visibility = View.GONE
            tvTitle.visibility = View.VISIBLE
            handler.postDelayed({ tvTitle.visibility = View.GONE }, 4000)
            handler.post(uiRunnable)
            handler.postDelayed(saveRunnable, 15_000)
            showController()
        } catch (t: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("Playback failed")
                .setMessage("${t.javaClass.name}: ${t.message}")
                .setPositiveButton("Close") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    /**
     * Downloads + parses subtitles fully in the background and renders them
     * from the position ticker — never re-requests the stream URL.
     */
    private fun fetchSubtitlesAsync() {
        val uri = streamUri ?: return
        Thread {
            val srt: File? = try {
                SubtitleFetcher(this@PlayerActivity).fetch(uri.toString(), streamTitle)
            } catch (t: Throwable) {
                null
            }
            val cues = if (srt != null && srt.exists()) SrtParser.parse(srt) else emptyList()
            handler.post {
                srtCues = cues
                if (cues.isNotEmpty()) {
                    Toast.makeText(this@PlayerActivity, "Subtitles loaded", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Opens the online subtitle list; the user picks which file to use. */
    /** Item 3: subtitle picker as a right-side drawer. Selecting applies
     *  immediately (so you can see which one works) and keeps the drawer
     *  open; Back closes it. */
    private fun showSubtitleDrawer() {
        val uri = streamUri ?: return
        Toast.makeText(this, "Searching subtitles...", Toast.LENGTH_SHORT).show()
        Thread {
            val options = try {
                SubtitleFetcher(this@PlayerActivity).searchOptions(uri.toString(), streamTitle)
            } catch (t: Throwable) {
                emptyList()
            }
            handler.post {
                if (options.isEmpty()) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "No subtitles found online for this title.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                val remembered = SubtitleFetcher.rememberedUploader(
                    this@PlayerActivity, SubtitleFetcher.seriesKeyFor(streamTitle)
                )
                val labels = options.map {
                    val star = if (!remembered.isNullOrBlank() &&
                        it.uploader.equals(remembered, ignoreCase = true)
                    ) "★ " else ""
                    if (it.info.isNotBlank()) "$star${it.release} (${it.info})"
                    else "$star${it.release}"
                }
                subtitleList.adapter = ArrayAdapter(
                    this@PlayerActivity,
                    android.R.layout.simple_list_item_1,
                    labels
                )
                subtitleList.setOnItemClickListener { _, _, which, _ ->
                    // Apply immediately, keep drawer open for preview.
                    downloadPickedSubtitle(options[which])
                }
                subtitleDrawer.visibility = View.VISIBLE
                subtitleList.requestFocus()
                // Keep controls visible while the drawer is open.
                handler.removeCallbacks(hideRunnable)
            }
        }.start()
    }

    private fun hideSubtitleDrawer() {
        subtitleDrawer.visibility = View.GONE
        // Return focus to the controller.
        if (controller.visibility == View.VISIBLE) {
            if (buttonMode && controllerButtons.isNotEmpty()) {
                controllerButtons[buttonIndex].requestFocus()
            } else {
                btnPlayPause.requestFocus()
            }
            showController()
        }
    }

    private fun downloadPickedSubtitle(option: SubtitleFetcher.SubtitleOption) {
        Toast.makeText(this, "Downloading subtitles...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                SubtitleFetcher(this@PlayerActivity).downloadOptionDetailed(option.fileId)
            } catch (t: Throwable) {
                SubtitleFetcher.DownloadResult.Failure("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
            }
            val srt = (result as? SubtitleFetcher.DownloadResult.Success)?.file
            val cues = if (srt != null && srt.exists()) SrtParser.parse(srt) else emptyList()
            handler.post {
                if (cues.isNotEmpty()) {
                    srtCues = cues
                    // Explicit user choice beats the embedded track.
                    userPickedOnline = true
                    // Remember this submitter for the show: next episodes will
                    // prefer their subtitles automatically (same timing).
                    if (option.uploader.isNotBlank()) {
                        SubtitleFetcher.rememberUploader(
                            this@PlayerActivity,
                            SubtitleFetcher.seriesKeyFor(streamTitle),
                            option.uploader
                        )
                        Toast.makeText(
                            this@PlayerActivity,
                            "Subtitles loaded — will prefer ${option.uploader} for this show",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this@PlayerActivity, "Subtitles loaded", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Show the specific reason instead of a generic failure.
                    val reason = when (result) {
                        is SubtitleFetcher.DownloadResult.Failure -> result.reason
                        is SubtitleFetcher.DownloadResult.Success ->
                            "File downloaded but has no readable subtitle lines (not SRT?)"
                        else -> "Unknown error"
                    }
                    Toast.makeText(
                        this@PlayerActivity,
                        "Couldn't load that subtitle: $reason",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    // ---------- Subtitle sync ----------

    private fun subDelayKey(): String = "subdelay_" + resumeKey().hashCode().toString(16)

    private fun loadSubDelay(): Long =
        getSharedPreferences("autosub", Context.MODE_PRIVATE)
            .getLong(subDelayKey(), 0L)

    private fun saveSubDelay() {
        getSharedPreferences("autosub", Context.MODE_PRIVATE)
            .edit().putLong(subDelayKey(), subDelayMs).apply()
    }

    private fun syncLabel(): String {
        val secs = subDelayMs / 1000.0
        val sign = if (secs >= 0) "+" else "−"
        return "sync $sign${"%.1f".format(kotlin.math.abs(secs))}s"
    }

    /** Shifts subtitles earlier (-) or later (+) by [deltaMs]. */
    private fun adjustSync(deltaMs: Long) {
        subDelayMs = (subDelayMs + deltaMs).coerceIn(-30_000, 30_000)
        saveSubDelay()
        tvSync.text = syncLabel()
        showController()
    }

    /** Replays the stream from a position (used after a 403 identity retry). */
    private fun replayAt(positionMs: Long) {
        val p = player ?: return
        val uri = streamUri ?: return
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
    }

    // ---------- Video size ----------

    private val videoSizeLabels = arrayOf("Fit", "Zoom", "16:9", "4:3")

    private fun cycleVideoSize() {
        videoSizeMode = (videoSizeMode + 1) % videoSizeLabels.size
        applyVideoSize()
        val desc = when (videoSizeMode) {
            0 -> "Fit: whole picture, black bars if needed"
            1 -> "Zoom: fills the screen, crops the edges"
            2 -> "Forced 16:9"
            else -> "Forced 4:3"
        }
        Toast.makeText(this, desc, Toast.LENGTH_SHORT).show()
        showController()
    }

    private fun applyVideoSize() {
        val p = player ?: return
        btnVideoSize.text = "Video: ${videoSizeLabels[videoSizeMode]}"
        when (videoSizeMode) {
            0 -> {
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                setSurfaceAspect(0f)
            }
            1 -> {
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                setSurfaceAspect(0f)
            }
            2 -> {
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                setSurfaceAspect(16f / 9f)
            }
            3 -> {
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                setSurfaceAspect(4f / 3f)
            }
        }
    }

    /** Forces the surface to an aspect ratio (0 = free, letterboxed by the player). */
    private fun setSurfaceAspect(ratio: Float) {
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (ratio <= 0f) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            if (w.toFloat() / h > ratio) {
                params.height = h
                params.width = (h * ratio).toInt()
            } else {
                params.width = w
                params.height = (w / ratio).toInt()
            }
        }
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    // ---------- D-pad controls ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Subtitle drawer open: Back closes it; Up/Down/OK navigate the list.
        if (subtitleDrawer.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideSubtitleDrawer()
                    return true
                }
            }
            // Let the ListView handle Up/Down/Center natively.
            return super.onKeyDown(keyCode, event)
        }
        // Button mode: D-pad drives the controller buttons explicitly.
        // Everywhere else, Left/Right always seek — the two can never fight.
        if (buttonMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveButtonFocus(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveButtonFocus(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    currentFocus?.performClick()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK -> {
                    // Back to tier 1 (seek bar only); Back again hides all.
                    exitButtonMode()
                    return true
                }
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Item 1: first press just shows the seek bar with focus on
                // play/pause; a second press (on the focused button) toggles.
                if (controller.visibility != View.VISIBLE) {
                    showControllerTier1()
                } else {
                    togglePlayPause()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-10_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(10_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(30_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-30_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Tier 1 showing: hide. Hidden: show tier 1.
                if (controller.visibility == View.VISIBLE) hideController()
                else showControllerTier1()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Item 2: on the seek bar, Down reveals the other buttons.
                if (controller.visibility == View.VISIBLE) enterButtonMode()
                else showControllerTier1()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Back hides the controls first; only exits when they're gone.
                if (controller.visibility == View.VISIBLE) {
                    hideController()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Explicit controller-button navigation state.
    private var buttonMode = false
    private var buttonIndex = 0
    private lateinit var controllerButtons: Array<Button>

    private fun enterButtonMode() {
        buttonMode = true
        buttonIndex = 0
        buttonRow.visibility = View.VISIBLE
        controllerButtons[buttonIndex].requestFocus()
        updateButtonSelection()
        showController()
    }

    private fun exitButtonMode() {
        // Back to tier 1: seek bar stays, buttons hide, focus to play/pause.
        buttonMode = false
        buttonRow.visibility = View.GONE
        currentFocus?.clearFocus()
        updateButtonSelection()
        if (controller.visibility == View.VISIBLE) {
            btnPlayPause.requestFocus()
            showController()
        }
    }

    /** Tier 1: seek bar only, focus on play/pause (item 1). */
    private fun showControllerTier1() {
        buttonMode = false
        buttonRow.visibility = View.GONE
        updateButtonSelection()
        showController()
        btnPlayPause.requestFocus()
    }

    private fun moveButtonFocus(dir: Int) {
        buttonIndex = (buttonIndex + dir + controllerButtons.size) % controllerButtons.size
        controllerButtons[buttonIndex].requestFocus()
        updateButtonSelection()
        showController()
    }

    /**
     * The orange highlight follows the D-pad scheme's logical focus
     * (buttonMode + buttonIndex) via the "selected" drawable state — never
     * the Android focus system, so a button can never look highlighted
     * while focus is actually elsewhere (e.g. on the seek bar).
     */
    private fun updateButtonSelection() {
        for (i in controllerButtons.indices) {
            controllerButtons[i].isSelected = buttonMode && i == buttonIndex
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        showController()
        updateUi()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var pos = p.currentPosition + deltaMs
        if (pos < 0) pos = 0
        if (dur > 0 && pos > dur) pos = dur
        p.seekTo(pos)
        showController()
        updateUi()
    }

    private fun showController() {
        controller.visibility = View.VISIBLE
        tvTitle.visibility = View.VISIBLE
        setSubtitleMargin(200)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 4000)
    }

    private fun hideController() {
        // An invisible focused button would keep eating D-pad input (killing
        // seek), so always leave button mode when the controls go away.
        buttonMode = false
        buttonRow.visibility = View.GONE
        subtitleDrawer.visibility = View.GONE
        currentFocus?.clearFocus()
        updateButtonSelection()
        controller.visibility = View.GONE
        tvTitle.visibility = View.GONE
        setSubtitleMargin(64)
    }

    /** Bottom margin for subtitles: high when controls are up, near the edge otherwise. */
    private fun setSubtitleMargin(dp: Int) {
        val params = tvSubtitle.layoutParams as FrameLayout.LayoutParams
        params.bottomMargin = (dp * resources.displayMetrics.density).toInt()
        tvSubtitle.layoutParams = params
    }

    private fun updateUi() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        tvPosition.text = fmt(pos)
        if (dur > 0) {
            tvDuration.text = fmt(dur)
            seekBar.max = (dur / 1000).toInt()
            seekBar.progress = (pos / 1000).toInt()
        } else {
            tvDuration.text = "live"
            seekBar.max = 0
        }
        btnPlayPause.text = if (p.playWhenReady) "⏸" else "▶"
        // Online SRT renders here; embedded subs render via onCues instead.
        // subDelayMs > 0 means subtitles appear later (VLC convention).
        if (!hasEmbeddedSubs || userPickedOnline) {
            tvSubtitle.text = SrtParser.activeCue(srtCues, pos - subDelayMs)?.text ?: ""
        }
        if (tvSync.text.toString() != syncLabel()) tvSync.text = syncLabel()
    }

    private fun savePosition() {
        val p = player ?: return
        if (streamUri == null) return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        if (pos > 5_000) {
            resumeStore.save(resumeKey(), pos, dur, streamTitle)
        }
    }

    private fun fmt(ms: Long): String {
        if (ms < 0) return "0:00"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(uiRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(hideRunnable)
        savePosition()
        player?.release()
        player = null
    }
}
