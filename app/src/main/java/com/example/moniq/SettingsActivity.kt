package com.example.moniq

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.slider.Slider
import android.widget.TextView
import com.example.moniq.player.AudioPlayer

class SettingsActivity : ComponentActivity() {
    private val pickDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
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

        chooseButton.setOnClickListener {
            pickDirLauncher.launch(null)
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
