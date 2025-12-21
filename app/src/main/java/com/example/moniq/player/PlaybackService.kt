package com.example.moniq.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.moniq.R

class PlaybackService : Service() {
    
    private val channelId = "moniq_playback_channel"
    
    override fun onCreate() {
        super.onCreate()
        // Create channel ONCE when service is created, not every time it starts
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    channelId,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = getString(R.string.notification_channel_description)
                nm.createNotificationChannel(ch)
            } catch (_: Exception) {}
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Build notification immediately
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_channel_name))
            .setContentText("")
            .setOngoing(true)
            .build()
        
        // Call startForeground IMMEDIATELY - no try/catch to hide errors
        startForeground(9999, notification)
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        try { 
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}