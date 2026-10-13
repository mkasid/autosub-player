# AutoSub Player — Handoff to another Muse instance

**Date:** 2026-10-12
**App:** AutoSub Player — minimal Fire TV app, external player for TiviMate
**Package:** `com.kasidwala.autosubplayer` · minSdk 21 (Fire OS 5+) · targetSdk 34
**Current version:** v13, live at `https://go.kasidwala.com/aplayer13`
**Status:** v13 delivered 2026-10-11, awaiting Mustufa's on-device test. The QA loop with him is: he installs on his Firestick, reports one-line issues, you ship a fix build. He is responsive and technical (20+ years software, Director-level).

## What it does

Receives a stream URL via `ACTION_VIEW` (TiviMate → Settings → Playback → external player), plays it immediately with ExoPlayer, fetches English subtitles automatically, supports resume per video, D-pad controls, subtitle picker with sync adjustment, and video aspect-ratio switching.

Videos come from an IPTV/Stalker server via TiviMate. The big win is VOD movies/shows (live-channel subtitles basically don't exist).

## Source layout (this zip)

```
app/src/main/java/com/kasidwala/autosubplayer/
  PlayerActivity.kt   — playback, custom controller UI, D-pad state machine, subtitle orchestration
  SubtitleFetcher.kt  — OpenSubtitles API (api.opensubtitles.com): hash-first search, download
  SrtParser.kt        — parses downloaded SRT, renders cues from a position ticker into a TextView
  ResumeStore.kt      — per-video resume position + subtitle delay, SharedPreferences "resume"
  MainActivity.kt     — leanback launcher: OpenSubtitles API key, username/password, custom UA/Referer
  TitleCleaner.kt     — strips 1080p/x264/WEB-DL/etc from IPTV filenames, keeps year
  CrashGuard.kt       — uncaught-exception guard
res/                  — layouts, drawables (btn_controller.xml etc.)
```

## How to build (Gradle does NOT work)

Gradle's daemon cannot run on the VM this was built on (starts, then drops out of its own registry). The APK is built manually:

```
aapt2 compile/link → kotlinc → d8 → zipalign → apksigner
```

The original script is `~/workspace/apkscratch/build-manual.sh` (not in this zip — it references local paths). To rebuild on a fresh machine you need:

- **Android SDK**: platform android-34 + build-tools 34.0.0 (aapt2, d8, zipalign, apksigner)
- **Kotlin compiler** (kotlinc)
- **Portable JDK 17** (a Temurin 17 was used; any JDK 17 works)
- **Dependencies** (17 jars — all re-downloadable from Maven Central, do NOT need to be transferred):
  media3 1.4.1 (exoplayer, ui, common, datasource, decoder, extractor, dash, hls, rtsp, container, database),
  okhttp 4.12.0, okio-jvm 3.6.0, kotlin-stdlib 1.9.24, androidx.core 1.13.1,
  recyclerview 1.3.0, guava 32.1.2-android, annotation 1.7.1 + annotation-experimental
- **Signing key** — `debug.keystore` is attached to the handoff email (password: `android`).
  **You MUST sign updates with this exact keystore**, otherwise Android will refuse to install
  the update over the existing app and Mustufa loses his settings/resume data.

Build gotchas learned the hard way:
- **NEVER use media3-ui's PlayerView.** Its library resource IDs are never rewritten in the manual
  build → `InflateException` at runtime. The player UI is custom: `SurfaceView` + own controller
  + subtitles rendered into a `TextView`. Do not reintroduce the ui module.
- **d8:** pass `android.jar` as `--lib` ONLY, never also as a program input
  ("defined multiple times" error).
- Simplifications already in the source: no appcompat (framework Activity/AlertDialog +
  `@android:style/Theme.Material.*`), no kotlinx-coroutines (plain `Thread` + `Handler`).

## Standing rules (learned from real device testing — do not regress these)

1. **Stalker servers issue short-lived stream tokens. NOTHING may delay the first stream request.**
   No probing, no resume dialog, no subtitle download before playback starts. Playback begins
   instantly; the resume position is auto-applied as a seek a moment later; subtitles are fetched
   in parallel and rendered from a ticker (never re-request the stream). Violating this → 403.
2. **Resume is keyed by stable video title** (`resumeKey()`), never the full URL — the Stalker token
   in the URL changes every launch. Same key is used for the per-video subtitle delay.
3. **Subtitle matching is hash-first, like VLC** (OpenSubtitles content hash), title search as fallback.
   Hash hits are labeled "✓ exact file match" in the picker. Title-first grabbed the most popular
   upload for a different cut and the subs didn't line up.
4. **Embedded subtitles first**: if the stream has a usable embedded text track (English preferred),
   use it and skip the online search. An explicit picker choice overrides embedded.
5. **Video size is session-only and always starts at Fit** (black bars top/bottom, height reduced).
   The Video button cycles Fit/Zoom/16:9/4:3 per video but resets next launch. (Persisting the
   choice once trapped Mustufa in Zoom with no visible way out.)
6. **D-pad scheme** (explicit `buttonMode` flag — focus-based handling was unreliable):
   Center = play/pause · Left/Right = ∓10s seek · FF/RW buttons = ±30s ·
   Up/Down with controls hidden = show controls · Up/Down with controls visible = enter button mode
   (Left/Right moves between buttons, Center clicks, Up/Down/Back exits mode) ·
   Back with controls visible = hide controls · Back with controls hidden = exit app.
7. **Subtitles sit at the bottom**: 64dp margin, auto-raised to 200dp while controls are visible.
   Sync buttons −0.5s/+0.5s (VLC convention: + = subs appear later), persisted per video.
8. **OpenSubtitles credentials live ONLY in the app's SharedPreferences** (settings screen in the app;
   survives updates). Never hardcode or commit the API key. Free API key alone ≈ 5 downloads/day;
   username+password login raises the quota substantially.

