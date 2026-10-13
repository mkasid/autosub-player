package com.kasidwala.autosubplayer

import android.app.Activity
import android.app.AlertDialog
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Diagnostic aid: catches any uncaught crash and shows the stack trace in a
 * dialog instead of silently closing, so the exact failure is visible on the
 * Fire TV stick. Temporary — remove once the launch crashes are fixed.
 */
object CrashGuard {
    fun install(activity: Activity) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val trace = sw.toString().lineSequence().take(20).joinToString("\n")
                val msg = "${e.javaClass.name}: ${e.message}\n\n$trace"
                activity.runOnUiThread {
                    try {
                        AlertDialog.Builder(activity)
                            .setTitle("AutoSub crashed")
                            .setMessage(msg)
                            .setPositiveButton("Close") { _, _ ->
                                activity.finish()
                            }
                            .setCancelable(false)
                            .show()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            // Keep the process alive so the dialog stays readable.
        }
    }
}
