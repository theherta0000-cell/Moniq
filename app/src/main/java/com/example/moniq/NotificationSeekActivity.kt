package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.os.Handler
import android.os.Looper

class NotificationSeekActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This activity was previously a seek UI for notifications.
        // The app now uses a unified compact notification that opens the Queue.
        // Forward users to the QueueActivity to keep behavior consistent.
        try {
            val intent = android.content.Intent(this, com.example.moniq.QueueActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (_: Exception) {}
        finish()
    }
}
