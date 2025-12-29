    package com.example.moniq.player

    import android.app.NotificationChannel
    import android.app.NotificationManager
    import android.content.Context
    import android.util.Log
    import android.widget.RemoteViews
    import androidx.lifecycle.MutableLiveData
    import androidx.media3.common.AudioAttributes
    import androidx.media3.common.C
    import androidx.media3.common.MediaItem
    import androidx.media3.common.MediaMetadata
    import androidx.media3.common.PlaybackParameters
    import androidx.media3.common.Player
    import androidx.media3.exoplayer.ExoPlayer
    import androidx.media3.session.MediaSession
    import androidx.media3.ui.PlayerNotificationManager
    import com.example.moniq.R
    import com.example.moniq.SessionManager
    import com.example.moniq.util.Crypto
    import com.example.moniq.util.MetadataFetcher
    import com.example.moniq.util.StreamUrlResolver
    import java.net.URLEncoder
    import kotlinx.coroutines.*
    import kotlinx.coroutines.isActive
    import kotlinx.coroutines.withTimeout
    import kotlinx.coroutines.TimeoutCancellationException
    import androidx.media3.exoplayer.dash.DashMediaSource
    import androidx.media3.datasource.DefaultDataSource
    import com.example.moniq.util.ServerManager 
    import kotlinx.coroutines.cancelChildren


    object AudioPlayer {
        private var player: ExoPlayer? = null
        private var mediaSession: MediaSession? = null
        private var playerNotificationManager: PlayerNotificationManager? = null
        private var appContext: Context? = null
        var currentTrackId: String? = null
        private var queue: MutableList<MediaItem> = mutableListOf()
        val queueTracks = MutableLiveData<List<com.example.moniq.model.Track>>(emptyList())
        val currentQueueIndex = MutableLiveData<Int>(0)
        private var scrobbleJob: Job? = null
        private var coroutineScope: CoroutineScope? = null

        // notification helpers
        private var notificationManager: NotificationManager? = null

        val currentTitle = MutableLiveData<String?>(null)
        val currentArtist = MutableLiveData<String?>(null)
        val currentAlbumArt = MutableLiveData<String?>(null)
        val currentAlbumName = MutableLiveData<String?>(null)
        val currentAlbumId = MutableLiveData<String?>(null)
        val currentTrackQuality = MutableLiveData<String?>(null)
    val currentBitDepth = MutableLiveData<Int?>(null)
    val currentSampleRate = MutableLiveData<Int?>(null)
    val currentDominantColor = MutableLiveData<Int?>(null)
    val isPlaying = MutableLiveData(false)
    val playbackSpeed = MutableLiveData<Float>(1.0f)
    val playbackState = MutableLiveData<Int>(Player.STATE_IDLE)
    private var lastNotifiedArtSrc: String? = null
        private var lastNotifiedBitmap: android.graphics.Bitmap? = null

        private data class TrackMetadata(
        val audioQuality: String,
        val bitDepth: Int?,
        val sampleRate: Int?,
        val albumId: String?,
        val albumTitle: String?
    )

        fun initialize(context: Context) {
        if (player != null) return
        val ctx = context.applicationContext
        appContext = ctx

            StreamUrlResolver.initialize(ctx)

        // Create a managed coroutine scope
        if (coroutineScope == null) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }
        
    // Build ExoPlayer WITH DASH support (handles both file:// and https://)
    // Build ExoPlayer with default media source (handles all formats including DASH)
    player = ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
                .setDataSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(
                        ctx,
                        androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .setConnectTimeoutMs(10000)
                            .setReadTimeoutMs(15000)
                    )
                )
        )
        .build()
            
        player?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build(),
            true
        )

            // Load Last.fm session
            try {
                com.example.moniq.lastfm.LastFmManager.loadSession(ctx)
            } catch (_: Exception) {}

            try {
                mediaSession = MediaSession.Builder(ctx, player!!).build()
            } catch (_: Exception) {}

            try {
                val channelId = "moniq_playback_channel"
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // store for later updates to custom big view
                notificationManager = nm
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val ch =
                            NotificationChannel(
                                    channelId,
                                    ctx.getString(R.string.notification_channel_name),
                                    NotificationManager.IMPORTANCE_LOW
                            )
                    ch.description = ctx.getString(R.string.notification_channel_description)
                    nm.createNotificationChannel(ch)
                }
                val adapter =
                        object : PlayerNotificationManager.MediaDescriptionAdapter {
                            override fun getCurrentContentTitle(player: Player): CharSequence {
                                return currentTitle.value ?: ""
                            }

                            override fun createCurrentContentIntent(
                                    player: Player
                            ): android.app.PendingIntent? {
                                return try {
                                    val intent =
                                            android.content.Intent(
                                                    appContext,
                                                    com.example.moniq.NotificationSeekActivity::class
                                                            .java
                                            )
                                    intent.flags =
                                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    android.app.PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            intent,
                                            android.app.PendingIntent.FLAG_IMMUTABLE or
                                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    )
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            override fun getCurrentContentText(player: Player): CharSequence? {
                                val artist = currentArtist.value ?: ""
                                val album = currentAlbumName.value ?: ""

                                // Build artist/album text
                                val metadata =
                                        when {
                                            artist.isNotBlank() && album.isNotBlank() ->
                                                    "$artist • $album"
                                            artist.isNotBlank() -> artist
                                            album.isNotBlank() -> album
                                            else -> ""
                                        }

                                // Add time if available
                                try {
                                    val pos = this@AudioPlayer.player?.currentPosition ?: 0L
                                    val dur = this@AudioPlayer.player?.duration ?: 0L
                                    if (dur > 0L) {
                                        fun fmt(ms: Long): String {
                                            val s = (ms / 1000L).toInt()
                                            val m = s / 60
                                            val sec = s % 60
                                            return String.format("%02d:%02d", m, sec)
                                        }
                                        val timeText = "${fmt(pos)} / ${fmt(dur)}"
                                        return if (metadata.isNotBlank()) "$metadata  •  $timeText"
                                        else timeText
                                    }
                                } catch (_: Exception) {}

                                return metadata.ifBlank { null }
                            }

                            override fun getCurrentLargeIcon(
                                    player: Player,
                                    callback: PlayerNotificationManager.BitmapCallback
                            ): android.graphics.Bitmap? {
                                try {
                                    val artUrl =
                                            currentAlbumArt.value?.takeIf { it.isNotBlank() }
                                                    ?: run {
                                                        val id = currentTrackId ?: return null
                                                        val host = SessionManager.host ?: return null
                                                        if (id.startsWith("http")) return null
                                                        android.net.Uri.parse(host)
                                                                .buildUpon()
                                                                .appendPath("rest")
                                                                .appendPath("getCoverArt.view")
                                                                .appendQueryParameter("id", id)
                                                                .appendQueryParameter(
                                                                        "u",
                                                                        SessionManager.username ?: ""
                                                                )
                                                                .appendQueryParameter(
                                                                        "p",
                                                                        SessionManager.password ?: ""
                                                                )
                                                                .build()
                                                                .toString()
                                                    }

                                    Thread {
                                                try {
                                                    java.net.URL(artUrl).openStream().use { stream ->
                                                        val bytes = stream.readBytes()
                                                        val bmp =
                                                                android.graphics.BitmapFactory
                                                                        .decodeByteArray(
                                                                                bytes,
                                                                                0,
                                                                                bytes.size
                                                                        )
                                                        if (bmp != null) callback.onBitmap(bmp)
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                            .start()
                                } catch (_: Exception) {}
                                return null
                            }
                        }

                val builder =
                        PlayerNotificationManager.Builder(ctx, 1, channelId)
                                .setChannelNameResourceId(R.string.notification_channel_name)
                                .setChannelDescriptionResourceId(
                                        R.string.notification_channel_description
                                )
                                .setSmallIconResourceId(R.mipmap.ic_launcher)
                                .setMediaDescriptionAdapter(adapter)
                playerNotificationManager = builder.build()
                // Attach the ExoPlayer so PlayerNotificationManager manages the media notification
                try {
                    playerNotificationManager?.setPlayer(player)
                    // Force notification updates during playback for progress/time display
                    playerNotificationManager?.setUseChronometer(true)
                    playerNotificationManager?.setUsePreviousAction(false)
                    playerNotificationManager?.setUseNextAction(false)
                    playerNotificationManager?.setUseStopAction(false)
                } catch (_: Exception) {}
            } catch (_: Exception) {}

        player?.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
    playbackState.postValue(state)
    
    when (state) {
        Player.STATE_READY -> {
            // ✅ FIX: Duration is now available after DASH parsing
            val dur = player?.duration ?: 0L
            if (dur > 0 && dur != C.TIME_UNSET) {
                Log.d("AudioPlayer", "onPlaybackStateChanged: Duration now available: ${dur}ms (${dur/1000}s)")
                // Update stored duration with correct value
                try {
                    val ctx = appContext
                    if (ctx != null && currentTrackId != null) {
                        com.example.moniq.SessionStore.saveLastTrack(
                            ctx,
                            currentTrackId,
                            player?.currentPosition ?: 0L,
                            currentTitle.value,
                            currentArtist.value,
                            currentAlbumArt.value,
                            player?.isPlaying == true,
                            currentAlbumId.value,
                            currentAlbumName.value,
                            dur
                        )
                    }
                } catch (_: Exception) {}
            }
        }
        Player.STATE_ENDED -> {
            // ✅ Check if this is the LAST track in the queue
            val currentIdx = player?.currentMediaItemIndex ?: -1
            val queueSize = queue.size
            
            Log.d("AudioPlayer", "STATE_ENDED: currentIdx=$currentIdx, queueSize=$queueSize")
            
            // Only stop service if we've reached the end of the entire queue
            if (currentIdx >= queueSize - 1) {
                Log.d("AudioPlayer", "Queue finished, stopping service")
                isPlaying.postValue(false)
                try {
                    val ctx = appContext
                    if (ctx != null) {
                        val svc = android.content.Intent(ctx, PlaybackService::class.java)
                        ctx.stopService(svc)
                    }
                } catch (_: Exception) {}
            } else {
                // Track ended but queue continues - ExoPlayer will auto-advance
                Log.d("AudioPlayer", "Track ended, but queue continues (next track will play)")
            }
        }
    }
}

    private var errorRetryCount = mutableMapOf<String, Int>() // Add this at class level

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
    android.util.Log.e("AudioPlayer", "Playback error: ${error.message}", error)
    android.util.Log.e("AudioPlayer", "Error code: ${error.errorCode}")
    android.util.Log.e("AudioPlayer", "Cause: ${error.cause?.message}", error.cause)
    
    // ✅ Auto-retry on HTTP 403/404 errors (expired URLs)
    if (error.cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
        val httpError = error.cause as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
        if (httpError.responseCode == 404 || httpError.responseCode == 403) {
            android.util.Log.w("AudioPlayer", "🔄 Got ${httpError.responseCode} error, attempting to re-resolve URL...")
            
            coroutineScope?.launch {
                try {
                    val currentIdx = player?.currentMediaItemIndex ?: return@launch
                    val currentTrack = queueTracks.value?.getOrNull(currentIdx)
                    
                    if (currentTrack != null) {
                        val trackId = currentTrack.id
                        
                        // ✅ Check retry count to prevent infinite loops
                        val retries = errorRetryCount.getOrDefault(trackId, 0)
                        if (retries >= 3) {
                            android.util.Log.e("AudioPlayer", "❌ Max retries (3) exceeded for track $trackId, skipping...")
                            errorRetryCount.remove(trackId)
                            
                            withContext(Dispatchers.Main) {
                                appContext?.let { ctx ->
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "Unable to play track, skipping...",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                try {
                                    player?.seekToNextMediaItem()
                                } catch (_: Exception) {}
                            }
                            return@launch
                        }
                        
                        errorRetryCount[trackId] = retries + 1
                        android.util.Log.d("AudioPlayer", "Re-resolving track: ${currentTrack.title} (attempt ${retries + 1}/3)")
                        
                        // ✅ Build new media item with forceFresh=true
                        val newItem = buildMediaItemForTrack(currentTrack, forceFresh = true)
                        
                        withContext(Dispatchers.Main) {
                            try {
                                // Update queue
                                queue[currentIdx] = newItem
                                
                                player?.replaceMediaItem(currentIdx, newItem)
                                player?.prepare()
                                player?.play()
                                
                                android.util.Log.d("AudioPlayer", "Successfully recovered from ${httpError.responseCode} error")
                                
                                // Show user feedback
                                appContext?.let { ctx ->
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "Retrying track... (${retries + 1}/3)",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("AudioPlayer", "Failed to recover from ${httpError.responseCode}", e)
                                
                                // If retry fails, skip to next track
                                errorRetryCount.remove(trackId)
                                try {
                                    player?.seekToNextMediaItem()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayer", "Error during ${httpError.responseCode} recovery", e)
                }
            }
        }
    }
}
                
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
    isPlaying.postValue(isPlayingNow)
    try {
        val ctx = appContext
        if (ctx != null && isPlayingNow) {
            // Only start service when playing begins
            val svc = android.content.Intent(ctx, PlaybackService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc)
                } else {
                    ctx.startService(svc)
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "Failed to start service", e)
            }
        }
        // Don't stop service when pausing - let it manage itself
    } catch (_: Exception) {}
                            try {
                                val ctx = appContext
                                if (ctx != null) {
                                    com.example.moniq.SessionStore.saveLastTrack(
            ctx,
            currentTrackId,
            player?.currentPosition ?: 0L,
            currentTitle.value,
            currentArtist.value,
            currentAlbumArt.value,
            isPlayingNow,
            currentAlbumId.value,
            currentAlbumName.value,  
            player?.duration ?: 0L
    )
                                }
                            } catch (_: Exception) {}
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            try {
            val trackId = mediaItem?.localConfiguration?.tag as? String
            if (trackId != null) {
                errorRetryCount.remove(trackId)
            }
        } catch (_: Exception) {}
        val dur = player?.duration ?: 0L
        Log.d("AudioPlayer", "onMediaItemTransition: duration from player = ${dur}ms (${dur/1000}s)")
        
        try {
            val meta = mediaItem?.mediaMetadata
            currentTitle.postValue(meta?.title?.toString())
            currentArtist.postValue(meta?.artist?.toString())
            currentAlbumArt.postValue(meta?.artworkUri?.toString())
            val albumTitle =
                    meta?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                            ?: mediaItem?.mediaMetadata?.extras?.getString(
                                    "albumName"
                            )
            currentAlbumName.postValue(albumTitle)
            try {
                val art = meta?.artworkUri?.toString()
                if (!art.isNullOrBlank()) {
                    Thread {
                                try {
                                    val conn = java.net.URL(art).openConnection()
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 3000
                                    val bytes = conn.getInputStream().readBytes()
                                    val bmp =
                                            android.graphics.BitmapFactory
                                                    .decodeByteArray(
                                                            bytes,
                                                            0,
                                                            bytes.size
                                                    )
                                    if (bmp != null) {
                                        val small =
                                                android.graphics.Bitmap
                                                        .createScaledBitmap(
                                                                bmp,
                                                                20,
                                                                20,
                                                                true
                                                        )
                                        var r = 0L
                                        var g = 0L
                                        var b = 0L
                                        var cnt = 0L
                                        for (x in 0 until small.width) for (y in
                                                0 until small.height) {
                                            val c = small.getPixel(x, y)
                                            r += android.graphics.Color.red(c)
                                            g += android.graphics.Color.green(c)
                                            b += android.graphics.Color.blue(c)
                                            cnt++
                                        }
                                        if (cnt > 0) {
                                            val avg =
                                                    android.graphics.Color.rgb(
                                                            (r / cnt).toInt(),
                                                            (g / cnt).toInt(),
                                                            (b / cnt).toInt()
                                                    )
                                            currentDominantColor.postValue(avg)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            .start()
                } else {
                    currentDominantColor.postValue(null)
                }
            } catch (_: Exception) {}
            currentQueueIndex.postValue(player?.currentMediaItemIndex ?: 0)
            try {
                currentTrackId = mediaItem?.localConfiguration?.tag as? String
            } catch (_: Exception) {}
            try {
                val extras = mediaItem?.mediaMetadata?.extras
                val aid = extras?.getString("albumId")
                if (!aid.isNullOrBlank()) currentAlbumId.postValue(aid)
                else currentAlbumId.postValue(null)
                try {
        val extras = mediaItem?.mediaMetadata?.extras
        val quality = extras?.getString("audioQuality")
        val bitDepth = extras?.getInt("bitDepth", 0) ?: 0
        val sampleRate = extras?.getInt("sampleRate", 0) ?: 0
        
        currentTrackQuality.postValue(quality)
        currentBitDepth.postValue(bitDepth.takeIf { it > 0 })
        currentSampleRate.postValue(sampleRate.takeIf { it > 0 })
    } catch (_: Exception) {}
            } catch (_: Exception) {}
            try {
                val ctx = appContext
                if (ctx != null) {
                    com.example.moniq.SessionStore.saveLastTrack(
                        ctx,
                        currentTrackId,
                        0L,
                        currentTitle.value,
                        currentArtist.value,
                        currentAlbumArt.value,
                        player?.isPlaying == true,
                        currentAlbumId.value,
                        currentAlbumName.value,
                        player?.duration ?: 0L
                    )
                }
            } catch (_: Exception) {}
            try {
                com.example.moniq.lastfm.LastFmManager.resetScrobbleState()
            } catch (_: Exception) {}

            try {
                val title = meta?.title?.toString()
                val artist = meta?.artist?.toString()
                val album =
                        meta?.albumTitle?.toString()
                                ?: mediaItem?.mediaMetadata?.extras?.getString(
                                        "albumName"
                                )
                val trackId =
                        try {
                            mediaItem?.localConfiguration?.tag as? String
                        } catch (_: Exception) {
                            null
                        }

                if (!title.isNullOrBlank() &&
                                !artist.isNullOrBlank() &&
                                trackId != null
                ) {
                    val dur = player?.duration ?: 0L
                    coroutineScope?.launch(Dispatchers.IO) {
                        com.example.moniq.lastfm.LastFmManager.onTrackStart(
                                trackId,
                                title,
                                artist,
                                album,
                                dur
                        )
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        // Don't add to recently played immediately - wait until track actually plays
        // (will be added by scrobble checker after 10 seconds of playback)
        
        preloadNext()

        // ✅ NEW: Add URL validation/retry if current track has placeholder URL
        coroutineScope?.launch {
            try {
                val currentIdx = player?.currentMediaItemIndex ?: return@launch
                val currentItem = queue.getOrNull(currentIdx)
                val currentUrl = currentItem?.localConfiguration?.uri?.toString()
                
                // If current track is a placeholder URL, resolve it immediately
                if (currentUrl?.contains("/stream/?id=") == true) {
                    android.util.Log.w("AudioPlayer", "⚠️ Current track has placeholder URL, resolving now...")
                    
                    val currentTrack = queueTracks.value?.getOrNull(currentIdx)
                    if (currentTrack != null) {
                        val resolvedItem = buildMediaItemForTrack(currentTrack)
                        
                        withContext(Dispatchers.Main) {
                            try {
                                // Get current position before replacing
                                val pos = player?.currentPosition ?: 0L
                                
                                // Replace the item
                                queue[currentIdx] = resolvedItem
                                player?.replaceMediaItem(currentIdx, resolvedItem)
                                
                                // Seek back to where we were
                                player?.seekTo(currentIdx, pos)
                                player?.prepare()
                                player?.play()
                                
                                android.util.Log.d("AudioPlayer", "✅ Resolved placeholder URL for current track")
                            } catch (e: Exception) {
                                android.util.Log.e("AudioPlayer", "Failed to resolve current track URL", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "Error in URL validation", e)
            }
        }
    }})

            // Start scrobble checker
startScrobbleJob()


            // apply persisted playback speed
            try {
                val s = com.example.moniq.SessionStore.loadPlaybackSpeed(appContext ?: context, 1.0f)
                setPlaybackSpeed(s)
                playbackSpeed.postValue(s)
            } catch (_: Exception) {}
        }

        fun setPlaybackSpeed(speed: Float) {
            try {
                val p = player ?: return
                // set speed and match pitch to speed to have pitch follow the speed
                val params = PlaybackParameters(speed, speed)
                p.playbackParameters = params
                playbackSpeed.postValue(speed)
                if (appContext != null)
                        com.example.moniq.SessionStore.savePlaybackSpeed(appContext!!, speed)
            } catch (_: Exception) {}
        }

        fun setQueue(tracks: List<com.example.moniq.model.Track>, startIndex: Int = 0) {
    val ctx = appContext ?: return
    initialize(ctx)

    // ✅ CRITICAL FIX: Only cancel preload jobs, NOT the scrobble job
    // Store reference to scrobbleJob before cancelling
    val currentScrobbleJob = scrobbleJob
    
    try {
        // Cancel all jobs in the coroutine scope EXCEPT scrobbleJob
        coroutineScope?.coroutineContext?.cancelChildren()
    } catch (_: Exception) {}
    
    // ✅ Restore scrobbleJob reference (it wasn't actually cancelled, just its parent context)
    // We need to restart it since cancelChildren() killed it
    scrobbleJob = currentScrobbleJob
    
    // ✅ If scrobbleJob was cancelled or is null, restart it
    if (scrobbleJob?.isActive != true) {
        startScrobbleJob()
    }

    // ✅ Immediately clear the old queue and reset player state
    queue.clear()
    queueTracks.postValue(emptyList())
    
    // ✅ Stop playback and clear all media items
    try {
        player?.stop()
        player?.clearMediaItems()
    } catch (_: Exception) {}

    // Launch coroutine to resolve URLs
    coroutineScope?.launch { 
        // Add a small delay to ensure player has fully cleared
        kotlinx.coroutines.delay(50)
        setQueueInternal(tracks, startIndex) 
    }
}

private fun startScrobbleJob() {
    scrobbleJob?.cancel()
    scrobbleJob = coroutineScope?.launch(Dispatchers.IO) {
        var addedToRecent = false
        var lastTrackId: String? = null
        
        while (isActive) {
            try {
                val trackId = currentTrackId
                val title = currentTitle.value
                val artist = currentArtist.value
                val album = currentAlbumName.value
                val albumId = currentAlbumId.value
                val coverArt = currentAlbumArt.value

                // Reset flag if track changed
                if (trackId != lastTrackId) {
                    addedToRecent = false
                    lastTrackId = trackId
                }

                // Access player on Main thread
                val (pos, dur) = withContext(Dispatchers.Main) {
                    val position = player?.currentPosition ?: 0L
                    val duration = player?.duration ?: 0L
                    Pair(position, duration)
                }

                Log.d("LastFm", "DEBUG: ScrobbleJob loop tick. Track ID: $trackId, Title: $title, Pos: $pos, Dur: $dur")

                // Check if duration is unset before proceeding
                if (dur == androidx.media3.common.C.TIME_UNSET || dur <= 0) {
                    Log.d("LastFm", "DEBUG: ScrobbleJob - Duration is invalid ($dur), skipping check for '$title'.")
                    delay(10000)
                    continue
                }

                if (!trackId.isNullOrBlank() && !title.isNullOrBlank() && !artist.isNullOrBlank()) {
                    // Add to recently played after 30 seconds OR 25% of playback
                    val minPlayTime = minOf(30000L, dur / 4)
                    val durationSec = (dur / 1000).toInt()
                    
                    if (!addedToRecent && pos >= minPlayTime && durationSec >= 30) {
                        try {
                            val ctx = appContext
                            if (ctx != null) {
                                val currentIndex = withContext(Dispatchers.Main) {
                                    player?.currentMediaItemIndex ?: -1
                                }
                                
                                val originalTrack = queueTracks.value?.getOrNull(currentIndex)
                                val realDuration = if (originalTrack != null && originalTrack.duration > 0) {
                                    originalTrack.duration
                                } else {
                                    durationSec
                                }
                                
                                Log.d("AudioPlayer", "Duration check: original=$realDuration, player=$durationSec")
                                
                                if (realDuration > 0) {
                                    val track = com.example.moniq.model.Track(
                                        id = trackId,
                                        title = title,
                                        artist = artist,
                                        duration = realDuration,
                                        albumId = albumId,
                                        albumName = album,
                                        coverArtId = coverArt
                                    )
                                    com.example.moniq.player.RecentlyPlayedManager(ctx).add(track)
                                    addedToRecent = true
                                    Log.d("AudioPlayer", "Added to recently played: $title (API duration: ${realDuration}s, player was: ${durationSec}s)")
                                } else {
                                    Log.w("AudioPlayer", "Skipping add - invalid duration: $realDuration")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AudioPlayer", "Failed to add to recently played", e)
                        }
                    }
                    
                    Log.d("LastFm", "DEBUG: ScrobbleJob - All conditions met, calling checkAndScrobble for '$title'.")
                    com.example.moniq.lastfm.LastFmManager.checkAndScrobble(
                        trackId,
                        title,
                        artist,
                        album,
                        pos,
                        dur
                    )
                } else {
                    Log.d("LastFm", "DEBUG: ScrobbleJob - Conditions NOT met for '$title'.")
                }
            } catch (e: Exception) {
                Log.e("LastFm", "DEBUG: ScrobbleJob encountered an exception: ", e)
            }
            delay(10000) // Check every 10 seconds
        }
    }
}

        private suspend fun setQueueInternal(
        tracks: List<com.example.moniq.model.Track>,
        startIndex: Int = 0
    ) {
        android.util.Log.d("AudioPlayer", "setQueueInternal: tracks=${tracks.size} startIndex=$startIndex")
        val ctx = appContext ?: return

        // ✅ Double-check queue is clear (in case of race condition)
        if (queue.isNotEmpty()) {
            android.util.Log.w("AudioPlayer", "Queue not empty at start of setQueueInternal, clearing again")
            queue.clear()
            try {
                player?.clearMediaItems()
            } catch (_: Exception) {}
        }

        val searchSites = ServerManager.getOrderedServers()

        // Only resolve URLs for the first few tracks (current + next 2)
        val preloadCount = 1
        val endPreloadIndex = (startIndex + preloadCount).coerceAtMost(tracks.size)
        
        // Data class to hold resolved track data
data class ResolvedTrackData(
    val url: String,
    val albumId: String?,
    val albumTitle: String?,
    val audioQuality: String?,  // ✅ ADD THIS
    val bitDepth: Int?,         // ✅ ADD THIS
    val sampleRate: Int?        // ✅ ADD THIS
)

val items = tracks.mapIndexed { index, tr ->
    android.util.Log.d("AudioPlayer", "Processing track: id=${tr.id} title=${tr.title} streamUrl=${tr.streamUrl}")
    
    // Only resolve URLs for tracks within preload range
    val resolvedData: ResolvedTrackData = if (index >= startIndex && index < endPreloadIndex) {
    // Try each server until one works
    var resolved: ResolvedTrackData? = null
    for ((serverIdx, server) in searchSites.withIndex()) {
        try {
            val proxyUrl = "$server/stream/?id=${tr.id}"
            val trackId = tr.id
            val trackInfoUrl = "$server/track/?id=$trackId"
            
            android.util.Log.d("AudioPlayer", "Trying server $server for track ${tr.id}")
            
            val conn = java.net.URL(trackInfoUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
            conn.setRequestProperty("x-client", "BiniLossless/v3.3")
            
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                
                // ✅ Parse metadata including album info, bitDepth, and sampleRate
val metadata = try {
    val jsonObj = org.json.JSONObject(response)
    val data = jsonObj.getJSONObject("data")
    
    // Parse audio quality
    val quality = try {
        val mediaMetadata = data.optJSONObject("mediaMetadata")
        val tags = mediaMetadata?.optJSONArray("tags")
        
        when {
            tags?.toString()?.contains("HIRES_LOSSLESS") == true -> "HI_RES_LOSSLESS"
            tags?.toString()?.contains("LOSSLESS") == true -> "LOSSLESS"
            else -> data.optString("audioQuality", "LOSSLESS")
        }
    } catch (e: Exception) {
        data.optString("audioQuality", "LOSSLESS")
    }
    
    // ✅ Parse album info with DEBUG logging
    val albumObj = data.optJSONObject("album")
    android.util.Log.d("AudioPlayer", "DEBUG: albumObj is null? ${albumObj == null}")
    if (albumObj != null) {
        android.util.Log.d("AudioPlayer", "DEBUG: albumObj.toString() = ${albumObj.toString()}")
        val rawId = albumObj.optLong("id", -1)
        android.util.Log.d("AudioPlayer", "DEBUG: rawId = $rawId")
        val rawTitle = albumObj.optString("title", "MISSING")
        android.util.Log.d("AudioPlayer", "DEBUG: rawTitle = $rawTitle")
    }
    val aId = albumObj?.optLong("id")?.toString()?.takeIf { it != "0" }
    val aTitle = albumObj?.optString("title")?.takeIf { it.isNotEmpty() }
    android.util.Log.d("AudioPlayer", "DEBUG: Final aId = $aId, aTitle = $aTitle")
    
    // ✅ Parse bitDepth and sampleRate inside the same scope
    val depth = data.optInt("bitDepth", 0).takeIf { it > 0 }
    val rate = data.optInt("sampleRate", 0).takeIf { it > 0 }
    
    // ✅ Return TrackMetadata object
    TrackMetadata(quality, depth, rate, aId, aTitle)
} catch (e: Exception) {
    android.util.Log.e("AudioPlayer", "Failed to parse metadata", e)
    TrackMetadata("LOSSLESS", null, null, null, null)
}
                
              // Try to resolve with StreamUrlResolver
val resolvedUrl = try {
    StreamUrlResolver.resolveStreamUrl(proxyUrl, trackId, metadata.audioQuality)
} catch (e: Exception) {
    android.util.Log.e("AudioPlayer", "StreamUrlResolver failed", e)
    null
}

ServerManager.recordSuccess(server)
if (resolvedUrl != null) {
    android.util.Log.d("AudioPlayer", "Successfully resolved URL from $server")
    
    // ✅ Now all variables are in scope
    resolved = ResolvedTrackData(
        resolvedUrl,
        metadata.albumId,
        metadata.albumTitle,
        metadata.audioQuality,
        metadata.bitDepth,
        metadata.sampleRate
    )
    break
}
                } else {
                    conn.disconnect()
                    ServerManager.recordFailure(server) 
                    android.util.Log.w("AudioPlayer", "Server $server returned HTTP $responseCode")
                }
            } catch (e: Exception) {
                android.util.Log.w("AudioPlayer", "Server $server failed: ${e.message}")
                if (serverIdx < searchSites.lastIndex) {
                    kotlinx.coroutines.delay(500)
                }
                continue
            }
        }
        
       resolved ?: ResolvedTrackData(
    "${searchSites[0]}/stream/?id=${tr.id}",
    null,
    null,
    tr.audioQuality,
    tr.bitDepth,
    tr.sampleRate
)
    } else {
        // For tracks beyond preload range, just use placeholder/proxy URL
        android.util.Log.d("AudioPlayer", "Track ${tr.id} beyond preload range, using placeholder")
        val placeholderUrl = if (tr.streamUrl?.contains("/stream/?id=") == true) {
            tr.streamUrl
        } else {
            "${searchSites[0]}/stream/?id=${tr.id}"
        }
        ResolvedTrackData(placeholderUrl, null, null, tr.audioQuality, tr.bitDepth, tr.sampleRate)
    }
    
    android.util.Log.d("AudioPlayer", "Final URL for track ${tr.id}: ${resolvedData.url}")

    // ✅ Use album info from server if available, otherwise use track's existing info
    val finalAlbumId = resolvedData.albumId ?: tr.albumId
    val finalAlbumName = resolvedData.albumTitle ?: tr.albumName
    
    android.util.Log.d("AudioPlayer", "Track ${tr.id}: using albumId=$finalAlbumId, albumName=$finalAlbumName")

    val meta =
        MediaMetadata.Builder()
            .apply {
                if (!tr.title.isNullOrEmpty()) setTitle(tr.title)
                if (!tr.artist.isNullOrEmpty()) setArtist(tr.artist)
                if (!finalAlbumName.isNullOrBlank())
                    setAlbumTitle(finalAlbumName)
                val coverId = tr.coverArtId ?: tr.albumId ?: tr.id
                if (!coverId.isNullOrEmpty()) {
                    val artUrl =
                        com.example.moniq.util.ImageUrlHelper
                            .getCoverArtUrl(coverId)
                    if (!artUrl.isNullOrEmpty()) {
                        try {
                            setArtworkUri(android.net.Uri.parse(artUrl))
                        } catch (_: Exception) {}
                    }
                }
                try {
    val extras = android.os.Bundle()
    finalAlbumId?.let { extras.putString("albumId", it) }
    finalAlbumName?.let { extras.putString("albumName", it) }
    extras.putString("trackArtist", tr.artist)
    
    (resolvedData.audioQuality ?: tr.audioQuality)?.let { extras.putString("audioQuality", it) }
    (resolvedData.bitDepth ?: tr.bitDepth)?.let { extras.putInt("bitDepth", it) }
    (resolvedData.sampleRate ?: tr.sampleRate)?.let { extras.putInt("sampleRate", it) }
    
    setExtras(extras)
} catch (_: Exception) {}
            }
            .build()

    MediaItem.Builder().setUri(resolvedData.url).setMediaMetadata(meta).setTag(tr.id).build()
}
        
        // ✅ Clear queue one more time right before setting new items (paranoid check)
        queue.clear()
        queue.addAll(items)
        
        // ✅ Use withContext to ensure this runs on Main thread
        withContext(Dispatchers.Main) {
            try {
                player?.setMediaItems(queue, startIndex, 0L)
                player?.prepare()
                player?.play()
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "Failed to set media items", e)
            }
        }
        
        preloadNext()
        
        // update queue live data
      queueTracks.postValue(
    queue.map { mi ->
        val meta = mi.mediaMetadata
        val id = try {
            mi.localConfiguration?.tag as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        // ✅ Extract album info from MediaItem extras
        val extras = meta?.extras
        val albumId = extras?.getString("albumId")
        val albumName = extras?.getString("albumName")
        
        com.example.moniq.model.Track(
            id,
            meta?.title?.toString() ?: "",
            meta?.artist?.toString() ?: "",
            0,
            albumId = albumId,  // ✅ Preserve album ID
            albumName = albumName,  // ✅ Preserve album name
            coverArtId = meta?.artworkUri?.toString()
        )
    }
)
        currentQueueIndex.postValue(startIndex)
        
        // update live data
        val firstMeta = queue.getOrNull(startIndex)?.mediaMetadata
        currentTitle.postValue(firstMeta?.title?.toString())
        currentArtist.postValue(firstMeta?.artist?.toString())
        currentAlbumArt.postValue(firstMeta?.artworkUri?.toString())
        try {
            currentTrackId = queue.getOrNull(startIndex)?.localConfiguration?.tag as? String
        } catch (_: Exception) {}
        try {
            val trId = queue.getOrNull(startIndex)?.localConfiguration?.tag as? String
            if (!trId.isNullOrEmpty() && appContext != null) {
                val extras = firstMeta?.extras
                try {
                    com.example.moniq.SessionStore.saveLastTrack(
                            appContext!!,
                            trId,
                            0L,
                            firstMeta?.title?.toString(),
                            firstMeta?.artist?.toString(),
                            firstMeta?.artworkUri?.toString(),
                            true,
                            extras?.getString("albumId"),
                            extras?.getString("albumName"),
                            player?.duration ?: 0L
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
        fun playAt(index: Int) {
            try {
                player?.seekTo(index, 0L)
            } catch (_: Exception) {}
            currentQueueIndex.postValue(index)
            preloadNext()
        }

        fun removeFromQueue(index: Int) {
            if (index < 0 || index >= queue.size) return
            try {
                val cur = player?.currentMediaItemIndex ?: 0
                val wasPlaying = player?.isPlaying == true
                // If removing the currently playing item, rebuild media items and start at the same
                // index (which will now point to the next item)
                if (index == cur) {
                    queue.removeAt(index)
                    val newStart = cur.coerceAtMost(queue.size - 1).coerceAtLeast(0)
                    player?.setMediaItems(queue, /* startIndex= */ newStart, /* positionMs= */ 0L)
                    player?.prepare()
                    if (wasPlaying) player?.play()
                } else {
                    // For non-current removals, instruct ExoPlayer to remove the item without
                    // resetting
                    // playback
                    queue.removeAt(index)
                    try {
                        player?.removeMediaItem(index)
                    } catch (_: Exception) {}
                    // If removed item was before current, current index shifts down by 1
                    val newCur = if (index < cur) (cur - 1).coerceAtLeast(0) else cur
                    // update live data without restarting playback
                    queueTracks.postValue(
    queue.map { mi ->
        val meta = mi.mediaMetadata
        val id = try {
            mi.localConfiguration?.tag as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        // ✅ Extract album info from MediaItem extras
        val extras = meta?.extras
        val albumId = extras?.getString("albumId")
        val albumName = extras?.getString("albumName")
        
        com.example.moniq.model.Track(
            id,
            meta?.title?.toString() ?: "",
            meta?.artist?.toString() ?: "",
            0,
            albumId = albumId,  // ✅ Preserve album ID
            albumName = albumName,  // ✅ Preserve album name
            coverArtId = meta?.artworkUri?.toString()
        )
    }
)
                    currentQueueIndex.postValue(newCur)
                    return
                }
            } catch (_: Exception) {}
        }

        // Append a single track to the end of the queue
        // Append a single track to the end of the queue
        fun addToQueue(track: com.example.moniq.model.Track) {
            try {
                initialize(appContext ?: return)
                coroutineScope?.launch {
                    val enriched = MetadataFetcher.enrichTrackMetadata(track)
                    addToQueueInternal(enriched)
                }
            } catch (_: Exception) {}
        }

        private suspend fun addToQueueInternal(track: com.example.moniq.model.Track) {
        try {
            val item = buildMediaItemForTrack(track)
            queue.add(item)
            try {
                player?.addMediaItem(item)
            } catch (_: Exception) {}
            try {
                player?.prepare()
            } catch (_: Exception) {}
         queueTracks.postValue(
    queue.map { mi ->
        val meta = mi.mediaMetadata
        val id = try {
            mi.localConfiguration?.tag as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        // ✅ Extract album info from MediaItem extras
        val extras = meta?.extras
        val albumId = extras?.getString("albumId")
        val albumName = extras?.getString("albumName")
        
        com.example.moniq.model.Track(
            id,
            meta?.title?.toString() ?: "",
            meta?.artist?.toString() ?: "",
            0,
            albumId = albumId,  // ✅ Preserve album ID
            albumName = albumName,  // ✅ Preserve album name
            coverArtId = meta?.artworkUri?.toString()
        )
    }
)
        } catch (_: Exception) {}
    }


    private suspend fun resolveUrlWithRetry(tr: com.example.moniq.model.Track, maxRetries: Int = 2): String = withContext(Dispatchers.IO) {
        val servers = ServerManager.getOrderedServers()
        
        // Try each server once, then retry failed ones
        for (attempt in 0 until maxRetries) {
            android.util.Log.d("AudioPlayer", "Resolve attempt ${attempt + 1}/$maxRetries for track ${tr.id}")
            
            for ((serverIdx, server) in servers.withIndex()) {
                try {
                    val proxyUrl = "$server/stream/?id=${tr.id}"
                    val trackId = tr.id
                    val trackInfoUrl = "$server/track/?id=$trackId"
                    
                    android.util.Log.d("AudioPlayer", "Trying server: $server")
                    
                    val conn = java.net.URL(trackInfoUrl).openConnection() as java.net.HttpURLConnection
conn.connectTimeout = 15000  
conn.readTimeout = 20000
conn.requestMethod = "GET"
conn.setRequestProperty("Accept", "*/*")
conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
conn.setRequestProperty("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
                    
                    val responseCode = conn.responseCode
                    if (responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        
                        val audioQuality = try {
                            val jsonObj = org.json.JSONObject(response)
                            val data = jsonObj.getJSONObject("data")
                            data.getString("audioQuality")
                        } catch (e: Exception) {
                            android.util.Log.e("AudioPlayer", "Failed to parse audioQuality", e)
                            "LOSSLESS"
                        }
                        
                        android.util.Log.d("AudioPlayer", "Got audioQuality: $audioQuality from $server")
                        
                        val resolvedUrl = try {
                            StreamUrlResolver.resolveStreamUrl(proxyUrl, trackId, audioQuality)
                        } catch (e: Exception) {
                            android.util.Log.e("AudioPlayer", "StreamUrlResolver failed", e)
                            null
                        }
                        
                        if (resolvedUrl != null) {
                            ServerManager.recordSuccess(server)
                            android.util.Log.d("AudioPlayer", "Successfully resolved URL from $server")
                            return@withContext resolvedUrl
                        } else {
                            android.util.Log.d("AudioPlayer", "Using proxy URL from $server")
                            return@withContext proxyUrl
                        }
                    } else {
        conn.disconnect()
        ServerManager.recordFailure(server)
        android.util.Log.w("AudioPlayer", "Server $server returned HTTP $responseCode")
        // Don't delay if we got a response - immediately try next server
    }
                } catch (e: Exception) {
                    android.util.Log.w("AudioPlayer", "Server $server failed: ${e.message}")
                    // Short delay before next server (only if not last)
                    if (serverIdx < servers.lastIndex) {
                        kotlinx.coroutines.delay(500)
                    }
                    continue
                }
            }
            
            // All servers failed this attempt, wait before retry
            if (attempt < maxRetries - 1) {
                val waitTime = 1000L // Just 1 second between full retries
                android.util.Log.w("AudioPlayer", "All servers failed, waiting ${waitTime}ms before retry ${attempt + 2}")
                kotlinx.coroutines.delay(waitTime)
            }
        }
        
        // Ultimate fallback
        val fallbackUrl = "${servers[0]}/stream/?id=${tr.id}"
        android.util.Log.e("AudioPlayer", "All retries exhausted, using fallback: $fallbackUrl")
        return@withContext fallbackUrl
    }

        // Insert a single track immediately after the current playing index
        fun playNext(track: com.example.moniq.model.Track) {
            try {
                initialize(appContext ?: return)
                coroutineScope?.launch {
                    val enriched = MetadataFetcher.enrichTrackMetadata(track)
                    playNextInternal(enriched)
                }
            } catch (_: Exception) {}
        }

        private fun preloadNext() {
        val p = player ?: return
        val queue = queueTracks.value ?: return

        val currentIdx = p.currentMediaItemIndex
        val nextIdx = currentIdx + 1

        if (nextIdx >= queue.size) return

        val nextTrack = queue[nextIdx]
        
        // Check if already resolved (has local file path or https URL that's not a placeholder)
        val currentUrl = this@AudioPlayer.queue.getOrNull(nextIdx)?.localConfiguration?.uri?.toString()
        if (currentUrl?.startsWith("file://") == true) {
            android.util.Log.d("AudioPlayer", "Next track already preloaded (file): ${nextTrack.title}")
            return
        }
        
        // If it's a resolved HTTPS URL (not a placeholder), check if it looks valid
        if (currentUrl?.startsWith("https://") == true && !currentUrl.contains("/stream/?id=")) {
            android.util.Log.d("AudioPlayer", "Next track already preloaded (https): ${nextTrack.title}")
            return
        }

        android.util.Log.d("AudioPlayer", "Preloading next track: ${nextTrack.title}")

        coroutineScope?.launch {
            try {
                // Resolve the URL for the next track
                val item = buildMediaItemForTrack(nextTrack)
                
                // Only update if we're still on the same current track
                withContext(Dispatchers.Main) {
                    if (p.currentMediaItemIndex == currentIdx && nextIdx < this@AudioPlayer.queue.size) {
                        this@AudioPlayer.queue[nextIdx] = item
                        
                        try {
                            p.replaceMediaItem(nextIdx, item)
                            android.util.Log.d("AudioPlayer", "✅ Preloaded next track: ${nextTrack.title}")
                        } catch (e: Exception) {
                            android.util.Log.w("AudioPlayer", "Failed to replace media item", e)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayer", "❌ Preload failed for ${nextTrack.title}", e)
                // Don't crash - the track will be resolved when it actually plays (see below)
            }
        }
    }


        private suspend fun playNextInternal(track: com.example.moniq.model.Track) {
            try {
                val idx = (player?.currentMediaItemIndex ?: -1) + 1
                val item = buildMediaItemForTrack(track)
                queue.add(idx.coerceAtMost(queue.size), item)
                try {
                    player?.addMediaItem(idx.coerceAtMost(queue.size - 1).coerceAtLeast(0), item)
                } catch (_: Exception) {}
                try {
                    player?.prepare()
                } catch (_: Exception) {}
              queueTracks.postValue(
    queue.map { mi ->
        val meta = mi.mediaMetadata
        val id = try {
            mi.localConfiguration?.tag as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        // ✅ Extract album info from MediaItem extras
        val extras = meta?.extras
        val albumId = extras?.getString("albumId")
        val albumName = extras?.getString("albumName")
        
        com.example.moniq.model.Track(
            id,
            meta?.title?.toString() ?: "",
            meta?.artist?.toString() ?: "",
            0,
            albumId = albumId,  // ✅ Preserve album ID
            albumName = albumName,  // ✅ Preserve album name
            coverArtId = meta?.artworkUri?.toString()
        )
    }
)
            } catch (_: Exception) {}
        }

        // Append multiple tracks to the end of the queue
        fun addToQueue(tracks: List<com.example.moniq.model.Track>) {
            coroutineScope?.launch {
                val enriched = MetadataFetcher.enrichTracksMetadata(tracks)
                for (t in enriched) addToQueueInternal(t)
            }
        }

        // Insert multiple tracks immediately after the current playing index
        fun playNext(tracks: List<com.example.moniq.model.Track>) {
            coroutineScope?.launch {
                val enriched = MetadataFetcher.enrichTracksMetadata(tracks)
                playNextBatchInternal(enriched)
            }
        }

        private suspend fun playNextBatchInternal(tracks: List<com.example.moniq.model.Track>) {
            try {
                initialize(appContext ?: return)
                var idx = (player?.currentMediaItemIndex ?: -1) + 1
                for (t in tracks) {
                    val item = buildMediaItemForTrack(t)
                    queue.add(idx.coerceAtMost(queue.size), item)
                    try {
                        player?.addMediaItem(idx.coerceAtMost(queue.size - 1).coerceAtLeast(0), item)
                    } catch (_: Exception) {}
                    idx += 1
                }
                try {
                    player?.prepare()
                } catch (_: Exception) {}
               queueTracks.postValue(
    queue.map { mi ->
        val meta = mi.mediaMetadata
        val id = try {
            mi.localConfiguration?.tag as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        // ✅ Extract album info from MediaItem extras
        val extras = meta?.extras
        val albumId = extras?.getString("albumId")
        val albumName = extras?.getString("albumName")
        
        com.example.moniq.model.Track(
            id,
            meta?.title?.toString() ?: "",
            meta?.artist?.toString() ?: "",
            0,
            albumId = albumId,  // ✅ Preserve album ID
            albumName = albumName,  // ✅ Preserve album name
            coverArtId = meta?.artworkUri?.toString()
        )
    }
)
            } catch (_: Exception) {}
        }

        private suspend fun buildMediaItemForTrack(
    tr: com.example.moniq.model.Track,
    forceFresh: Boolean = false
): MediaItem {
    android.util.Log.d(
            "AudioPlayer",
            "buildMediaItemForTrack: Building for track ${tr.id} '${tr.title}'"
    )
    android.util.Log.d("AudioPlayer", "buildMediaItemForTrack: coverArtId='${tr.coverArtId}'")

    val searchSites = ServerManager.getOrderedServers()
    val trackId = tr.id
        
    // ✅ FIX: Try with retry logic (2 full attempts)
    var resolvedUrl: String? = null
    var trackMetadata: TrackMetadata? = null  // ✅ Declare outside the loop
    
    for (attempt in 1..2) {
        android.util.Log.d("AudioPlayer", "buildMediaItemForTrack: Attempt $attempt/2 for track $trackId")
        
        for ((serverIdx, server) in searchSites.withIndex()) {
            try {
                val proxyUrl = "$server/stream/?id=$trackId"
                val trackInfoUrl = "$server/track/?id=$trackId"
                
                android.util.Log.d("AudioPlayer", "Trying server $server for track $trackId")
                
                val conn = java.net.URL(trackInfoUrl).openConnection() as java.net.HttpURLConnection
conn.connectTimeout = 15000  
conn.readTimeout = 20000
conn.requestMethod = "GET"
conn.setRequestProperty("Accept", "*/*")
conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
conn.setRequestProperty("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
                
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    
                    trackMetadata = try {
    val jsonObj = org.json.JSONObject(response)
    val data = jsonObj.getJSONObject("data")
    
    // Parse audio quality
    val quality = try {
        val mediaMetadata = data.optJSONObject("mediaMetadata")
        val tags = mediaMetadata?.optJSONArray("tags")
        
        when {
            tags?.toString()?.contains("HIRES_LOSSLESS") == true -> "HI_RES_LOSSLESS"
            tags?.toString()?.contains("LOSSLESS") == true -> "LOSSLESS"
            else -> data.optString("audioQuality", "LOSSLESS")
        }
    } catch (e: Exception) {
        data.optString("audioQuality", "LOSSLESS")
    }
    
    val depth = data.optInt("bitDepth", 0)
    val rate = data.optInt("sampleRate", 0)
    
    // ✅ NEW: Parse album info from response with DEBUG logging
    val albumObj = data.optJSONObject("album")
    android.util.Log.d("AudioPlayer", "DEBUG buildMedia: albumObj is null? ${albumObj == null}")
    if (albumObj != null) {
        android.util.Log.d("AudioPlayer", "DEBUG buildMedia: albumObj.toString() = ${albumObj.toString()}")
        val rawId = albumObj.optLong("id", -1)
        android.util.Log.d("AudioPlayer", "DEBUG buildMedia: rawId = $rawId")
        val rawTitle = albumObj.optString("title", "MISSING")
        android.util.Log.d("AudioPlayer", "DEBUG buildMedia: rawTitle = $rawTitle")
    }
    val albumId = albumObj?.optLong("id")?.toString()?.takeIf { it != "0" }
    val albumTitle = albumObj?.optString("title")?.takeIf { it.isNotEmpty() }
    android.util.Log.d("AudioPlayer", "DEBUG buildMedia: Final albumId = $albumId, albumTitle = $albumTitle")
    
    // ✅ RETURN the TrackMetadata object
    TrackMetadata(quality, depth, rate, albumId, albumTitle)
} catch (e: Exception) {
    android.util.Log.e("AudioPlayer", "Failed to parse track metadata", e)
    TrackMetadata("LOSSLESS", null, null, null, null)
}
                    
ServerManager.recordSuccess(server)
                    
// ✅ Use trackMetadata.audioQuality instead of audioQuality
resolvedUrl = try {
                        withContext(Dispatchers.IO) {
                            withTimeout(20000L) {
                                StreamUrlResolver.resolveStreamUrl(proxyUrl, trackId, trackMetadata?.audioQuality ?: "LOSSLESS", forceFresh)
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.w("AudioPlayer", "StreamUrlResolver timed out, using proxy URL")
                        proxyUrl
                    } catch (e: Exception) {
                        android.util.Log.e("AudioPlayer", "StreamUrlResolver failed: ${e.message}", e)
                        null
                    }
                    
                    if (resolvedUrl != null) {
                        android.util.Log.d("AudioPlayer", "Successfully resolved from $server: $resolvedUrl")
                        break // Success - exit server loop
                    } else {
                        android.util.Log.d("AudioPlayer", "Using proxy URL from $server")
                        resolvedUrl = proxyUrl
                        break // Got proxy URL - exit server loop
                    }
                } else {
                    conn.disconnect()
                    ServerManager.recordFailure(server)
                    android.util.Log.w("AudioPlayer", "Server $server returned HTTP $responseCode")
                }
            } catch (e: Exception) {
                android.util.Log.w("AudioPlayer", "Server $server failed: ${e.message}")
                if (serverIdx < searchSites.lastIndex) {
                    kotlinx.coroutines.delay(500)
                }
                continue
            }
        }
        
        // If we got a URL, break out of retry loop
        if (resolvedUrl != null) break
        
        // All servers failed this attempt
        if (attempt < 2) {
            android.util.Log.w("AudioPlayer", "All servers failed attempt $attempt, waiting 2s before retry...")
            kotlinx.coroutines.delay(2000) // Wait 2 seconds before full retry
        }
    }
    
    // ✅ Use fallback metadata if parsing failed
    val finalMetadata = trackMetadata ?: TrackMetadata("LOSSLESS", null, null, null, null)
    
    // ✅ FIX: If still no URL after retries, throw error instead of using broken fallback
    val url = resolvedUrl ?: run {
        android.util.Log.e("AudioPlayer", "CRITICAL: All servers failed after retries for track $trackId")
        
        // Show error to user on main thread
        withContext(Dispatchers.Main) {
            try {
                appContext?.let { ctx ->
                    android.widget.Toast.makeText(
                        ctx,
                        "Unable to play ${tr.title}: All servers unavailable",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {}
        }
        
        // Return a placeholder that will fail gracefully
        "https://invalid.placeholder/error"
    }
    
    android.util.Log.d("AudioPlayer", "Using URL: $url")
    
    val meta =
            MediaMetadata.Builder()
                    .apply {
                        if (!tr.title.isNullOrEmpty()) setTitle(tr.title)
                        if (!tr.artist.isNullOrEmpty()) setArtist(tr.artist)
                        
                        // ✅ Use album info from server response if available, fallback to tr values
                        val albumName = finalMetadata.albumTitle ?: tr.albumName
                        if (!albumName.isNullOrBlank()) setAlbumTitle(albumName)
                        
                        val coverId = tr.coverArtId ?: tr.albumId ?: tr.id
                        if (!coverId.isNullOrEmpty()) {
                            val artUrl =
                                    com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(
                                            coverId
                                    )
                            if (!artUrl.isNullOrEmpty()) {
                                try {
                                    setArtworkUri(android.net.Uri.parse(artUrl))
                                } catch (_: Exception) {}
                            }
                        }
                        try {
                            val extras = android.os.Bundle()
                            // ✅ Use album info from server response if available, fallback to tr values
                            val albumId = finalMetadata.albumId ?: tr.albumId
                            val albumName = finalMetadata.albumTitle ?: tr.albumName
                            
                            albumId?.let { extras.putString("albumId", it) }
                            albumName?.let { extras.putString("albumName", it) }
                            extras.putString("trackArtist", tr.artist)
                            
                            // Also set audio quality info
                            extras.putString("audioQuality", finalMetadata.audioQuality)
                            finalMetadata.bitDepth?.let { extras.putInt("bitDepth", it) }
                            finalMetadata.sampleRate?.let { extras.putInt("sampleRate", it) }
                            
                            setExtras(extras)
                        } catch (_: Exception) {}
                    }
                    .build()
    
    return MediaItem.Builder()
            .setUri(android.net.Uri.parse(url))
            .setMediaMetadata(meta)
            .setTag(tr.id)
            .build()
}
            

        fun moveInQueue(fromIndex: Int, toIndex: Int) {
            if (fromIndex < 0 || fromIndex >= queue.size) return
            if (toIndex < 0 || toIndex >= queue.size) return
            try {
                val wasPlaying = player?.isPlaying == true
                val cur = player?.currentMediaItemIndex ?: 0
                val curPos = player?.currentPosition ?: 0L
                val item = queue.removeAt(fromIndex)
                queue.add(toIndex, item)

                // compute new current index after move
                var newCur = cur
                if (fromIndex == cur) newCur = toIndex
                else if (fromIndex < cur && toIndex >= cur) newCur = cur - 1
                else if (fromIndex > cur && toIndex <= cur) newCur = cur + 1

                player?.setMediaItems(queue, newCur.coerceAtLeast(0), curPos)
                player?.prepare()
                if (wasPlaying) player?.play()

                // update live data
               queueTracks.postValue(
    queue.map { mi ->
        val meta = mi.mediaMetadata
        val id = try {
            mi.localConfiguration?.tag as? String ?: ""
        } catch (_: Exception) {
            ""
        }
        // ✅ Extract album info from MediaItem extras
        val extras = meta?.extras
        val albumId = extras?.getString("albumId")
        val albumName = extras?.getString("albumName")
        
        com.example.moniq.model.Track(
            id,
            meta?.title?.toString() ?: "",
            meta?.artist?.toString() ?: "",
            0,
            albumId = albumId,  // ✅ Preserve album ID
            albumName = albumName,  // ✅ Preserve album name
            coverArtId = meta?.artworkUri?.toString()
        )
    }
)
                currentQueueIndex.postValue(player?.currentMediaItemIndex ?: 0)
            } catch (_: Exception) {}
        }

        fun playTrack(
                context: Context,
                trackId: String,
                title: String? = null,
                artist: String? = null,
                albumArt: String? = null,
                albumId: String? = null,
                albumName: String? = null
        ) {
            currentTrackId = trackId
            // Play a single track by creating a one-item queue so next/prev work properly
            val temp =
                    com.example.moniq.model.Track(
                            trackId,
                            title ?: "",
                            artist ?: "",
                            0,
                            albumId = albumId,
                            albumName = albumName,
                            coverArtId = albumArt
                    )
            // Enrich metadata before playing
            coroutineScope?.launch {
                val enriched = MetadataFetcher.enrichTrackMetadata(temp)
                setQueueInternal(listOf(enriched), 0)
            }
            // avoid adding an empty-cover duplicate here; setQueue already records the played track
            // with available metadata
        }

        fun playTrack(
                trackId: String,
                title: String? = null,
                artist: String? = null,
                albumArt: String? = null,
                albumId: String? = null,
                albumName: String? = null
        ) {
            val ctx = appContext ?: return
            playTrack(ctx, trackId, title, artist, albumArt, albumId, albumName)
        }

        fun playStream(
                context: Context,
                url: String,
                title: String? = null,
                artist: String? = null,
                albumArt: String? = null
        ) {
            initialize(context)
            player?.apply {
                val meta =
                        MediaMetadata.Builder()
                                .apply {
                                    if (!title.isNullOrEmpty()) setTitle(title)
                                    if (!artist.isNullOrEmpty()) setArtist(artist)
                                    if (!albumArt.isNullOrEmpty())
                                            setArtworkUri(android.net.Uri.parse(albumArt))
                                }
                                .build()
                val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(meta).build()
                setMediaItem(mediaItem)
                prepare()
                play()
                currentTitle.postValue(title)
                currentAlbumArt.postValue(albumArt)
                currentArtist.postValue(artist)
            }
        }

        fun playStream(
                url: String,
                title: String? = null,
                artist: String? = null,
                albumArt: String? = null
        ) {
            val ctx = appContext ?: return
            playStream(ctx, url, title, artist, albumArt)
        }

        fun togglePlayPause() {
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
        }

        fun pause() {
            player?.pause()
        }

        fun resume() {
            player?.play()
        }

        fun next() {
            try {
                player?.seekToNextMediaItem()
            } catch (_: Exception) {}
        }

        fun previous() {
            try {
                player?.seekToPreviousMediaItem()
            } catch (_: Exception) {}
        }

        fun seekTo(ms: Long) {
            try {
                player?.seekTo(ms)
            } catch (_: Exception) {}
        }

        fun seekRelative(deltaMs: Long) {
            try {
                val p = player ?: return
                val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
                val dur = p.duration
                val final = if (dur > 0) target.coerceAtMost(dur) else target
                p.seekTo(final)
            } catch (_: Exception) {}
        }

        fun currentPosition(): Long {
            return player?.currentPosition ?: 0L
        }

        fun duration(): Long {
            return player?.duration ?: 0L
        }

        fun release() {
            scrobbleJob?.cancel()
            scrobbleJob = null

            // Cancel all coroutines
            coroutineScope?.cancel()
            coroutineScope = null

            try {
                playerNotificationManager?.setPlayer(null)
            } catch (_: Exception) {}
            playerNotificationManager = null
            try {
                mediaSession?.release()
            } catch (_: Exception) {}
            mediaSession = null
            try {
                player?.release()
            } catch (_: Exception) {}
            player = null
        }

        // Post a custom media notification with a seek-like progress bar and basic controls
        private fun postCustomNotification() {
            try {
                val ctx = appContext ?: return
                val nm = notificationManager ?: return
                val chan = "moniq_playback_channel"
                val builder =
                        androidx.core.app.NotificationCompat.Builder(ctx, chan)
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .setContentTitle(currentTitle.value ?: "")
                                .setContentText(currentArtist.value ?: "")
                                .setOnlyAlertOnce(true)
                                .setOngoing(true)

                // Use a single compact notification layout with actions: Prev, Play/Pause, Next,
                // Queue
                val prevIntent =
                        android.content.Intent(ctx, NotificationActionReceiver::class.java).apply {
                            action = NotificationActionReceiver.ACTION_PREV
                        }
                val prevPi =
                        android.app.PendingIntent.getBroadcast(
                                ctx,
                                101,
                                prevIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                        android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                builder.addAction(0, "Prev", prevPi)

                val toggleIntent =
                        android.content.Intent(ctx, NotificationActionReceiver::class.java).apply {
                            action = NotificationActionReceiver.ACTION_TOGGLE_PLAY
                        }
                val togglePi =
                        android.app.PendingIntent.getBroadcast(
                                ctx,
                                102,
                                toggleIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                        android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                val playLabel = if (isPlaying.value == true) "Pause" else "Play"
                builder.addAction(0, playLabel, togglePi)

                val nextIntent =
                        android.content.Intent(ctx, NotificationActionReceiver::class.java).apply {
                            action = NotificationActionReceiver.ACTION_NEXT
                        }
                val nextPi =
                        android.app.PendingIntent.getBroadcast(
                                ctx,
                                103,
                                nextIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                        android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                builder.addAction(0, "Next", nextPi)

                // action to open the Queue view
                val queueAct =
                        android.content.Intent(ctx, com.example.moniq.QueueActivity::class.java).apply {
                            flags =
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                val queuePi =
                        android.app.PendingIntent.getActivity(
                                ctx,
                                110,
                                queueAct,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                        android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                builder.addAction(0, "Queue", queuePi)

                // tapping notification opens QueueActivity as well
                builder.setContentIntent(queuePi)

                // attach compact custom content view (cover, title, subtitle)
                val rv = RemoteViews(ctx.packageName, R.layout.notification_media_compact)
                rv.setTextViewText(R.id.notif_title, currentTitle.value ?: "")
                // show "Artist — Album" when possible
                val artistText = currentArtist.value ?: ""
                val albumText = currentAlbumName.value ?: ""
                val subtitle =
                        if (!albumText.isNullOrBlank()) "$artistText — $albumText" else artistText
                rv.setTextViewText(R.id.notif_subtitle, subtitle)
                try {
                    rv.setImageViewResource(R.id.notif_cover, R.mipmap.ic_launcher)
                } catch (_: Exception) {}
                builder.setCustomContentView(rv)

                // If we have a cached bitmap from a previous fetch, reuse it to avoid flicker
                if (lastNotifiedBitmap != null) {
                    try {
                        builder.setLargeIcon(lastNotifiedBitmap)
                    } catch (_: Exception) {}
                    try {
                        rv.setImageViewBitmap(R.id.notif_cover, lastNotifiedBitmap)
                    } catch (_: Exception) {}
                }
                val notif = builder.build()
                nm.notify(1, notif)

                // Asynchronously fetch album art and update notification large icon when available
                try {
                    val artSrc: String? =
                            if (!currentAlbumArt.value.isNullOrBlank()) {
                                currentAlbumArt.value
                            } else {
                                val id = currentTrackId
                                val host = SessionManager.host
                                if (id.isNullOrBlank() || host.isNullOrBlank() || id.startsWith("http"))
                                        null
                                else
                                        android.net.Uri.parse(host)
                                                .buildUpon()
                                                .appendPath("rest")
                                                .appendPath("getCoverArt.view")
                                                .appendQueryParameter("id", id)
                                                .appendQueryParameter(
                                                        "u",
                                                        SessionManager.username ?: ""
                                                )
                                                .appendQueryParameter(
                                                        "p",
                                                        SessionManager.password ?: ""
                                                )
                                                .build()
                                                .toString()
                            }
                    if (!artSrc.isNullOrBlank() && artSrc != lastNotifiedArtSrc) {
                        lastNotifiedArtSrc = artSrc
                        Thread {
                                    try {
                                        val conn = java.net.URL(artSrc).openConnection()
                                        conn.connectTimeout = 5000
                                        conn.readTimeout = 5000
                                        val stream = conn.getInputStream()
                                        val bytes = stream.readBytes()
                                        stream.close()
                                        val bmp =
                                                android.graphics.BitmapFactory.decodeByteArray(
                                                        bytes,
                                                        0,
                                                        bytes.size
                                                )
                                        if (bmp != null) {
                                            lastNotifiedBitmap = bmp
                                            try {
                                                builder.setLargeIcon(bmp)
                                                try {
                                                    rv.setImageViewBitmap(R.id.notif_cover, bmp)
                                                    builder.setCustomContentView(rv)
                                                } catch (_: Exception) {}
                                                nm.notify(1, builder.build())
                                            } catch (_: Exception) {}
                                        }
                                    } catch (_: Exception) {}
                                }
                                .start()
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        fun restoreLast(context: Context) {
        Log.d("AudioPlayer", "restoreLast() called - this should only happen on app startup!")
        initialize(context) // Initialize FIRST to ensure coroutineScope exists
        coroutineScope?.launch {
            try {
                val last = com.example.moniq.SessionStore.loadLastTrack(context) ?: return@launch
                
                // Use 0 for duration when restoring - it will be updated when track loads
                val tr =
                        com.example.moniq.model.Track(
                                last.id,
                                last.title ?: "",
                                last.artist ?: "",
                                0,  // Duration will be resolved when track loads
                                albumId = last.albumId,
                                albumName = last.albumName,
                                coverArtId = last.artUrl
                        )
                    val item = buildMediaItemForTrack(tr)
                    queue.clear()
                    queue.add(item)
                    player?.setMediaItems(queue, 0, last.posMs)
                    player?.prepare()
                    try {
                        player?.pause()
                    } catch (_: Exception) {}
                    currentTrackId = last.id
                    currentTitle.postValue(last.title)
                    currentArtist.postValue(last.artist)
                    currentAlbumArt.postValue(last.artUrl)
                    currentAlbumName.postValue(last.albumName)
                    currentAlbumId.postValue(last.albumId)
                    isPlaying.postValue(false)

                    // Prevent scrobbling restored tracks by marking them as already scrobbled
                    try {
                        com.example.moniq.lastfm.LastFmManager.resetScrobbleState()
                    } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
    }
