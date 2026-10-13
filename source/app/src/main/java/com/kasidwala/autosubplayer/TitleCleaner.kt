package com.kasidwala.autosubplayer

/**
 * Turns a messy stream URL / filename / playlist title into a clean
 * OpenSubtitles search query, e.g.
 * "Dune.Part.Two.2024.1080p.WEB-DL.x264-GROUP" -> "Dune Part Two 2024"
 */
object TitleCleaner {

    private val QUALITY_TOKENS = setOf(
        "1080p", "720p", "480p", "2160p", "4k", "uhd", "hd",
        "x264", "x265", "h264", "h265", "hevc", "avc", "xvid",
        "web-dl", "webdl", "webrip", "web", "bluray", "brrip", "bdrip", "blu-ray",
        "hdtv", "dvdrip", "hdrip", "hdcam", "cam", "tc",
        "hdr", "hdr10", "hdr10plus", "dts", "dtshd", "truehd", "atmos",
        "aac", "ac3", "eac3", "ddp5", "dd5", "mp3", "opus",
        "yify", "yts", "rarbg", "ettv", "eztv", "psa", "fgt", "ion10",
        "galaxyrg", "tgx", "mkvcage", "extrafan", "multi", "dubbed", "subbed",
        "repack", "proper", "extended", "unrated", "remastered", "imax"
    )

    private val EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "ts", "m3u8", "mpd", "mov", "wmv",
        "flv", "webm", "m4v", "3gp", "mpg", "mpeg", "vob"
    )

    data class Cleaned(val query: String, val year: String?)

    fun clean(rawInput: String): Cleaned {
        var s = rawInput
        // last path segment of a URL/path
        s = s.substringAfterLast('/').substringAfterLast('\\')
        // URL-decode
        s = try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
        // drop query string
        s = s.substringBefore('?')

        // extract year first (often inside parens we are about to strip)
        val year = Regex("""\b(19|20)\d{2}\b""").find(s)?.value

        // strip extension
        val dot = s.lastIndexOf('.')
        if (dot > 0) {
            val ext = s.substring(dot + 1).lowercase()
            if (ext in EXTENSIONS || ext.length <= 4) s = s.substring(0, dot)
        }

        // remove [bracket] and (paren) groups
        s = s.replace(Regex("""\[[^\]]*]"""), " ")
        s = s.replace(Regex("""\([^)]*\)"""), " ")

        // separators -> spaces
        s = s.replace(Regex("""[._\-+]+"""), " ")

        // drop release/quality tokens, keep S01E02-style tokens
        val words = s.split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it.lowercase() !in QUALITY_TOKENS }
        s = words.joinToString(" ").trim()

        val query = if (year != null && !s.contains(year)) "$s $year".trim() else s
        return Cleaned(query, year)
    }
}
