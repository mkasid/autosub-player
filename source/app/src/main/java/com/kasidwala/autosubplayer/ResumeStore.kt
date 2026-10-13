package com.kasidwala.autosubplayer

import android.content.Context

/** Remembers playback position per stream URI in SharedPreferences. */
class ResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    data class Mark(val positionMs: Long, val durationMs: Long, val title: String)

    private fun base(uri: String) = "r_" + uri.hashCode().toString(16)

    fun save(uri: String, positionMs: Long, durationMs: Long, title: String) {
        val b = base(uri)
        prefs.edit()
            .putLong(b + "_p", positionMs)
            .putLong(b + "_d", durationMs)
            .putString(b + "_t", title)
            .apply()
    }

    fun load(uri: String): Mark? {
        val b = base(uri)
        if (!prefs.contains(b + "_p")) return null
        return Mark(
            prefs.getLong(b + "_p", 0),
            prefs.getLong(b + "_d", 0),
            prefs.getString(b + "_t", "") ?: ""
        )
    }

    fun clear(uri: String) {
        val b = base(uri)
        prefs.edit().remove(b + "_p").remove(b + "_d").remove(b + "_t").apply()
    }
}
