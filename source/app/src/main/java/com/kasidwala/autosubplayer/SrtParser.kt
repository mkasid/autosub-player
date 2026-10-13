package com.kasidwala.autosubplayer

import java.io.File

/** Minimal SRT parser for sidecar subtitle rendering (no stream re-request needed). */
object SrtParser {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    fun parse(file: File): List<Cue> {
        val cues = mutableListOf<Cue>()
        return try {
            val blocks = file.readText().split(Regex("\r?\n\r?\n"))
            for (block in blocks) {
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size < 2) continue
                val timeIdx = lines.indexOfFirst { it.contains("-->") }
                if (timeIdx < 0) continue
                val parts = lines[timeIdx].split("-->")
                if (parts.size != 2) continue
                val start = parseTime(parts[0].trim()) ?: continue
                val end = parseTime(parts[1].trim()) ?: continue
                val text = lines.drop(timeIdx + 1).joinToString("\n").trim()
                if (text.isNotEmpty() && end > start) {
                    cues.add(Cue(start, end, text))
                }
            }
            cues
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** "00:01:23,456" or "00:01:23.456" -> ms. */
    private fun parseTime(s: String): Long? {
        val m = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)").find(s) ?: return null
        val (h, min, sec, frac) = m.destructured
        return try {
            val ms = frac.padEnd(3, '0').take(3).toLong()
            h.toLong() * 3600000 + min.toLong() * 60000 + sec.toLong() * 1000 + ms
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun activeCue(cues: List<Cue>, positionMs: Long): Cue? =
        cues.firstOrNull { positionMs >= it.startMs && positionMs <= it.endMs }
}
