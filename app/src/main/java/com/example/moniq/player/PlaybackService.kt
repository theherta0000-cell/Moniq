package com.example.moniq.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.moniq.R

class PlaybackService : Service() {
    
    private val channelId = "moniq_playback_channel"
    
    override fun onCreate() {
        super.onCreate()
        Log.d("PlaybackService", "onCreate() called")
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
                Log.d("PlaybackService", "Notification channel created")
            } catch (e: Exception) {
                Log.e("PlaybackService", "Failed to create notification channel", e)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("PlaybackService", "onStartCommand() called")
        
        try {
            // Build notification immediately
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_channel_name))
                .setContentText("Playing")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            Log.d("PlaybackService", "About to call startForeground()")
            startForeground(9999, notification)
            Log.d("PlaybackService", "startForeground() completed successfully")
            
        } catch (e: Exception) {
            Log.e("PlaybackService", "CRITICAL: Failed in onStartCommand", e)
            // Try to stop gracefully if startForeground fails
            stopSelf()
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d("PlaybackService", "onDestroy() called")
        try { 
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e("PlaybackService", "Failed to stop foreground", e)
        }
        super.onDestroy()
    }
}