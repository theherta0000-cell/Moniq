package com.example.moniq.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import java.net.URLEncoder

object AudioPlayer {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var appContext: Context? = null
    var currentTrackId: String? = null
    private var queue: MutableList<MediaItem> = mutableListOf()
    val queueTracks = MutableLiveData<List<com.example.moniq.model.Track>>(emptyList())
    val currentQueueIndex = MutableLiveData<Int>(0)

    // notification helpers
    private var notificationManager: NotificationManager? = null

    val currentTitle = MutableLiveData<String?>(null)
    val currentArtist = MutableLiveData<String?>(null)
    val currentAlbumArt = MutableLiveData<String?>(null)
    val currentAlbumName = MutableLiveData<String?>(null)
    val currentAlbumId = MutableLiveData<String?>(null)
    val currentDominantColor = MutableLiveData<Int?>(null)
    val isPlaying = MutableLiveData(false)
    val playbackSpeed = MutableLiveData<Float>(1.0f)
    private var lastNotifiedArtSrc: String? = null
    private var lastNotifiedBitmap: android.graphics.Bitmap? = null

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
                                    return "${fmt(pos)} / ${fmt(dur)}"
                                }
                            } catch (_: Exception) {}
                            return currentArtist.value ?: null
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
                                                val stream = java.net.URL(artUrl).openStream()
                                                val bytes = stream.readBytes()
                                                val bmp =
                                                        android.graphics.BitmapFactory
                                                                .decodeByteArray(
                                                                        bytes,
                                                                        0,
                                                                        bytes.size
                                                                )
                                                if (bmp != null) callback.onBitmap(bmp)
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
            try { playerNotificationManager?.setPlayer(player) } catch (_: Exception) {}
        } catch (_: Exception) {}

        player?.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                        isPlaying.postValue(isPlayingNow)
                        try {
                            val ctx = appContext
                            if (ctx != null) {
                                val svc = android.content.Intent(ctx, PlaybackService::class.java)
                                if (isPlayingNow) {
                                    try {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(svc) else ctx.startService(svc)
                                    } catch (_: Exception) { try { ctx.startService(svc) } catch (_: Exception) {} }
                                } else {
                                    try { ctx.stopService(svc) } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                        try {
                            val ctx = appContext
                            if (ctx != null) {
                               com.example.moniq.SessionStore.saveLastTrack(ctx, currentTrackId, player?.currentPosition ?: 0L, currentTitle.value, currentArtist.value, currentAlbumArt.value, isPlayingNow, currentAlbumId.value, currentAlbumName.value)
                            }
                        } catch (_: Exception) {}
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        try {
                            val meta = mediaItem?.mediaMetadata
                            currentTitle.postValue(meta?.title?.toString())
                            currentArtist.postValue(meta?.artist?.toString())
                            currentAlbumArt.postValue(meta?.artworkUri?.toString())
                            // prefer explicit albumTitle, fallback to extras if present
                            val albumTitle = meta?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                                ?: mediaItem?.mediaMetadata?.extras?.getString("albumName")
                            currentAlbumName.postValue(albumTitle)
                            // compute dominant color asynchronously when artwork changes
                            try {
                                val art = meta?.artworkUri?.toString()
                                if (!art.isNullOrBlank()) {
                                    Thread {
                                        try {
                                            val conn = java.net.URL(art).openConnection()
                                            conn.connectTimeout = 3000
                                            conn.readTimeout = 3000
                                            val bytes = conn.getInputStream().readBytes()
                                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            if (bmp != null) {
                                                val small = android.graphics.Bitmap.createScaledBitmap(bmp, 20, 20, true)
                                                var r = 0L; var g = 0L; var b = 0L; var cnt = 0L
                                                for (x in 0 until small.width) for (y in 0 until small.height) {
                                                    val c = small.getPixel(x, y)
                                                    r += android.graphics.Color.red(c)
                                                    g += android.graphics.Color.green(c)
                                                    b += android.graphics.Color.blue(c)
                                                    cnt++
                                                }
                                                if (cnt > 0) {
                                                    val avg = android.graphics.Color.rgb((r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt())
                                                    currentDominantColor.postValue(avg)
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }.start()
                                } else {
                                    currentDominantColor.postValue(null)
                                }
                            } catch (_: Exception) {}
                            // ensure observers know which queue index is now active
                            currentQueueIndex.postValue(player?.currentMediaItemIndex ?: 0)
                            // try to read track id from tag if present
                            try {
                                currentTrackId = mediaItem?.localConfiguration?.tag as? String
                            } catch (_: Exception) {}
                            try {
                                val extras = mediaItem?.mediaMetadata?.extras
                                val aid = extras?.getString("albumId")
                                if (!aid.isNullOrBlank()) currentAlbumId.postValue(aid) else currentAlbumId.postValue(null)
                            } catch (_: Exception) {}
                            try {
                                val ctx = appContext
                                if (ctx != null) {
                                    com.example.moniq.SessionStore.saveLastTrack(ctx, currentTrackId, 0L, currentTitle.value, currentArtist.value, currentAlbumArt.value, player?.isPlaying == true, currentAlbumId.value, currentAlbumName.value)
                                }
                            } catch (_: Exception) {}
                        } catch (_: Exception) {}
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
    when (playbackState) {
        Player.STATE_ENDED -> {
            // Queue has finished - do nothing, just stay idle
            // Player will show last track's metadata
            isPlaying.postValue(false)
            try {
                val ctx = appContext
                if (ctx != null) {
                    // Stop the foreground service since we're done
                    val svc = android.content.Intent(ctx, PlaybackService::class.java)
                    ctx.stopService(svc)
                }
            } catch (_: Exception) {}
        }
        Player.STATE_READY -> {
            // Ready to play
        }
        Player.STATE_BUFFERING -> {
            // Buffering
        }
        Player.STATE_IDLE -> {
            // Idle
        }
    }
}
                }
        )
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
        val host = SessionManager.host ?: return
        val username = SessionManager.username ?: ""
        val passwordRaw = SessionManager.password ?: ""
        val legacy = SessionManager.legacy
        val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)

        val base = run {
            var b = host.trim()
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
            if (!b.endsWith("/")) b += "/"
            b
        }

        val items =
                tracks.map { tr ->
                    val qU = URLEncoder.encode(username, "UTF-8")
                    val qP = URLEncoder.encode(pwParam, "UTF-8")
                    val qId = URLEncoder.encode(tr.id, "UTF-8")
                    val url = "${base}rest/stream.view?u=$qU&p=$qP&id=$qId&v=1.16.1&c=Moniq"
                    val meta =
                            MediaMetadata.Builder()
                                    .apply {
                                        if (!tr.title.isNullOrEmpty()) setTitle(tr.title)
                                        if (!tr.artist.isNullOrEmpty()) setArtist(tr.artist)
                                        if (!tr.albumName.isNullOrBlank()) setAlbumTitle(tr.albumName)
                                        val coverId = tr.coverArtId ?: tr.albumId ?: tr.id
                                        if (!coverId.isNullOrEmpty()) {
                                            if (coverId.startsWith("http")) {
                                                try { setArtworkUri(android.net.Uri.parse(coverId)) } catch (_: Exception) {}
                                            } else if (SessionManager.host != null) {
                                                try {
                                                    val art = android.net.Uri.parse(SessionManager.host)
                                                            .buildUpon()
                                                            .appendPath("rest").appendPath("getCoverArt.view")
                                                            .appendQueryParameter("id", coverId)
                                                            .appendQueryParameter("u", SessionManager.username ?: "")
                                                            .appendQueryParameter("p", SessionManager.password ?: "")
                                                            .build()
                                                    setArtworkUri(art)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        // attach extras so UI can navigate to album/artist if needed
                                        try {
                                            val extras = android.os.Bundle()
                                            tr.albumId?.let { extras.putString("albumId", it) }
                                            tr.albumName?.let { extras.putString("albumName", it) }
                                            extras.putString("trackArtist", tr.artist)
                                            setExtras(extras)
                                        } catch (_: Exception) {}
                                    }
                                    .build()
                    MediaItem.Builder().setUri(url).setMediaMetadata(meta).setTag(tr.id).build()
                }
        queue.clear()
        queue.addAll(items)
        player?.setMediaItems(queue, startIndex, 0L)
        player?.prepare()
        player?.play()
        // update queue live data
        queueTracks.postValue(
                items.map { mi ->
                    val meta = mi.mediaMetadata
                    val id =
                            try {
                                mi.localConfiguration?.tag as? String ?: ""
                            } catch (_: Exception) {
                                ""
                            }
                    com.example.moniq.model.Track(
                            id,
                            meta?.title?.toString() ?: "",
                            meta?.artist?.toString() ?: "",
                            0,
                            albumId = null,
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
                // store a minimal Track entry for recently played with album info
                val extras = firstMeta?.extras
                val tmp =
                        com.example.moniq.model.Track(
                                trId,
                                firstMeta?.title?.toString() ?: "",
                                firstMeta?.artist?.toString() ?: "",
                                0,
                                albumId = extras?.getString("albumId"),
                                albumName = extras?.getString("albumName"),
                                coverArtId = firstMeta?.artworkUri?.toString()
                        )
                RecentlyPlayedManager(appContext!!).add(tmp)
                    try {
                       val extras = firstMeta?.extras
com.example.moniq.SessionStore.saveLastTrack(appContext!!, trId, 0L, firstMeta?.title?.toString(), firstMeta?.artist?.toString(), firstMeta?.artworkUri?.toString(), true, extras?.getString("albumId"), extras?.getString("albumName"))
                    } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun playAt(index: Int) {
        try {
            player?.seekTo(index, 0L)
        } catch (_: Exception) {}
        currentQueueIndex.postValue(index)
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
                // For non-current removals, instruct ExoPlayer to remove the item without resetting
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
                            val id =
                                    try {
                                        mi.localConfiguration?.tag as? String ?: ""
                                    } catch (_: Exception) {
                                        ""
                                    }
                            com.example.moniq.model.Track(
                                    id,
                                    meta?.title?.toString() ?: "",
                                    meta?.artist?.toString() ?: "",
                                    0,
                                    albumId = null,
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
    fun addToQueue(track: com.example.moniq.model.Track) {
        try {
            initialize(appContext ?: return)
            val item = buildMediaItemForTrack(track)
            queue.add(item)
            try {
                player?.addMediaItem(item)
            } catch (_: Exception) {}
            try {
                player?.prepare()
            } catch (_: Exception) {}
            // update live data
            queueTracks.postValue(
                    queue.map { mi ->
                        val meta = mi.mediaMetadata
                        val id =
                                try {
                                    mi.localConfiguration?.tag as? String ?: ""
                                } catch (_: Exception) {
                                    ""
                                }
                        com.example.moniq.model.Track(
                                id,
                                meta?.title?.toString() ?: "",
                                meta?.artist?.toString() ?: "",
                                0,
                                albumId = null,
                                coverArtId = meta?.artworkUri?.toString()
                        )
                    }
            )
        } catch (_: Exception) {}
    }

    // Insert a single track immediately after the current playing index
    fun playNext(track: com.example.moniq.model.Track) {
        try {
            initialize(appContext ?: return)
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
                        val id =
                                try {
                                    mi.localConfiguration?.tag as? String ?: ""
                                } catch (_: Exception) {
                                    ""
                                }
                        com.example.moniq.model.Track(
                                id,
                                meta?.title?.toString() ?: "",
                                meta?.artist?.toString() ?: "",
                                0,
                                albumId = null,
                                coverArtId = meta?.artworkUri?.toString()
                        )
                    }
            )
        } catch (_: Exception) {}
    }

    // Append multiple tracks to the end of the queue
    fun addToQueue(tracks: List<com.example.moniq.model.Track>) {
        for (t in tracks) addToQueue(t)
    }

    // Insert multiple tracks immediately after the current playing index
    fun playNext(tracks: List<com.example.moniq.model.Track>) {
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
                        val id =
                                try {
                                    mi.localConfiguration?.tag as? String ?: ""
                                } catch (_: Exception) {
                                    ""
                                }
                        com.example.moniq.model.Track(
                                id,
                                meta?.title?.toString() ?: "",
                                meta?.artist?.toString() ?: "",
                                0,
                                albumId = null,
                                coverArtId = meta?.artworkUri?.toString()
                        )
                    }
            )
        } catch (_: Exception) {}
    }

    private fun buildMediaItemForTrack(tr: com.example.moniq.model.Track): MediaItem {
        val host = SessionManager.host ?: ""
        val username = SessionManager.username ?: ""
        val passwordRaw = SessionManager.password ?: ""
        val legacy = SessionManager.legacy
        val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)
        val base = run {
            var b = host.trim()
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
            if (!b.endsWith("/")) b += "/"
            b
        }
        val qU = URLEncoder.encode(username, "UTF-8")
        val qP = URLEncoder.encode(pwParam, "UTF-8")
        val qId = URLEncoder.encode(tr.id, "UTF-8")
        val url = "${base}rest/stream.view?u=$qU&p=$qP&id=$qId&v=1.16.1&c=Moniq"
        val meta =
            MediaMetadata.Builder()
                .apply {
                    if (!tr.title.isNullOrEmpty()) setTitle(tr.title)
                    if (!tr.artist.isNullOrEmpty()) setArtist(tr.artist)
                    if (!tr.albumName.isNullOrBlank()) setAlbumTitle(tr.albumName)
                    val coverId = tr.coverArtId ?: tr.albumId ?: tr.id
                    if (!coverId.isNullOrEmpty() && SessionManager.host != null) {
                    val art =
                        android.net.Uri.parse(SessionManager.host)
                            .buildUpon()
                            .appendPath("rest")
                            .appendPath("getCoverArt.view")
                            .appendQueryParameter("id", coverId)
                            .appendQueryParameter(
                                "u",
                                SessionManager.username ?: ""
                            )
                            .appendQueryParameter(
                                "p",
                                SessionManager.password ?: ""
                            )
                            .build()
                    setArtworkUri(art)
                    }
                    try {
                    val extras = android.os.Bundle()
                    tr.albumId?.let { extras.putString("albumId", it) }
                    tr.albumName?.let { extras.putString("albumName", it) }
                    extras.putString("trackArtist", tr.artist)
                    setExtras(extras)
                    } catch (_: Exception) {}
                }
                .build()
        return MediaItem.Builder().setUri(url).setMediaMetadata(meta).setTag(tr.id).build()
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
                        val id =
                                try {
                                    mi.localConfiguration?.tag as? String ?: ""
                                } catch (_: Exception) {
                                    ""
                                }
                        com.example.moniq.model.Track(
                                id,
                                meta?.title?.toString() ?: "",
                                meta?.artist?.toString() ?: "",
                                0,
                                albumId = null,
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
        setQueue(listOf(temp), 0)
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

                // Use a single compact notification layout with actions: Prev, Play/Pause, Next, Queue
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
                    flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
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
                val subtitle = if (!albumText.isNullOrBlank()) "$artistText — $albumText" else artistText
                rv.setTextViewText(R.id.notif_subtitle, subtitle)
                try {
                rv.setImageViewResource(R.id.notif_cover, R.mipmap.ic_launcher)
                } catch (_: Exception) {}
                builder.setCustomContentView(rv)

            // If we have a cached bitmap from a previous fetch, reuse it to avoid flicker
            if (lastNotifiedBitmap != null) {
                try { builder.setLargeIcon(lastNotifiedBitmap) } catch (_: Exception) {}
                try { rv.setImageViewBitmap(R.id.notif_cover, lastNotifiedBitmap) } catch (_: Exception) {}
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
                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
    try {
        val last = com.example.moniq.SessionStore.loadLastTrack(context) ?: return
        val tr = com.example.moniq.model.Track(last.id, last.title ?: "", last.artist ?: "", 0, albumId = last.albumId, albumName = last.albumName, coverArtId = last.artUrl)
        initialize(context)
        val item = buildMediaItemForTrack(tr)
        queue.clear()
        queue.add(item)
        player?.setMediaItems(queue, 0, last.posMs)
        player?.prepare()
        try { player?.pause() } catch (_: Exception) {}
        currentTrackId = last.id
        currentTitle.postValue(last.title)
        currentArtist.postValue(last.artist)
        currentAlbumArt.postValue(last.artUrl)
        currentAlbumName.postValue(last.albumName)
        currentAlbumId.postValue(last.albumId)
        isPlaying.postValue(false)
    } catch (_: Exception) {}
}
}