## Distribution (how new versions reach his Firestick)

- **GitHub:** public repo `mkasid/autosub-player`. APKs are committed and served via
  `https://github.com/mkasid/autosub-player/raw/main/autosub-player-vN.apk`.
  Push with the repo owner's GitHub credentials (the previous Muse used
  `python3 ~/workspace/skills/github/bin/gh-push.py /tmp/apkrel autosub-player --public`).
  **Gotcha:** that script rebuilds the whole repo tree from the local dir each push, so the local
  dir must always contain EVERY APK whose download link must keep working.
- **Short links:** `https://go.kasidwala.com/aplayerN` via `POST https://go.kasidwala.com/api/shorten`
  with `Authorization: Bearer <token>`. The token lives in `~/workspace/url-shortener/.secrets`
  on the previous Muse's machine (value included in the handoff email — keep it private;
  it controls his shortener). Always verify the short link returns 200 with the exact byte size.

## Version history (condensed)

- v1–v3: crash (media3-ui) → removed ui → black screen
- v4–v7: 403s; root cause found — Stalker token expiry from delayed first request (see rule 1)
- v8: instant playback + parallel subtitles — first working version
- v9: subtitle picker + sync buttons + resume keyed by title (was URL, never matched)
- v10: hash-first matching (fixed misaligned subs) + embedded subs first
- v11: FF/RW ±30s, visible button focus style, Video size button (Fit/Zoom/16:9/4:3)
- v12: buttonMode D-pad rewrite, Back hides controls, subtitles to bottom
- v13 (current): video size always starts at Fit, session-only (was: persisted Zoom trap)

## What's pending

v13 is on his Firestick awaiting test. Open questions for the next QA round: seek reliability,
Back-hides-controls behavior, subtitle position, Fit default giving the expected letterbox.
Mustufa will report in one-liners; respond with a fix build and a one-line status before he nudges.

## Keystore

`debug.keystore` (password `android`) is attached to the handoff email. Same package name +
same signature = updates install cleanly over v13 with all settings and resume data intact.
