# AutoSub Player — Build Notes

**APK:** `app/build/outputs/apk/debug/app-debug.apk` (6.2 MB, debug-signed, sideload-ready)
**Package:** `com.kasidwala.autosubplayer` · **minSdk 21** (Fire OS 5+) · **targetSdk 34**

## What was built
Minimal Fire TV video player that receives a stream URL via `ACTION_VIEW`
(e.g. from TiviMate's external-player option), auto-downloads English
subtitles from OpenSubtitles, and plays with resume.

- **PlayerActivity** — `ACTION_VIEW` + `DEFAULT`/`BROWSABLE`, `http/https` +
  `video/*`. Cleans the stream title (strips 1080p/x264/WEB-DL/etc., keeps
  year), queries OpenSubtitles by title → falls back to OpenSubtitles
  content hash (size + LE-uint64 sums of first/last 64 KB via HTTP Range;
  skipped if the server ignores ranges, e.g. HLS). Downloads the `.srt`
  to cache, injects it as a Media3 subtitle track, auto-selects it.
  Toast: "Subtitles loaded" / "No subtitles found".
- **Resume** — position saved every 15 s + on pause; on reopen, if
  10 s < position < 90 % duration, an `AlertDialog` offers
  "Resume from M:SS" vs "Start over" (D-pad friendly).
- **MainActivity** — leanback launcher entry: fields for OpenSubtitles
  API key (required), username/password (optional, raises quota), Save
  button, and a short "use with TiviMate" help text.

## How it was built (unusual — read before rebuilding)
Gradle's daemon cannot run on this VM (starts, then drops out of its own
registry — "Could not receive a message from the daemon"). The APK was
built **manually**:
`aapt2 compile/link` → `kotlinc` → `d8` → `zipalign` → `apksigner`.
Full reproducible script: `~/workspace/apkscratch/build-manual.sh`
(run it to rebuild; it re-links, recompiles, re-dexes, re-signs).
SDK: `~/workspace/android-sdk` (platform android-34, build-tools 34.0.0,
downloaded as zips via curl — sdkmanager can't tunnel through this VM's
egress proxy). Kotlin compiler: `~/workspace/apkscratch/kotlinc/`.
Deps (17 jars, in `~/workspace/apkscratch/deps/`, list in
`classpath-full.txt`): media3 1.4.1 (exoplayer, ui, common, datasource,
decoder, extractor, dash, hls, rtsp, container, database), okhttp 4.12.0,
okio-jvm 3.6.0, kotlin-stdlib 1.9.24, androidx.core 1.13.1, recyclerview
1.3.0, guava 32.1.2-android, annotation 1.7.1 + annotation-experimental.

Simplifications vs. the original plan (all safe):
- Dropped **appcompat** → framework `android.app.Activity` /
  `android.app.AlertDialog` + `@android:style/Theme.Material.*`.
  Nothing in the UI needed appcompat.
- Dropped **kotlinx-coroutines** → plain `Thread` + `Handler` for the
  subtitle fetch. No behavior change.

## TiviMate setup (not verified — please confirm on the Firestick)
In TiviMate: Settings → Player → External player (or per-playlist:
long-press playlist → Settings → External player) → choose
**AutoSub Player**. TiviMate fires `ACTION_VIEW` with the stream URL;
some builds also pass the channel/programme name as an extra, which we
read (`title`/`TITLE`) — otherwise we derive it from the URL's last
path segment, so messy IPTV filenames still get a hash fallback.

## OpenSubtitles quota caveat
- A free API key alone: ~5 downloads/day. **Enter username + password
  in the app's settings** — the app logs in (`POST /api/v1/login`) and
  uses the token, which raises the daily download limit substantially.
- Subtitle *search* calls are not the bottleneck; *downloads* are.
- If the daily quota is exhausted, playback still works — you just get
  the "No subtitles found" toast until the quota resets.

## Untested / stubs
- **Never run on a real Firestick.** Compiled clean, manifest verified
  (`aapt dump` shows leanback launcher + `ACTION_VIEW` filter), APK
  signature verifies — but playback, D-pad focus, subtitle rendering,
  and the OpenSubtitles flow are untested on device.
- The OpenSubtitles hash path needs a server that honors HTTP Range;
  most IPTV VOD servers do, HLS playlists don't (graceful skip).
- `android:banner` and launcher icons are PIL-generated placeholders.
- Debug-signed with a local debug key (`~/workspace/apkscratch/debug.keystore`).
