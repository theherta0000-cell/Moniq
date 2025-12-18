package com.example.moniq.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TOGGLE_PLAY = "com.example.moniq.action.TOGGLE_PLAY"
        const val ACTION_PREV = "com.example.moniq.action.PREV"
        const val ACTION_NEXT = "com.example.moniq.action.NEXT"
        const val ACTION_SEEK_FORWARD = "com.example.moniq.action.SEEK_FORWARD"
        const val ACTION_SEEK_BACK = "com.example.moniq.action.SEEK_BACK"
        const val ACTION_OPEN_QUEUE = "com.example.moniq.action.OPEN_QUEUE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            when (intent?.action) {
                ACTION_TOGGLE_PLAY -> com.example.moniq.player.AudioPlayer.togglePlayPause()
                ACTION_PREV -> com.example.moniq.player.AudioPlayer.previous()
                ACTION_NEXT -> com.example.moniq.player.AudioPlayer.next()
                ACTION_SEEK_FORWARD -> com.example.moniq.player.AudioPlayer.seekRelative(15000L)
                ACTION_SEEK_BACK -> com.example.moniq.player.AudioPlayer.seekRelative(-15000L)
                ACTION_OPEN_QUEUE -> {
                    try {
                        val ctx = context ?: return
                        val i = android.content.Intent(ctx, com.example.moniq.QueueActivity::class.java)
                        i.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        ctx.startActivity(i)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }
}
