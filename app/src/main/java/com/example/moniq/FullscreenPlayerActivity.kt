package com.example.moniq

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.moniq.player.AudioPlayer
import com.example.moniq.player.DownloadManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ProgressBar
import androidx.media3.common.Player
import android.view.Choreographer


class FullscreenPlayerActivity : ComponentActivity() {
    companion object {
        private val lyricsCache = mutableMapOf<String, List<*>>()
    }

    private var lyricsLoadedFor: String? = null
    private var sliderJob: Job? = null
    private var lyricsChoreographer: Choreographer.FrameCallback? = null
    private var timeJob: Job? = null
    private var lyricsFetchJob: Job? = null
    private var showRomanization = true
    private var showTranslation = true

    private fun loadLyricsIfNeeded(lyricsView: View?) {
        val title = AudioPlayer.currentTitle.value
        val artist = AudioPlayer.currentArtist.value
        val album = AudioPlayer.currentAlbumName.value
        val durSec = (AudioPlayer.duration() / 1000).toInt()
        if (title == null || artist == null) return

        val cacheKey = "$title|$artist|$album"

        if (lyricsLoadedFor == cacheKey) return

        val cachedLyrics = lyricsCache[cacheKey]
        if (cachedLyrics != null) {
            lyricsLoadedFor = cacheKey
            lyricsView?.visibility = View.VISIBLE
            lyricsSetLines(lyricsView, cachedLyrics)
            val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this, true)
            val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this, true)
            lyricsSetShowTransliteration(lyricsView, showRomanization)
            lyricsSetShowTranslation(lyricsView, showTranslation)
            lyricsUpdatePosition(lyricsView, AudioPlayer.currentPosition())
            updateLyricsToggleButtonsVisibility(lyricsView)  // ADD THIS LINE
            lyricsView?.post {
                try {
                    lyricsView.visibility = View.VISIBLE
                } catch (_: Exception) {}
            }
            Toast.makeText(this, "Loaded from cache", Toast.LENGTH_SHORT).show()
            return
        }

        lyricsLoadedFor = cacheKey
        lyricsClear(lyricsView)

        val progressBar =
                android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        progressBar.isIndeterminate = true
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(24, 24, 24, 24)
        container.addView(progressBar)
        val dlg =
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Fetching lyrics...")
                        .setView(container)
                        .setNegativeButton("Cancel") { _, _ ->
                            try {
                                lyricsFetchJob?.cancel()
                            } catch (_: Exception) {}
                            lyricsLoadedFor = null
                        }
                        .setCancelable(false)
                        .show()

        lyricsFetchJob =
                lifecycleScope.launch {
                    try {
                        val result =
                                withContext(Dispatchers.IO) {
                                    com.example.moniq.network.LyricsFetcher.fetchLyrics(
                                            title,
                                            artist,
                                            album,
                                            durSec
                                    )
                                }
                        if (result.lines.isNotEmpty()) {
                            lyricsCache[cacheKey] = result.lines

                            lyricsView?.visibility = View.VISIBLE
                            lyricsSetLines(lyricsView, result.lines)
                            val showRomanization =
                                    com.example.moniq.SessionStore.loadShowRomanization(
                                            this@FullscreenPlayerActivity,
                                            true
                                    )
                            val showTranslation =
                                    com.example.moniq.SessionStore.loadShowTranslation(
                                            this@FullscreenPlayerActivity,
                                            true
                                    )
                            lyricsSetShowTransliteration(lyricsView, showRomanization)
                            lyricsSetShowTranslation(lyricsView, showTranslation)
                            lyricsUpdatePosition(lyricsView, AudioPlayer.currentPosition())
                            updateLyricsToggleButtonsVisibility(lyricsView)  // ADD THIS LINE
                            lyricsView?.post {
                                try {
                                    lyricsView.visibility = View.VISIBLE
                                } catch (_: Exception) {}
                            }

                            runOnUiThread {
                                Toast.makeText(
                                                this@FullscreenPlayerActivity,
                                                "Fetched successfully from: ${result.successfulUrl ?: "unknown"}",
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                            }
                        } else {
                            lyricsView?.visibility = View.GONE
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                                this@FullscreenPlayerActivity,
                                                "No lyrics found",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                            runOnUiThread {
                                try {
                                    val info =
                                            android.widget.TextView(this@FullscreenPlayerActivity)
                                    info.text = "Tried all backends"
                                    info.setPadding(24, 24, 24, 24)
                                    com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                                    this@FullscreenPlayerActivity
                                            )
                                            .setTitle("Lyrics not found")
                                            .setView(info)
                                            .setPositiveButton("Edit search") { _, _ ->
                                                showEditSearchDialog(
                                                        title,
                                                        artist,
                                                        album,
                                                        lyricsView,
                                                        durSec
                                                )
                                            }
                                            .setNegativeButton("Close", null)
                                            .show()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            // user cancelled
                        } else {
                            runOnUiThread {
                                try {
                                    val tv = android.widget.TextView(this@FullscreenPlayerActivity)
                                  tv.text = "Attempted all backends"
                                    tv.setPadding(24, 24, 24, 24)
                                    com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                                    this@FullscreenPlayerActivity
                                            )
                                            .setTitle("Lyrics fetch failed")
                                            .setView(tv)
                                            .setPositiveButton("Edit search") { _, _ ->
                                                showEditSearchDialog(
                                                        title,
                                                        artist,
                                                        album,
                                                        lyricsView,
                                                        durSec
                                                )
                                            }
                                            .setNegativeButton("Close", null)
                                            .show()
                                } catch (_: Exception) {
                                    android.util.Log.w(
                                            "FullscreenPlayer",
                                            "failed to show lyrics error dialog",
                                            e
                                    )
                                }
                            }
                            lyricsLoadedFor = null
                        }
                    } finally {
                        try {
                            dlg.dismiss()
                        } catch (_: Exception) {}
                        lyricsFetchJob = null
                    }
                }
    }

    private suspend fun loadLyricsRetry(
            nTitle: String?,
            nArtist: String?,
            nAlbum: String?,
            lyricsView: View?,
            durSec: Int
    ) {
        try {
            val result =
                    withContext(Dispatchers.IO) {
                        com.example.moniq.network.LyricsFetcher.fetchLyrics(
                                nTitle,
                                nArtist,
                                nAlbum,
                                durSec
                        )
                    }
            if (result.lines.isNotEmpty()) {
                val cacheKey = "$nTitle|$nArtist|$nAlbum"
                lyricsCache[cacheKey] = result.lines

                runOnUiThread {
                    lyricsView?.visibility = View.VISIBLE
                    lyricsSetLines(lyricsView, result.lines)
                    val showRomanization =
                            com.example.moniq.SessionStore.loadShowRomanization(
                                    this@FullscreenPlayerActivity,
                                    true
                            )
                    val showTranslation =
                            com.example.moniq.SessionStore.loadShowTranslation(
                                    this@FullscreenPlayerActivity,
                                    true
                            )
                    lyricsSetShowTransliteration(lyricsView, showRomanization)
                    lyricsSetShowTranslation(lyricsView, showTranslation)
                    updateLyricsToggleButtonsVisibility(lyricsView)  // ADD THIS LINE
                    lyricsView?.post {
                        try {
                            lyricsView.visibility = View.VISIBLE
                        } catch (_: Exception) {}
                    }
                    Toast.makeText(
                                    this@FullscreenPlayerActivity,
                                    "Fetched from: ${result.successfulUrl ?: "unknown"}",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(
                                    this@FullscreenPlayerActivity,
                                    "No lyrics found",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                    showEditSearchDialog(nTitle, nArtist, nAlbum, lyricsView, durSec)
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(
                                this@FullscreenPlayerActivity,
                                "Retry failed: ${e.message}",
                                Toast.LENGTH_SHORT
                        )
                        .show()
                showEditSearchDialog(nTitle, nArtist, nAlbum, lyricsView, durSec)
            }
        }
    }

    private fun showEditSearchDialog(
            title: String?,
            artist: String?,
            album: String?,
            lyricsView: View?,
            durSec: Int
    ) {
        val container =
                android.widget.LinearLayout(this@FullscreenPlayerActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
        val titleInput =
                android.widget.EditText(this@FullscreenPlayerActivity).apply {
                    hint = "Title (optional)"
                    setSingleLine(true)
                    setText(title ?: "")
                }
        val artistInput =
                android.widget.EditText(this@FullscreenPlayerActivity).apply {
                    hint = "Artist (optional)"
                    setSingleLine(true)
                    setText(artist ?: "")
                }
        val albumInput =
                android.widget.EditText(this@FullscreenPlayerActivity).apply {
                    hint = "Album (optional)"
                    setSingleLine(true)
                    setText(album ?: "")
                }
        container.addView(titleInput)
        container.addView(artistInput)
        container.addView(albumInput)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FullscreenPlayerActivity)
                .setTitle("Edit lyrics search")
                .setView(container)
                .setPositiveButton("Search") { _, _ ->
                    val rawTitle = titleInput.text?.toString()?.trim()
                    val rawArtist = artistInput.text?.toString()?.trim()
                    val rawAlbum = albumInput.text?.toString()?.trim()
                    val nTitle = if (!rawTitle.isNullOrEmpty()) rawTitle else title
                    val nArtist = if (!rawArtist.isNullOrEmpty()) rawArtist else artist
                    val nAlbum = if (!rawAlbum.isNullOrEmpty()) rawAlbum else album
                    lyricsLoadedFor = null
                    lifecycleScope.launch {
                        loadLyricsRetry(nTitle, nArtist, nAlbum, lyricsView, durSec)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_player)

        val gradientBackground =
                findViewById<com.example.moniq.views.GradientBackgroundView>(
                        R.id.gradient_background
                )
        val playerContent = findViewById<ConstraintLayout>(R.id.player_content)
        val lyricsModeContainer = findViewById<ConstraintLayout>(R.id.lyrics_mode_container)

        val titleView = findViewById<TextView>(R.id.full_title)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener { finish() }

        // Setup toolbar actions...
        try {
            val artistItem = toolbar.menu.add("Artist")
            artistItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            artistItem.setIcon(android.R.drawable.ic_menu_myplaces)
            artistItem.setOnMenuItemClickListener {
                val name = AudioPlayer.currentArtist.value ?: ""
                if (name.isBlank()) {
                    Toast.makeText(this, "No artist available", Toast.LENGTH_SHORT).show()
                } else {
                    lifecycleScope.launch {
                        try {
                            val repo = com.example.moniq.search.SearchRepository()
                            val res =
                                    try {
                                        repo.search(name)
                                    } catch (e: Exception) {
                                        null
                                    }
                            val match = res?.artists?.firstOrNull()
                            val artistId = match?.id
                            val cover = match?.coverArtId
                            if (!artistId.isNullOrBlank()) {
                                val intent =
                                        android.content.Intent(
                                                this@FullscreenPlayerActivity,
                                                ArtistActivity::class.java
                                        )
                                intent.putExtra("artistId", artistId)
                                intent.putExtra("artistName", name)
                                if (!cover.isNullOrBlank()) intent.putExtra("artistCoverId", cover)
                                try {
                                    runOnUiThread { startActivity(intent) }
                                } catch (_: Exception) {
                                    startActivity(intent)
                                }
                            } else {
                                Toast.makeText(
                                                this@FullscreenPlayerActivity,
                                                "Artist not found",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                            this@FullscreenPlayerActivity,
                                            "Artist lookup failed",
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                    }
                }
                true
            }

            val albumItem = toolbar.menu.add("Album")
            albumItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            albumItem.setIcon(android.R.drawable.ic_menu_gallery)
            albumItem.setOnMenuItemClickListener {
                val albumName = AudioPlayer.currentAlbumName.value ?: ""
                val artistName = AudioPlayer.currentArtist.value ?: ""
                val albumIdLive = AudioPlayer.currentAlbumId.value
                if (!albumIdLive.isNullOrBlank()) {
                    val intent =
                            android.content.Intent(
                                    this@FullscreenPlayerActivity,
                                    AlbumActivity::class.java
                            )
                    intent.putExtra("albumId", albumIdLive)
                    intent.putExtra("albumTitle", albumName)
                    intent.putExtra("albumArtist", artistName)
                    startActivity(intent)
                } else if (albumName.isBlank()) {
                    Toast.makeText(this, "No album available", Toast.LENGTH_SHORT).show()
                } else {
                    lifecycleScope.launch {
                        try {
                            val q =
                                    if (artistName.isBlank()) albumName
                                    else "$albumName $artistName"
                            val repo = com.example.moniq.search.SearchRepository()
                            val res =
                                    try {
                                        repo.search(q)
                                    } catch (e: Exception) {
                                        null
                                    }
                            val albumId = res?.albums?.firstOrNull()?.id
                            val alb = res?.albums?.firstOrNull()
                            if (!albumId.isNullOrBlank() && alb != null) {
                                val intent =
                                        android.content.Intent(
                                                this@FullscreenPlayerActivity,
                                                AlbumActivity::class.java
                                        )
                                intent.putExtra("albumId", albumId)
                                intent.putExtra("albumTitle", alb.name)
                                intent.putExtra("albumArtist", alb.artist)
                                startActivity(intent)
                            } else {
                                Toast.makeText(
                                                this@FullscreenPlayerActivity,
                                                "Album not found",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                            this@FullscreenPlayerActivity,
                                            "Album lookup failed",
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                    }
                }
                true
            }

            // Add this in onCreate() after the albumItem code (around line 145):

val infoItem = toolbar.menu.add("Info")
infoItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
infoItem.setIcon(android.R.drawable.ic_menu_info_details)
infoItem.setOnMenuItemClickListener {
    val trackId = AudioPlayer.currentTrackId
    if (trackId.isNullOrBlank()) {
        Toast.makeText(this, "No track selected", Toast.LENGTH_SHORT).show()
    } else {
        showTrackInfo(trackId)
    }
    true
}
        } catch (_: Exception) {}

        // Setup landscape view artist/album buttons
        val viewArtistBtn = findViewById<ImageButton>(R.id.view_artist_btn)
        val viewAlbumBtn = findViewById<ImageButton>(R.id.view_album_btn)
        
        viewArtistBtn?.setOnClickListener {
            val name = AudioPlayer.currentArtist.value ?: ""
            if (name.isBlank()) {
                Toast.makeText(this, "No artist available", Toast.LENGTH_SHORT).show()
            } else {
                lifecycleScope.launch {
                    try {
                        val repo = com.example.moniq.search.SearchRepository()
                        val res = try { repo.search(name) } catch (e: Exception) { null }
                        val match = res?.artists?.firstOrNull()
                        val artistId = match?.id
                        val cover = match?.coverArtId
                        if (!artistId.isNullOrBlank()) {
                            val intent = android.content.Intent(this@FullscreenPlayerActivity, ArtistActivity::class.java)
                            intent.putExtra("artistId", artistId)
                            intent.putExtra("artistName", name)
                            if (!cover.isNullOrBlank()) intent.putExtra("artistCoverId", cover)
                            startActivity(intent)
                        } else {
                            Toast.makeText(this@FullscreenPlayerActivity, "Artist not found", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@FullscreenPlayerActivity, "Artist lookup failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        viewAlbumBtn?.setOnClickListener {
            val albumName = AudioPlayer.currentAlbumName.value ?: ""
            val artistName = AudioPlayer.currentArtist.value ?: ""
            val albumIdLive = AudioPlayer.currentAlbumId.value
            if (!albumIdLive.isNullOrBlank()) {
                val intent = android.content.Intent(this@FullscreenPlayerActivity, AlbumActivity::class.java)
                intent.putExtra("albumId", albumIdLive)
                intent.putExtra("albumTitle", albumName)
                intent.putExtra("albumArtist", artistName)
                startActivity(intent)
            } else if (albumName.isBlank()) {
                Toast.makeText(this, "No album available", Toast.LENGTH_SHORT).show()
            } else {
                lifecycleScope.launch {
                    try {
                        val q = if (artistName.isBlank()) albumName else "$albumName $artistName"
                        val repo = com.example.moniq.search.SearchRepository()
                        val res = try { repo.search(q) } catch (e: Exception) { null }
                        val albumId = res?.albums?.firstOrNull()?.id
                        val alb = res?.albums?.firstOrNull()
                        if (!albumId.isNullOrBlank() && alb != null) {
                            val intent = android.content.Intent(this@FullscreenPlayerActivity, AlbumActivity::class.java)
                            intent.putExtra("albumId", albumId)
                            intent.putExtra("albumTitle", alb.name)
                            intent.putExtra("albumArtist", alb.artist)
                            startActivity(intent)
                        } else {
                            Toast.makeText(this@FullscreenPlayerActivity, "Album not found", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@FullscreenPlayerActivity, "Album lookup failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        val artistView = findViewById<TextView>(R.id.full_artist)
        val artView = findViewById<ImageView>(R.id.full_art)
        val prevBtn = findViewById<ImageButton>(R.id.full_prev)
        val nextBtn = findViewById<ImageButton>(R.id.full_next)
        val playPause = findViewById<ImageButton>(R.id.full_play_pause)
        val progressSlider = findViewById<Slider>(R.id.progressSlider)
        val downloadBtn = findViewById<MaterialButton>(R.id.full_download)
        val queueBtn = findViewById<MaterialButton>(R.id.full_queue)

        AudioPlayer.currentArtist.observe(this) { a -> artistView?.text = a ?: "" }

// Add loading indicator observer
val fullLoading = findViewById<ProgressBar>(R.id.fullLoading)
AudioPlayer.playbackState.observe(this) { state ->
    val isBuffering = state == Player.STATE_BUFFERING
    fullLoading?.visibility = if (isBuffering) View.VISIBLE else View.GONE
}

// Add quality display observers
AudioPlayer.currentTrackQuality.observe(this) { _ -> updateQualityDisplay() }
AudioPlayer.currentBitDepth.observe(this) { _ -> updateQualityDisplay() }
AudioPlayer.currentSampleRate.observe(this) { _ -> updateQualityDisplay() }

// REPLACEMENT: use same behavior as SearchActivity (do NOT fall back to track id)
AudioPlayer.currentAlbumArt.observe(this) { artUrl ->
    android.util.Log.d("FullscreenPlayer", "currentAlbumArt observe: artUrl='$artUrl' currentTrackId='${AudioPlayer.currentTrackId}'")

    // Only use the album art value provided by AudioPlayer (as SearchActivity does).
    // Do NOT fall back to building a proxy URL from numeric track id.
    val loadUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(artUrl)

    android.util.Log.d("FullscreenPlayer", "getCoverArtUrl returned='$loadUrl' for artUrl='$artUrl'")

    if (loadUrl.isNullOrBlank()) {
        artView?.setImageResource(android.R.drawable.ic_menu_report_image)
        android.util.Log.w("FullscreenPlayer", "No loadUrl (albumArt missing) -> showing placeholder")
    } else {
        artView?.load(loadUrl) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_report_image)
            error(android.R.drawable.ic_menu_report_image)
            transformations(coil.transform.RoundedCornersTransformation(16f))
            size(1024)
            scale(coil.size.Scale.FILL)
            listener(
                onSuccess = { _, result ->
                    android.util.Log.d("FullscreenPlayer", "Coil success loading cover")
                    try {
                        val dr = result.drawable
                        val bmp = (dr as? BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            val colors = extractDominantColors(bmp)
                            gradientBackground?.setColors(colors)
                            val primaryColor = colors.firstOrNull() ?: 0xFF6200EE.toInt()
                            applyAccentColor(primaryColor, playPause, prevBtn, nextBtn, progressSlider, downloadBtn, queueBtn)
                            val lyricsMiniArt = findViewById<ImageView>(R.id.lyrics_mini_art)
                            lyricsMiniArt?.setImageBitmap(bmp)
                        }
                    } catch (_: Exception) {}
                },
                onError = { request, result ->
                    // Coil v2 error listener gives an ImageResult containing a throwable — log it.
                    android.util.Log.e("FullscreenPlayer", "Coil failed to load cover: request=${request?.data} error=${result.throwable?.message}", result.throwable)
                }
            )
        }
    }
}




        AudioPlayer.currentTitle.observe(this) { title ->
            titleView?.text = title ?: ""
            // Update lyrics mini title
            val lyricsMiniTitle = findViewById<TextView>(R.id.lyrics_mini_title)
            lyricsMiniTitle?.text = title ?: ""

            val toggleLyrics = findViewById<MaterialButton>(R.id.full_toggle_lyrics)
            if (toggleLyrics?.isChecked == true) {
                lyricsLoadedFor = null
                val lyricsView = findViewById<View>(R.id.full_lyrics_view)
                lifecycleScope.launch {
                    delay(100)
                    try {
                        loadLyricsIfNeeded(lyricsView)
                    } catch (_: Exception) {}
                }
            }
        }

        AudioPlayer.currentArtist.observe(this) { artist ->
            // Update lyrics mini artist
            val lyricsMiniArtist = findViewById<TextView>(R.id.lyrics_mini_artist)
            lyricsMiniArtist?.text = artist ?: ""
        }

        AudioPlayer.isPlaying.observe(this) { playing ->
            val icon =
                    if (playing == true) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
            playPause.setImageResource(icon)
            playPause
                    ?.animate()
                    ?.scaleX(if (playing == true) 1.05f else 0.95f)
                    ?.scaleY(if (playing == true) 1.05f else 0.95f)
                    ?.setDuration(120)
                    ?.withEndAction {
                        playPause.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
                    }
                    ?.start()
        }

        val fullCurrent = findViewById<TextView>(R.id.full_current_time)
        val fullTotal = findViewById<TextView>(R.id.full_total_time)

        playPause.setOnClickListener { AudioPlayer.togglePlayPause() }


        AudioPlayer.isPlaying.value?.let { wasPlaying ->
    if (wasPlaying) {
        // Already playing, just observe
    } else {
        // Check if there's a track loaded and not at the end
        val dur = AudioPlayer.duration()
        val pos = AudioPlayer.currentPosition()
        if (dur > 0 && pos < dur - 1000) {
            // Track is loaded but paused, clicking the button should work
        }
    }
}

        prevBtn?.setOnClickListener { AudioPlayer.previous() }
        nextBtn?.setOnClickListener { AudioPlayer.next() }

        downloadBtn?.setOnClickListener {
    val trackId = AudioPlayer.currentTrackId
    val title = AudioPlayer.currentTitle.value
    val artist = AudioPlayer.currentArtist.value
    val albumName = AudioPlayer.currentAlbumName.value
    val albumArt = AudioPlayer.currentAlbumArt.value
    
    if (trackId == null) {
        Toast.makeText(this, "No track selected to download", Toast.LENGTH_SHORT).show()
    } else {
        lifecycleScope.launch {
            // Show progress dialog
            val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FullscreenPlayerActivity)
                .setTitle("Downloading")
                .setMessage("$title\n0%")
                .setCancelable(false)
                .create()
            progressDialog.show()
            
            try {
                // Find working stream URL using ServerManager
                val streamUrl = withContext(Dispatchers.IO) {
                    val servers = com.example.moniq.util.ServerManager.getOrderedServers()
                    var workingUrl: String? = null
                    
                    for (server in servers) {
                        try {
                            val testUrl = "$server/track/?id=$trackId"
                            val conn = java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            conn.requestMethod = "GET"
                            
                            if (conn.responseCode == 200) {
                                conn.disconnect()
                                workingUrl = "$server/stream/?id=$trackId"
                                com.example.moniq.util.ServerManager.recordSuccess(server)
                                break
                            }
                            conn.disconnect()
                            com.example.moniq.util.ServerManager.recordFailure(server)
                        } catch (e: Exception) {
                            com.example.moniq.util.ServerManager.recordFailure(server)
                            continue
                        }
                    }
                    workingUrl
                }
                
                if (streamUrl == null) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this@FullscreenPlayerActivity,
                        "No working server found",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                
                val albumArtUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(albumArt)
                
                val ok = DownloadManager.downloadTrackWithProgress(
                    this@FullscreenPlayerActivity,
                    trackId,
                    title,
                    artist,
                    albumName,
                    albumArtUrl,
                    streamUrl = streamUrl
                ) { progress ->
                    runOnUiThread {
                        if (progress >= 0) {
                            progressDialog.setMessage("$title\n$progress%")
                        } else {
                            progressDialog.setMessage("$title\nDownloading...")
                        }
                    }
                }
                
                progressDialog.dismiss()
                Toast.makeText(
                    this@FullscreenPlayerActivity,
                    if (ok) "Download complete" else "Download failed",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    this@FullscreenPlayerActivity,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

        queueBtn?.setOnClickListener {
            val intent = android.content.Intent(this, QueueActivity::class.java)
            startActivity(intent)
        }

        val toggleLyrics = findViewById<MaterialButton>(R.id.full_toggle_lyrics)
        val lyricsView = findViewById<View>(R.id.full_lyrics_view)
        val lyricsCloseBtn = findViewById<ImageButton>(R.id.lyrics_close_btn)

        // Setup seek listener for lyrics
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (lyricsView != null && cls.isInstance(lyricsView)) {
                val listener = object : Function1<Long, Unit> {
                            override fun invoke(p1: Long) {
    val posMs = p1
                                AudioPlayer.seekTo(posMs)
                            }
                        }
                val m =
                        cls.getMethod(
                                "setOnSeekListener",
                                kotlin.jvm.functions.Function1::class.java
                        )
                m.invoke(lyricsView, listener)
            }
        } catch (_: Exception) {}

        // Setup romanization and translation toggle buttons
fun setupLyricsToggles() {
    android.util.Log.d("FullscreenPlayer", "setupLyricsToggles: Starting setup")
    
    val toggleRomanization = findViewById<ImageButton>(R.id.lyrics_toggle_romanization)
    val toggleTranslation = findViewById<ImageButton>(R.id.lyrics_toggle_translation)
    val lyricsViewRef = findViewById<View>(R.id.full_lyrics_view)
    
    android.util.Log.d("FullscreenPlayer", "setupLyricsToggles: toggleRomanization=$toggleRomanization, toggleTranslation=$toggleTranslation, lyricsView=$lyricsViewRef")

    fun updateToggleButton(button: ImageButton?, isEnabled: Boolean) {
        android.util.Log.d("FullscreenPlayer", "updateToggleButton: button=$button, isEnabled=$isEnabled")
        button?.alpha = if (isEnabled) 1.0f else 0.4f
    }

    // Load initial states
    showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this, true)
    showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this, true)
    
    android.util.Log.d("FullscreenPlayer", "setupLyricsToggles: Initial states - showRomanization=$showRomanization, showTranslation=$showTranslation")

    updateToggleButton(toggleRomanization, showRomanization)
    updateToggleButton(toggleTranslation, showTranslation)

    toggleRomanization?.setOnClickListener {
        android.util.Log.d("FullscreenPlayer", "toggleRomanization clicked: current state=$showRomanization")
        showRomanization = !showRomanization
        android.util.Log.d("FullscreenPlayer", "toggleRomanization: new state=$showRomanization")
        
        com.example.moniq.SessionStore.saveShowRomanization(this, showRomanization)
        android.util.Log.d("FullscreenPlayer", "toggleRomanization: saved to SessionStore")
        
        lyricsSetShowTransliteration(lyricsViewRef, showRomanization)
        android.util.Log.d("FullscreenPlayer", "toggleRomanization: called lyricsSetShowTransliteration")
        
        updateToggleButton(toggleRomanization, showRomanization)
        android.util.Log.d("FullscreenPlayer", "toggleRomanization: updated button alpha")
    }

    toggleTranslation?.setOnClickListener {
        android.util.Log.d("FullscreenPlayer", "toggleTranslation clicked: current state=$showTranslation")
        showTranslation = !showTranslation
        android.util.Log.d("FullscreenPlayer", "toggleTranslation: new state=$showTranslation")
        
        com.example.moniq.SessionStore.saveShowTranslation(this, showTranslation)
        android.util.Log.d("FullscreenPlayer", "toggleTranslation: saved to SessionStore")
        
        lyricsSetShowTranslation(lyricsViewRef, showTranslation)
        android.util.Log.d("FullscreenPlayer", "toggleTranslation: called lyricsSetShowTranslation")
        
        updateToggleButton(toggleTranslation, showTranslation)
        android.util.Log.d("FullscreenPlayer", "toggleTranslation: updated button alpha")
    }
    
    android.util.Log.d("FullscreenPlayer", "setupLyricsToggles: Setup complete")
}


        // Toggle lyrics button - shows/hides lyrics mode
        toggleLyrics?.setOnClickListener {
            val checked = toggleLyrics.isChecked
            if (checked) {
                try {
                    loadLyricsIfNeeded(lyricsView)
                } catch (_: Exception) {}
                lyricsSetShowTransliteration(lyricsView, showRomanization)
                lyricsSetShowTranslation(lyricsView, showTranslation)

                // Hide player content, show lyrics mode
                playerContent
                        ?.animate()
                        ?.alpha(0f)
                        ?.setDuration(300)
                        ?.withEndAction {
                            playerContent.visibility = View.GONE
                            lyricsModeContainer?.visibility = View.VISIBLE
                            lyricsModeContainer?.alpha = 0f
                            lyricsModeContainer
                                    ?.animate()
                                    ?.alpha(1f)
                                    ?.setDuration(300)
                                    ?.withEndAction {
                                        // Setup toggle buttons AFTER lyrics mode is visible
                                        setupLyricsToggles()
                                    }
                                    ?.start()
                        }
                        ?.start()
            }
        }

        // Close button in lyrics mode
        lyricsCloseBtn?.setOnClickListener {
            // Hide lyrics mode, show player content
            lyricsModeContainer
                    ?.animate()
                    ?.alpha(0f)
                    ?.setDuration(300)
                    ?.withEndAction {
                        lyricsModeContainer.visibility = View.GONE
                        playerContent?.visibility = View.VISIBLE
                        playerContent?.alpha = 0f
                        playerContent.animate()?.alpha(1f)?.setDuration(300)?.start()
                        toggleLyrics?.isChecked = false
                    }
                    ?.start()
        }

        sliderJob =
        lifecycleScope.launch(Dispatchers.Main) {
            var userSeeking = false
            progressSlider?.addOnChangeListener { _, _, fromUser ->
                if (fromUser) userSeeking = true
            }
            progressSlider?.addOnSliderTouchListener(
                    object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: Slider) {
                            userSeeking = true
                        }
                        override fun onStopTrackingTouch(slider: Slider) {
                            userSeeking = false
                            val dur = AudioPlayer.duration()
                            if (dur > 0) {
                                val targetMs = (slider.value / 100f * dur).toLong()
                                AudioPlayer.seekTo(targetMs)
                            }
                        }
                    }
            )
            while (isActive) {
                try {
                    val dur = AudioPlayer.duration()
                    val pos = AudioPlayer.currentPosition()
                    if (!userSeeking && dur > 0) {
                        val percent = (pos.toFloat() / dur.toFloat()) * 100f
                        progressSlider?.value = percent.coerceIn(0f, 100f)  // ADD .coerceIn(0f, 100f)
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }


lyricsChoreographer = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
        try {
            val pos = AudioPlayer.currentPosition()
            lyricsUpdatePosition(lyricsView, pos)
            Choreographer.getInstance().postFrameCallback(this)
        } catch (_: Exception) {}
    }
}
Choreographer.getInstance().postFrameCallback(lyricsChoreographer!!)
timeJob =
                lifecycleScope.launch(Dispatchers.Main) {
                    while (isActive) {
                        val dur = AudioPlayer.duration()
                        val pos = AudioPlayer.currentPosition()
                        if (dur > 0) {
                            fullTotal?.text = formatMs(dur)
                            fullCurrent?.text = formatMs(pos)
                        }
                        delay(500)
                    }
                }
    }

    override fun onResume() {
        super.onResume()
        val lyricsView = findViewById<View>(R.id.full_lyrics_view)
        val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this, true)
        val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this, true)
        lyricsSetShowTransliteration(lyricsView, showRomanization)
        lyricsSetShowTranslation(lyricsView, showTranslation)
        lyricsView?.post {
            try {
                lyricsSetShowTransliteration(lyricsView, showRomanization)
                lyricsSetShowTranslation(lyricsView, showTranslation)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            sliderJob?.cancel()
        } catch (_: Exception) {}
        try {
            lyricsChoreographer?.let {
                Choreographer.getInstance().removeFrameCallback(it)
            }
        } catch (_: Exception) {}
        try {
            timeJob?.cancel()
        } catch (_: Exception) {}
    }

    private fun lyricsClear(view: View?) {
        if (view == null) return
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("clear")
                m.invoke(view)
            }
        } catch (_: Exception) {}
    }

    private fun lyricsSetLines(view: View?, lines: List<*>) {
        if (view == null) return
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("setLines", java.util.List::class.java)
                m.invoke(view, lines)
            }
        } catch (_: Exception) {}
    }

    private fun lyricsUpdatePosition(view: View?, posMs: Long) {
        if (view == null) return
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("updatePosition", java.lang.Long.TYPE)
                m.invoke(view, posMs)
            }
        } catch (_: Exception) {}
    }

    private fun lyricsSetShowTransliteration(view: View?, show: Boolean) {
        android.util.Log.d("FullscreenPlayer", "lyricsSetShowTransliteration: view=$view, show=$show")
        if (view == null) {
            android.util.Log.w("FullscreenPlayer", "lyricsSetShowTransliteration: view is null!")
            return
        }
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            android.util.Log.d("FullscreenPlayer", "lyricsSetShowTransliteration: class loaded, isInstance=${cls.isInstance(view)}")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("setShowTransliteration", Boolean::class.javaPrimitiveType)
                android.util.Log.d("FullscreenPlayer", "lyricsSetShowTransliteration: method found, invoking with show=$show")
                m.invoke(view, show)
                android.util.Log.d("FullscreenPlayer", "lyricsSetShowTransliteration: method invoked successfully")
            } else {
                android.util.Log.w("FullscreenPlayer", "lyricsSetShowTransliteration: view is not an instance of LyricsView")
            }
        } catch (e: Exception) {
            android.util.Log.e("FullscreenPlayer", "lyricsSetShowTransliteration: error", e)
        }
    }

    private fun lyricsSetShowTranslation(view: View?, show: Boolean) {
        android.util.Log.d("FullscreenPlayer", "lyricsSetShowTranslation: view=$view, show=$show")
        if (view == null) {
            android.util.Log.w("FullscreenPlayer", "lyricsSetShowTranslation: view is null!")
            return
        }
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            android.util.Log.d("FullscreenPlayer", "lyricsSetShowTranslation: class loaded, isInstance=${cls.isInstance(view)}")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("setShowTranslation", Boolean::class.javaPrimitiveType)
                android.util.Log.d("FullscreenPlayer", "lyricsSetShowTranslation: method found, invoking with show=$show")
                m.invoke(view, show)
                android.util.Log.d("FullscreenPlayer", "lyricsSetShowTranslation: method invoked successfully")
            } else {
                android.util.Log.w("FullscreenPlayer", "lyricsSetShowTranslation: view is not an instance of LyricsView")
            }
        } catch (e: Exception) {
            android.util.Log.e("FullscreenPlayer", "lyricsSetShowTranslation: error", e)
        }
    }

    private fun updateLyricsToggleButtonsVisibility(lyricsView: View?) {
        try {
            val toggleRomanization = findViewById<ImageButton>(R.id.lyrics_toggle_romanization)
            val toggleTranslation = findViewById<ImageButton>(R.id.lyrics_toggle_translation)
            
            if (lyricsView == null) {
                toggleRomanization?.visibility = View.GONE
                toggleTranslation?.visibility = View.GONE
                return
            }
            
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (!cls.isInstance(lyricsView)) {
                toggleRomanization?.visibility = View.GONE
                toggleTranslation?.visibility = View.GONE
                return
            }
            
            val linesField = cls.getDeclaredField("lines")
            linesField.isAccessible = true
            val lines = linesField.get(lyricsView) as? List<*>
            
            var hasRomanization = false
            var hasTranslation = false
            
            if (lines != null) {
                for (line in lines) {
                    if (line == null) continue
                    
                    val lineClass = line::class.java
                    
                    try {
                        val translitField = lineClass.getDeclaredField("transliteration")
                        translitField.isAccessible = true
                        val translit = translitField.get(line) as? String
                        if (!translit.isNullOrBlank()) {
                            hasRomanization = true
                        }
                    } catch (_: Exception) {}
                    
                    try {
                        val translationField = lineClass.getDeclaredField("translation")
                        translationField.isAccessible = true
                        val translation = translationField.get(line) as? String
                        if (!translation.isNullOrBlank()) {
                            hasTranslation = true
                        }
                    } catch (_: Exception) {}
                    
                    if (hasRomanization && hasTranslation) break
                }
            }
            
            android.util.Log.d("FullscreenPlayer", "updateLyricsToggleButtonsVisibility: hasRomanization=$hasRomanization, hasTranslation=$hasTranslation")
            
            toggleRomanization?.visibility = if (hasRomanization) View.VISIBLE else View.GONE
            toggleTranslation?.visibility = if (hasTranslation) View.VISIBLE else View.GONE
            
        } catch (e: Exception) {
            android.util.Log.e("FullscreenPlayer", "updateLyricsToggleButtonsVisibility: error", e)
        }
    }

    private fun extractDominantColors(bmp: Bitmap): List<Int> {
        return try {
            val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
            val colorCounts = mutableMapOf<Int, Int>()

            for (x in 0 until small.width) for (y in 0 until small.height) {
                val p = small.getPixel(x, y)
                val alpha = (p shr 24) and 0xff
                if (alpha < 128) continue

                val r = ((p shr 16) and 0xff) / 32 * 32
                val g = ((p shr 8) and 0xff) / 32 * 32
                val b = (p and 0xff) / 32 * 32
                val quantized = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1
            }

            val sorted = colorCounts.entries.sortedByDescending { it.value }.take(3)
            sorted.map { it.key }
        } catch (_: Exception) {
            listOf(0xFF6200EE.toInt())
        }
    }

    private fun applyAccentColor(
            color: Int,
            playPause: ImageButton?,
            prev: ImageButton?,
            next: ImageButton?,
            slider: Slider?,
            downloadBtn: MaterialButton?,
            queueBtn: MaterialButton?,
    ) {
        try {
            val csl = ColorStateList.valueOf(color)
            playPause?.backgroundTintList = csl
            prev?.backgroundTintList = ColorStateList.valueOf(adjustAlpha(color, 0.85f))
            next?.backgroundTintList = ColorStateList.valueOf(adjustAlpha(color, 0.85f))
            slider?.trackActiveTintList = ColorStateList.valueOf(color)
            slider?.trackInactiveTintList = ColorStateList.valueOf(adjustAlpha(color, 0.28f))
            slider?.thumbTintList = ColorStateList.valueOf(color)
            downloadBtn?.setTextColor(color)
            queueBtn?.setTextColor(color)
            try {
                downloadBtn?.iconTint = ColorStateList.valueOf(color)
            } catch (_: Exception) {}
            try {
                queueBtn?.iconTint = ColorStateList.valueOf(color)
            } catch (_: Exception) {}
            try {
                val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                toolbar?.overflowIcon?.setTint(color)
            } catch (_: Exception) {}
            try {
                val lv = findViewById<View>(R.id.full_lyrics_view)
                val cls = Class.forName("com.example.moniq.views.LyricsView")
                if (lv != null && cls.isInstance(lv)) {
                    val m = cls.getMethod("setAccentColor", Integer::class.javaPrimitiveType)
                    m.invoke(lv, color)
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = ((color shr 24) and 0xff)
        val r = ((color shr 16) and 0xff)
        val g = ((color shr 8) and 0xff)
        val b = (color and 0xff)
        val na = (a * factor).toInt().coerceIn(0, 255)
        return (na shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun formatMs(ms: Long): String {
        val sec = (ms / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return String.format("%d:%02d", m, s)
    }

    private fun updateQualityDisplay() {
        val qualityView = findViewById<TextView>(R.id.full_quality)
        val quality = AudioPlayer.currentTrackQuality.value
        val bitDepth = AudioPlayer.currentBitDepth.value
        val sampleRate = AudioPlayer.currentSampleRate.value
        
        val parts = mutableListOf<String>()
        
        when (quality) {
            "HI_RES_LOSSLESS", "HI_RES" -> parts.add("Hi-Res Lossless")
            "LOSSLESS" -> parts.add("Lossless")
            "HIGH" -> parts.add("High Quality")
            "LOW" -> parts.add("Standard Quality")
        }
        
        bitDepth?.let { parts.add("${it}-bit") }
        
        sampleRate?.let { 
            val kHz = it / 1000
            parts.add("${kHz} kHz")
        }
        
        if (parts.isNotEmpty()) {
            qualityView?.text = parts.joinToString(" • ")
            qualityView?.visibility = View.VISIBLE
        } else {
            qualityView?.visibility = View.GONE
        }
    }

    private fun showTrackInfo(trackId: String) {
        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Loading track info...")
            .setView(ProgressBar(this).apply { 
                isIndeterminate = true
                setPadding(48, 48, 48, 48)
            })
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    com.example.moniq.util.TrackInfoFetcher.fetchTrackInfo(trackId)
                }
                
                progressDialog.dismiss()
                
                if (info != null) {
                    showTrackInfoDialog(info)
                } else {
                    Toast.makeText(this@FullscreenPlayerActivity, "Failed to load track info", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@FullscreenPlayerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTrackInfoDialog(info: com.example.moniq.model.TrackInfo) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        
        fun addInfoRow(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            
            val row = LinearLayout(this@FullscreenPlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            
            row.addView(TextView(this@FullscreenPlayerActivity).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF999999.toInt())
            })
            
            row.addView(TextView(this@FullscreenPlayerActivity).apply {
                text = value
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 4, 0, 0)
            })
            
            container.addView(row)
        }
        
        addInfoRow("Title", info.title)
        addInfoRow("Artist", info.artists.joinToString(", ") { it.name })
        addInfoRow("Album", info.album?.title)
        
        val minutes = info.duration / 60
        val seconds = info.duration % 60
        addInfoRow("Duration", String.format("%d:%02d", minutes, seconds))
        
        val qualityText = buildString {
            when (info.audioQuality) {
                "HI_RES_LOSSLESS", "HI_RES" -> append("Hi-Res Lossless")
                "LOSSLESS" -> append("Lossless")
                "HIGH" -> append("High Quality")
                "LOW" -> append("Standard Quality")
                else -> append(info.audioQuality.replace("_", " "))
            }
            
            if (info.bitDepth != null || info.sampleRate != null) {
                append(" (")
                info.bitDepth?.let { append("${it}-bit") }
                if (info.bitDepth != null && info.sampleRate != null) append(", ")
                info.sampleRate?.let { append("${it/1000} kHz") }
                append(")")
            }
            
            if (info.audioModes.isNotEmpty()) {
                append(" • ${info.audioModes.joinToString(", ")}")
            }
        }
        addInfoRow("Audio Quality", qualityText)
        
        info.trackNumber?.let { 
            addInfoRow("Track Number", "$it${info.volumeNumber?.let { v -> " (Disc $v)" } ?: ""}")
        }
        
        if (info.bpm != null || info.key != null) {
            val musicalInfo = buildString {
                info.bpm?.let { append("$it BPM") }
                info.key?.let { 
                    if (isNotEmpty()) append(" • ")
                    append(it)
                    info.keyScale?.let { scale -> append(" ${scale.lowercase().replaceFirstChar { c -> c.uppercase() }}") }
                }
            }
            addInfoRow("Musical Info", musicalInfo)
        }
        
        info.popularity?.let { addInfoRow("Popularity", "$it%") }
        
        info.replayGain?.let { 
            addInfoRow("Replay Gain", String.format("%.2f dB", it))
        }
        
        addInfoRow("ISRC", info.isrc)
        addInfoRow("Copyright", info.copyright)
        
        if (info.explicit) {
            addInfoRow("Content", "Explicit")
        }
        
        val scrollView = androidx.core.widget.NestedScrollView(this).apply {
            addView(container)
        }
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Track Information")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }
}