package com.kasidwala.autosubplayer

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Launcher / settings screen: OpenSubtitles credentials + TiviMate how-to. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("autosub", Context.MODE_PRIVATE)
        val etKey = findViewById<EditText>(R.id.et_api_key)
        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val etUa = findViewById<EditText>(R.id.et_ua)
        val etReferer = findViewById<EditText>(R.id.et_referer)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        etKey.setText(prefs.getString("api_key", ""))
        etUser.setText(prefs.getString("os_user", ""))
        etPass.setText(prefs.getString("os_pass", ""))
        etUa.setText(prefs.getString("custom_ua", ""))
        etReferer.setText(prefs.getString("custom_referer", ""))
        if (!prefs.getString("api_key", "").isNullOrBlank()) {
            tvStatus.text = "API key saved. You can close this screen."
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            try {
                prefs.edit()
                    .putString("api_key", etKey.text.toString().trim())
                    .putString("os_user", etUser.text.toString().trim())
                    .putString("os_pass", etPass.text.toString())
                    .putString("custom_ua", etUa.text.toString().trim())
                    .putString("custom_referer", etReferer.text.toString().trim())
                    .remove("os_token") // force re-login with new creds
                    .apply()
                tvStatus.text = getString(R.string.saved)
                Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                tvStatus.text = "Save failed: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }
}
