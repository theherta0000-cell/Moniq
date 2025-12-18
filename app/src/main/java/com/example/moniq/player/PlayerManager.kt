package com.example.moniq.player

/*

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.example.moniq.SessionManager
import com.example.moniq.R
import com.example.moniq.util.Crypto
import java.net.URLEncoder

/**
 * Centralized ExoPlayer manager used by activities to start playback.
 * Exposes simple APIs: initialize, playTrack, playStream, pause/resume/toggle, release
 * Also exposes LiveData for `currentTitle`, `currentArtist`, and `isPlaying`.
 */
object PlayerManager {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var appContext: Context? = null

    val currentTitle = MutableLiveData<String?>(null)
    val currentArtist = MutableLiveData<String?>(null)
    val isPlaying = MutableLiveData(false)

    fun initialize(context: Context) {
        package com.example.moniq.player

        import android.content.Context
        import android.app.NotificationChannel
        import android.app.NotificationManager
        import androidx.lifecycle.MutableLiveData
        import androidx.media3.common.MediaItem
        import androidx.media3.common.MediaMetadata
        import androidx.media3.common.AudioAttributes
        import androidx.media3.common.C
        import androidx.media3.common.Player
        import androidx.media3.exoplayer.ExoPlayer
        import androidx.media3.session.MediaSession
        import androidx.media3.ui.PlayerNotificationManager
        import com.example.moniq.SessionManager
        import com.example.moniq.R
        import com.example.moniq.util.Crypto
        import java.net.URLEncoder

        /**
         * Central ExoPlayer manager: provide a stable API used by activities.
         */
        object PlayerManager {
            private var player: ExoPlayer? = null
            private var mediaSession: MediaSession? = null
            private var playerNotificationManager: PlayerNotificationManager? = null
            private var appContext: Context? = null

            val currentTitle = MutableLiveData<String?>(null)
            val currentArtist = MutableLiveData<String?>(null)
            val isPlaying = MutableLiveData(false)

            fun initialize(context: Context) {
                if (player != null) return
                val ctx = context.applicationContext
                appContext = ctx
                player = ExoPlayer.Builder(ctx).build()
                player?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )

                try { mediaSession = MediaSession.Builder(ctx, player!!).build() } catch (_: Exception) {}

                try {
                    val channelId = "moniq_playback_channel"
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val ch = NotificationChannel(channelId, ctx.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
                        ch.description = ctx.getString(R.string.notification_channel_description)
                        nm.createNotificationChannel(ch)
                    }
                    val builder = PlayerNotificationManager.Builder(ctx, 1, channelId)
                        .setChannelNameResourceId(R.string.notification_channel_name)
                        .setChannelDescriptionResourceId(R.string.notification_channel_description)
                        .setSmallIconResourceId(R.mipmap.ic_launcher)
                    playerNotificationManager = builder.build()
                    playerNotificationManager?.setPlayer(player)
                } catch (_: Exception) {}

                player?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingNow: Boolean) { isPlaying.postValue(isPlayingNow) }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        try { val meta = mediaItem?.mediaMetadata; currentTitle.postValue(meta?.title?.toString()); currentArtist.postValue(meta?.artist?.toString()) } catch (_: Exception) {}
                    }
                })
            }

            fun playTrack(context: Context, trackId: String, title: String? = null, artist: String? = null) {
                initialize(context)
                val host = SessionManager.host ?: return
                val username = SessionManager.username ?: ""
                val passwordRaw = SessionManager.password ?: ""
                val legacy = SessionManager.legacy
                val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)

                var base = host.trim()
                if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
                if (!base.endsWith("/")) base += "/"

                val qU = URLEncoder.encode(username, "UTF-8")
                val qP = URLEncoder.encode(pwParam, "UTF-8")
                val qId = URLEncoder.encode(trackId, "UTF-8")

                val url = "${base}rest/stream.view?u=$qU&p=$qP&id=$qId&v=1.16.1&c=Moniq"
                playStream(context, url, title, artist, null)
            }

            fun playTrack(trackId: String, title: String? = null, artist: String? = null) {
                val ctx = appContext ?: return
                playTrack(ctx, trackId, title, artist)
            }

            fun playStream(context: Context, url: String, title: String? = null, artist: String? = null, albumArt: String? = null) {
                initialize(context)
                player?.apply {
                    val meta = MediaMetadata.Builder().apply {
                        if (!title.isNullOrEmpty()) setTitle(title)
                        if (!artist.isNullOrEmpty()) setArtist(artist)
                        if (!albumArt.isNullOrEmpty()) setArtworkUri(android.net.Uri.parse(albumArt))
                    }.build()
                    val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(meta).build()
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                    currentTitle.postValue(title)
                    currentArtist.postValue(artist)
                }
            }

            fun playStream(url: String, title: String? = null, artist: String? = null, albumArt: String? = null) {
                val ctx = appContext ?: return
                playStream(ctx, url, title, artist, albumArt)
            }

            fun togglePlayPause() { val p = player ?: return; if (p.isPlaying) p.pause() else p.play() }
            fun pause() { player?.pause() }
            fun resume() { player?.play() }

            fun release() {
                try { playerNotificationManager?.setPlayer(null) } catch (_: Exception) {}
                playerNotificationManager = null
                try { mediaSession?.release() } catch (_: Exception) {}
                mediaSession = null
                try { player?.release() } catch (_: Exception) {}
                player = null
            }
        }
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus= */ true
        )
        // If an ended callback was already registered, ensure the listener is attached
        if (endedCallback != null) {
            player?.removeListener(internalListener)
            player?.addListener(internalListener)
        }
        // init media session + notification manager once when player created
        if (mediaSession == null) {
            try {
                mediaSession = MediaSession.Builder(context, player!!).build()
            } catch (_: Exception) {}
        }
        // Intentionally avoid MediaSessionConnector to maintain compatibility across media3 versions
        if (playerNotificationManager == null) {
            try {
                val channelId = "moniq_playback_channel"
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(channelId, context.getString(com.example.moniq.R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
                    ch.description = context.getString(com.example.moniq.R.string.notification_channel_description)
                    nm.createNotificationChannel(ch)
                }
                val builder = PlayerNotificationManager.Builder(context, 1, channelId)
                    .setChannelNameResourceId(com.example.moniq.R.string.notification_channel_name)
                    .setChannelDescriptionResourceId(com.example.moniq.R.string.notification_channel_description)
                    .setSmallIconResourceId(com.example.moniq.R.mipmap.ic_launcher)
                // Attempt to enable previous/next actions at the builder-level if available (reflection)
                try {
                    val bcls = builder.javaClass
                    val setUseNext = bcls.methods.firstOrNull { it.name == "setUseNextAction" }
                    val setUsePrev = bcls.methods.firstOrNull { it.name == "setUsePreviousAction" }
                    if (setUseNext != null) setUseNext.invoke(builder, true)
                    if (setUsePrev != null) setUsePrev.invoke(builder, true)
                    android.util.Log.d("PlayerManager", "Builder actions set: next=${setUseNext!=null} prev=${setUsePrev!=null}")
                } catch (_: Exception) {}
                playerNotificationManager = builder.build()
                playerNotificationManager?.setPlayer(player)
                // Try to enable previous/next actions via reflection for compatibility (instance-level)
                try {
                    val cls = playerNotificationManager!!.javaClass
                    val setUseNext = cls.methods.firstOrNull { it.name == "setUseNextAction" }
                    val setUsePrev = cls.methods.firstOrNull { it.name == "setUsePreviousAction" }
                    if (setUseNext != null) {
                        setUseNext.invoke(playerNotificationManager, true)
                        android.util.Log.d("PlayerManager", "Set instance next action via reflection")
                    }
                    if (setUsePrev != null) {
                        setUsePrev.invoke(playerNotificationManager, true)
                        android.util.Log.d("PlayerManager", "Set instance previous action via reflection")
                    }
                    try {
                        val setUseFast = cls.methods.firstOrNull { it.name == "setUseFastForwardAction" }
                        val setUseRewind = cls.methods.firstOrNull { it.name == "setUseRewindAction" }
                        setUseFast?.invoke(playerNotificationManager, false)
                        setUseRewind?.invoke(playerNotificationManager, false)
                        android.util.Log.d("PlayerManager", "Set instance fast/rewind actions to false via reflection")
                    } catch (_: Exception) {}
                    try {
                        val setChrono = cls.methods.firstOrNull { it.name == "setUseChronometer" }
                        setChrono?.invoke(playerNotificationManager, true)
                        android.util.Log.d("PlayerManager", "Enabled chronometer on notification via reflection")
                    package com.example.moniq.player

                    // PlayerManager was removed (corrupted). Use `AudioPlayer` instead.
        player = null
    }
    
    fun getPosition(): Long {
        return player?.currentPosition ?: 0L
    }
    
    fun getDuration(): Long {
        return player?.duration ?: 0L
    }
    
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }
    
    fun setPlaybackParameters(speed: Float, pitch: Float) {
        try {
            player?.playbackParameters = PlaybackParameters(speed, pitch)
        } catch (_: Exception) {
        }
    }
    
    fun setOnPlaybackEnded(cb: (() -> Unit)?) {
        endedCallback = cb
        player?.let { p ->
            p.removeListener(internalListener)
            p.addListener(internalListener)
        }
    }

    fun setOnMediaItemIndexChanged(cb: ((Int) -> Unit)?) {
        indexChangedCallback = cb
    }
    
    private val internalListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                try {
                    endedCallback?.invoke()
                } catch (_: Exception) {}
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            try {
                val idx = player?.currentMediaItemIndex ?: -1
                indexChangedCallback?.invoke(idx)
            } catch (_: Exception) {}
        }
    }
}

*/