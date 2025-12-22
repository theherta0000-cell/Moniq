package com.example.moniq

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.example.moniq.player.AudioPlayer
import com.google.android.material.slider.Slider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                // Persist permission for the picked tree so app can write there later
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val s = uri.toString()
                com.example.moniq.SessionStore.saveDownloadDirectory(this, s)
                updateDownloadValue(s)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply saved theme mode on startup
        try {
            when (com.example.moniq.SessionStore.loadThemeMode(this, 0)) {
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else ->
                    AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    )
            }
        } catch (_: Exception) {}
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val speedLabel = findViewById<TextView>(R.id.settings_speed_label)
        val speedSlider = findViewById<Slider>(R.id.settings_speed_slider)

        // initialize from persisted value
        val initial = com.example.moniq.SessionStore.loadPlaybackSpeed(this, 1.0f)
        speedSlider.value = (initial * 100f)
        speedLabel.text = String.format("Speed: %.2fx", initial)

        speedSlider.addOnChangeListener { _, value, _ ->
            val speed = value / 100f
            speedLabel.text = String.format("Speed: %.2fx", speed)
            AudioPlayer.initialize(this)
            AudioPlayer.setPlaybackSpeed(speed)
            com.example.moniq.SessionStore.savePlaybackSpeed(this, speed)
        }

        // Lyrics display settings
        val romanizationSwitch =
            findViewById<androidx.appcompat.widget.SwitchCompat>(
                R.id.settings_show_romanization_switch
            )
        val translationSwitch =
            findViewById<androidx.appcompat.widget.SwitchCompat>(
                R.id.settings_show_translation_switch
            )

        // Load saved preferences
        romanizationSwitch.isChecked =
            com.example.moniq.SessionStore.loadShowRomanization(this, true)
        translationSwitch.isChecked = com.example.moniq.SessionStore.loadShowTranslation(this, true)

        romanizationSwitch.setOnCheckedChangeListener { _, isChecked ->
            com.example.moniq.SessionStore.saveShowRomanization(this, isChecked)
        }

        translationSwitch.setOnCheckedChangeListener { _, isChecked ->
            com.example.moniq.SessionStore.saveShowTranslation(this, isChecked)
        }

        // Disconnect button: clear stored credentials and return to login
        val disconnect = findViewById<android.widget.Button>(R.id.settings_disconnect_button)
        disconnect.setOnClickListener {
            try {
                SessionStore.clear(this)
            } catch (_: Exception) {}
            val it = Intent(this, LoginActivity::class.java)
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(it)
        }

        // Download directory picker
        val downloadValue = findViewById<TextView>(R.id.settings_download_value)
        val chooseButton = findViewById<android.widget.Button>(R.id.settings_choose_download_button)

        val current = com.example.moniq.SessionStore.loadDownloadDirectory(this)
        updateDownloadValue(current)

        chooseButton.setOnClickListener { pickDirLauncher.launch(null) }

        // Theme toggle: cycles System -> Light -> Dark
        val themeBtn = findViewById<android.widget.Button>(R.id.settings_theme_button)
        fun modeLabel(m: Int): String =
            when (m) {
                1 -> "Light"
                2 -> "Dark"
                else -> "System"
            }
        try {
            var mode = com.example.moniq.SessionStore.loadThemeMode(this, 0)
            themeBtn.text = modeLabel(mode)
            themeBtn.setOnClickListener {
                mode = ((mode + 1) % 3)
                com.example.moniq.SessionStore.saveThemeMode(this, mode)
                themeBtn.text = modeLabel(mode)
                try {
                    when (mode) {
                        1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        else ->
                            AppCompatDelegate.setDefaultNightMode(
                                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            )
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Last.fm integration
        val lastfmStatus = findViewById<TextView>(R.id.settings_lastfm_status)
        val lastfmLoginBtn = findViewById<android.widget.Button>(R.id.settings_lastfm_login_button)
        val lastfmLogoutBtn =
            findViewById<android.widget.Button>(R.id.settings_lastfm_logout_button)

        fun updateLastFmStatus() {
            val session = com.example.moniq.SessionStore.loadLastFmSession(this)
            if (session != null) {
                lastfmStatus.text = "Connected as ${session.first}"
                lastfmLoginBtn.isEnabled = false
                lastfmLogoutBtn.isEnabled = true
            } else {
                lastfmStatus.text = "Not connected"
                lastfmLoginBtn.isEnabled = true
                lastfmLogoutBtn.isEnabled = false
            }
        }

        updateLastFmStatus()

        lastfmLoginBtn.setOnClickListener {
            val container =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 32, 32, 32)
                }

            val usernameInput =
                android.widget.EditText(this).apply {
                    hint = "Last.fm username"
                    setSingleLine(true)
                }
            val passwordInput =
                android.widget.EditText(this).apply {
                    hint = "Last.fm password"
                    setSingleLine(true)
                    inputType =
                        android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }

            container.addView(usernameInput)
            container.addView(passwordInput)

            com.google.android.material.dialog
                .MaterialAlertDialogBuilder(this)
                .setTitle("Connect to Last.fm")
                .setView(container)
                .setPositiveButton("Connect") { _, _ ->
                    val username = usernameInput.text.toString().trim()
                    val password = passwordInput.text.toString()

                    if (username.isBlank() || password.isBlank()) {
                        android.widget.Toast.makeText(
                                this,
                                "Please enter username and password",
                                android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                        return@setPositiveButton
                    }

                    lifecycleScope.launch {
                        val progressDialog =
                            com.google.android.material.dialog
                                .MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("Connecting...")
                                .setView(android.widget.ProgressBar(this@SettingsActivity))
                                .setCancelable(false)
                                .show()

                        val success =
                            com.example.moniq.lastfm.LastFmManager.authenticate(
                                this@SettingsActivity,
                                username,
                                password
                            )

                        progressDialog.dismiss()

                        if (success) {
                            android.widget.Toast.makeText(
                                    this@SettingsActivity,
                                    "Connected to Last.fm!",
                                    android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                            updateLastFmStatus()
                        } else {
                            android.widget.Toast.makeText(
                                    this@SettingsActivity,
                                    "Failed to connect. Check your credentials.",
                                    android.widget.Toast.LENGTH_LONG
                                )
                                .show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        lastfmLogoutBtn.setOnClickListener {
            com.example.moniq.lastfm.LastFmManager.logout(this)
            android.widget.Toast.makeText(
                    this,
                    "Disconnected from Last.fm",
                    android.widget.Toast.LENGTH_SHORT
                )
                .show()
            updateLastFmStatus()
        }
    }

    private fun updateDownloadValue(uriString: String?) {
        val downloadValue = findViewById<TextView>(R.id.settings_download_value)
        if (uriString == null) {
            downloadValue.text = "Default"
        } else {
            // show a short friendly representation
            try {
                val uri = Uri.parse(uriString)
                val last = uri.path?.split('/')?.lastOrNull() ?: uriString
                downloadValue.text = last
            } catch (_: Exception) {
                downloadValue.text = uriString
            }
        }
    }
}
