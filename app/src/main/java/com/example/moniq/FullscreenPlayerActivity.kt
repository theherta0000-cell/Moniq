package com.example.moniq

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ImageView
import coil.load
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.content.res.ColorStateList
import kotlinx.coroutines.Job
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.google.android.material.slider.Slider
import com.example.moniq.player.DownloadManager
import androidx.activity.ComponentActivity
import com.example.moniq.player.AudioPlayer
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.view.View

class FullscreenPlayerActivity : ComponentActivity() {
    companion object {
        // Cache to store fetched lyrics: "title|artist|album" -> List of lyrics lines
        private val lyricsCache = mutableMapOf<String, List<*>>()
    }
    
    private var lyricsLoadedFor: String? = null
    private var sliderJob: Job? = null
    private var lyricsJob: Job? = null
    private var timeJob: Job? = null
    private var lyricsFetchJob: Job? = null

   private fun loadLyricsIfNeeded(lyricsView: View?) {
    val title = AudioPlayer.currentTitle.value
    val artist = AudioPlayer.currentArtist.value
    val album = AudioPlayer.currentAlbumName.value
    val durSec = (AudioPlayer.duration() / 1000).toInt()
    if (title == null || artist == null) return
    
    val cacheKey = "$title|$artist|$album"
    
    // Check if already loaded in this activity instance
    if (lyricsLoadedFor == cacheKey) return
    
    // Check if we have cached lyrics
    val cachedLyrics = lyricsCache[cacheKey]
    if (cachedLyrics != null) {
        lyricsLoadedFor = cacheKey
        lyricsView?.visibility = View.VISIBLE
        lyricsSetLines(lyricsView, cachedLyrics)
        // Apply display settings when loading from cache
        val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this, true)
        val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this, true)
        lyricsSetShowTransliteration(lyricsView, showRomanization)
        lyricsSetShowTranslation(lyricsView, showTranslation)
        // Update position immediately so previous lyrics are colored
        lyricsUpdatePosition(lyricsView, AudioPlayer.currentPosition())
        lyricsView?.post { try { lyricsView.visibility = View.VISIBLE } catch (_: Exception) {} }
        Toast.makeText(this, "Loaded from cache", Toast.LENGTH_SHORT).show()
        return
    }
    
    // If not in cache, proceed to fetch
    lyricsLoadedFor = cacheKey
    lyricsClear(lyricsView)

    // show a cancellable progress dialog while fetching lyrics
    val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
    progressBar.isIndeterminate = true
    val container = android.widget.LinearLayout(this)
    container.orientation = android.widget.LinearLayout.VERTICAL
    container.setPadding(24,24,24,24)
    container.addView(progressBar)
    val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("Fetching lyrics...")
        .setView(container)
        .setNegativeButton("Cancel") { _, _ ->
            try { lyricsFetchJob?.cancel() } catch (_: Exception) {}
            lyricsLoadedFor = null
        }
        .setCancelable(false)
        .show()

    lyricsFetchJob = lifecycleScope.launch {
        try {
           val result = withContext(Dispatchers.IO) { com.example.moniq.network.LyricsFetcher.fetchLyrics(title, artist, album, durSec) }
if (result.lines.isNotEmpty()) {
    // Store in cache
    lyricsCache[cacheKey] = result.lines
    
    lyricsView?.visibility = View.VISIBLE
    lyricsSetLines(lyricsView, result.lines)
                // Apply display settings
                val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this@FullscreenPlayerActivity, true)
                val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this@FullscreenPlayerActivity, true)
                lyricsSetShowTransliteration(lyricsView, showRomanization)
                lyricsSetShowTranslation(lyricsView, showTranslation)
                // Update position immediately so previous lyrics are colored
                lyricsUpdatePosition(lyricsView, AudioPlayer.currentPosition())
                // ensure view refreshes so lines become visible in cases where the view was measured before content
                lyricsView?.post { try { lyricsView.visibility = View.VISIBLE } catch (_: Exception) {} }
                
                // Show success message with URL for a few seconds
                runOnUiThread {
    Toast.makeText(
        this@FullscreenPlayerActivity, 
        "Fetched successfully from: ${result.successfulUrl ?: "unknown"}", 
        Toast.LENGTH_LONG
    ).show()
}
            } else {
                lyricsView?.visibility = View.GONE
                runOnUiThread { android.widget.Toast.makeText(this@FullscreenPlayerActivity, "No lyrics found", android.widget.Toast.LENGTH_SHORT).show() }
                runOnUiThread {
                    try {
                        val info = android.widget.TextView(this@FullscreenPlayerActivity)
                        info.text = "Tried all backends"
                        info.setPadding(24,24,24,24)
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FullscreenPlayerActivity)
                            .setTitle("Lyrics not found")
                            .setView(info)
                            .setPositiveButton("Edit search") { _, _ ->
                                showEditSearchDialog(title, artist, album, lyricsView, durSec)
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
        tv.text = "Attempted all backends\n\nError: ${e.message}"
                        tv.setPadding(24,24,24,24)
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FullscreenPlayerActivity)
                            .setTitle("Lyrics fetch failed")
                            .setView(tv)
                            .setPositiveButton("Edit search") { _, _ ->
                                showEditSearchDialog(title, artist, album, lyricsView, durSec)
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    } catch (_: Exception) { android.util.Log.w("FullscreenPlayer", "failed to show lyrics error dialog", e) }
                }
                lyricsLoadedFor = null
            }
        } finally {
            try { dlg.dismiss() } catch (_: Exception) {}
            lyricsFetchJob = null
        }
    }
}

    private suspend fun loadLyricsRetry(nTitle: String?, nArtist: String?, nAlbum: String?, lyricsView: View?, durSec: Int) {
    try {
        val result = withContext(Dispatchers.IO) { com.example.moniq.network.LyricsFetcher.fetchLyrics(nTitle, nArtist, nAlbum, durSec) }
        if (result.lines.isNotEmpty()) {
            // Store in cache
            val cacheKey = "$nTitle|$nArtist|$nAlbum"
            lyricsCache[cacheKey] = result.lines
            
            runOnUiThread {
                lyricsView?.visibility = View.VISIBLE
                lyricsSetLines(lyricsView, result.lines)
                // Apply lyrics display settings
                val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this@FullscreenPlayerActivity, true)
                val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this@FullscreenPlayerActivity, true)
                lyricsSetShowTransliteration(lyricsView, showRomanization)
                lyricsSetShowTranslation(lyricsView, showTranslation)
                lyricsView?.post { try { lyricsView.visibility = View.VISIBLE } catch (_: Exception) {} }
                // Show success message
                Toast.makeText(this@FullscreenPlayerActivity, "Fetched from: ${result.successfulUrl ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        } else {
            // No lyrics found - show edit dialog again
            runOnUiThread {
                Toast.makeText(this@FullscreenPlayerActivity, "No lyrics found", Toast.LENGTH_SHORT).show()
                showEditSearchDialog(nTitle, nArtist, nAlbum, lyricsView, durSec)
            }
        }
    } catch (e: Exception) {
        // Error occurred - show edit dialog again
        runOnUiThread {
            Toast.makeText(this@FullscreenPlayerActivity, "Retry failed: ${e.message}", Toast.LENGTH_SHORT).show()
            showEditSearchDialog(nTitle, nArtist, nAlbum, lyricsView, durSec)
        }
    }
}

private fun showEditSearchDialog(title: String?, artist: String?, album: String?, lyricsView: View?, durSec: Int) {
    val container = android.widget.LinearLayout(this@FullscreenPlayerActivity).apply { orientation = android.widget.LinearLayout.VERTICAL; val pad = (12 * resources.displayMetrics.density).toInt(); setPadding(pad,pad,pad,pad) }
    val titleInput = android.widget.EditText(this@FullscreenPlayerActivity).apply { hint = "Title (optional)"; setSingleLine(true); setText(title ?: "") }
    val artistInput = android.widget.EditText(this@FullscreenPlayerActivity).apply { hint = "Artist (optional)"; setSingleLine(true); setText(artist ?: "") }
    val albumInput = android.widget.EditText(this@FullscreenPlayerActivity).apply { hint = "Album (optional)"; setSingleLine(true); setText(album ?: "") }
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
            // Keep original values if fields are left empty
            val nTitle = if (!rawTitle.isNullOrEmpty()) rawTitle else title
            val nArtist = if (!rawArtist.isNullOrEmpty()) rawArtist else artist
            val nAlbum = if (!rawAlbum.isNullOrEmpty()) rawAlbum else album
            lyricsLoadedFor = null
            lifecycleScope.launch { loadLyricsRetry(nTitle, nArtist, nAlbum, lyricsView, durSec) }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_player)

        val titleView = findViewById<TextView>(R.id.full_title)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setNavigationOnClickListener { finish() }
        // add artist and album quick actions to the toolbar
        try {
            val artistItem = toolbar.menu.add("Artist")
            artistItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            artistItem.setIcon(android.R.drawable.ic_menu_myplaces)
            artistItem.setOnMenuItemClickListener {
                val name = AudioPlayer.currentArtist.value ?: ""
                if (name.isBlank()) {
                    Toast.makeText(this, "No artist available", Toast.LENGTH_SHORT).show()
                } else {
                    // try to resolve artist id by searching
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
                                try { runOnUiThread { startActivity(intent) } } catch (_: Exception) { startActivity(intent) }
                            } else {
                                Toast.makeText(this@FullscreenPlayerActivity, "Artist not found", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) { Toast.makeText(this@FullscreenPlayerActivity, "Artist lookup failed", Toast.LENGTH_SHORT).show() }
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
                    // prefer direct album id if available
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
                        } catch (e: Exception) { Toast.makeText(this@FullscreenPlayerActivity, "Album lookup failed", Toast.LENGTH_SHORT).show() }
                    }
                }
                true
            }
        } catch (_: Exception) {}
        val artistView = findViewById<TextView>(R.id.full_artist)
        val artView = findViewById<ImageView>(R.id.full_art)
        val prevBtn = findViewById<ImageButton>(R.id.full_prev)
        val nextBtn = findViewById<ImageButton>(R.id.full_next)
        val playPause = findViewById<ImageButton>(R.id.full_play_pause)
        val progressSlider = findViewById<Slider>(R.id.progressSlider)
        val stopBtn = findViewById<MaterialButton>(R.id.full_stop)
        val downloadBtn = findViewById<MaterialButton>(R.id.full_download)
        val queueBtn = findViewById<MaterialButton>(R.id.full_queue)

        // Observe player metadata
        AudioPlayer.currentTitle.observe(this) { t ->
            titleView?.text = t ?: ""
        }

        AudioPlayer.currentArtist.observe(this) { a -> artistView?.text = a ?: "" }

        // Also observe currentAlbumArt and load it for fullscreen view with rounded corners and size limit
        AudioPlayer.currentAlbumArt.observe(this) { artUrl ->
            val loadUrl = when {
                !artUrl.isNullOrBlank() -> artUrl
                !AudioPlayer.currentTrackId.isNullOrBlank() && SessionManager.host != null ->
                    android.net.Uri.parse(SessionManager.host).buildUpon()
                        .appendPath("rest").appendPath("getCoverArt.view")
                        .appendQueryParameter("id", AudioPlayer.currentTrackId)
                        .appendQueryParameter("u", SessionManager.username ?: "")
                        .appendQueryParameter("p", SessionManager.password ?: "")
                        .build().toString()
                else -> null
            }
            if (loadUrl == null) {
                artView?.setImageResource(android.R.drawable.ic_menu_report_image)
            } else {
                artView?.load(loadUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_report_image)
                    error(android.R.drawable.ic_menu_report_image)
                    transformations(coil.transform.RoundedCornersTransformation(16f))
                    // cap decode/resize to avoid huge memory usage on tablets
                    size(1024)
                    scale(coil.size.Scale.FILL)
                    listener(onSuccess = { _, result ->
                        try {
                            val dr = result.drawable
                            val bmp = (dr as? BitmapDrawable)?.bitmap
                                    ?: (result.drawable?.let { (it as? BitmapDrawable)?.bitmap })
                            if (bmp != null) {
                                val color = extractDominantColor(bmp)
                                applyAccentColor(color, playPause, prevBtn, nextBtn, progressSlider, downloadBtn, queueBtn, stopBtn)
                            }
                        } catch (_: Exception) {}
                    })
                }
            }
        }

        AudioPlayer.isPlaying.observe(this) { playing -> 
            val icon = if (playing == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            playPause?.setImageResource(icon)
            // animate button for a small feedback
            playPause?.animate()?.scaleX(if (playing == true) 1.05f else 0.95f)?.scaleY(if (playing == true) 1.05f else 0.95f)?.setDuration(120)?.withEndAction {
                playPause?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            }?.start()
        }

        val fullCurrent = findViewById<TextView>(R.id.full_current_time)
        val fullTotal = findViewById<TextView>(R.id.full_total_time)

        playPause?.setOnClickListener { AudioPlayer.togglePlayPause() }
        prevBtn?.setOnClickListener { AudioPlayer.previous() }
        nextBtn?.setOnClickListener { AudioPlayer.next() }
        stopBtn?.setOnClickListener { AudioPlayer.pause() }

        downloadBtn?.setOnClickListener {
            val trackId = AudioPlayer.currentTrackId
            val title = AudioPlayer.currentTitle.value
            val artist = AudioPlayer.currentArtist.value
            if (trackId == null) {
                Toast.makeText(this, "No track selected to download", Toast.LENGTH_SHORT).show()
            } else {
                lifecycleScope.launch {
                    Toast.makeText(this@FullscreenPlayerActivity, "Downloading...", Toast.LENGTH_SHORT).show()
                    val ok = DownloadManager.downloadTrack(this@FullscreenPlayerActivity, trackId, title, artist, null, null)
                    Toast.makeText(this@FullscreenPlayerActivity, if (ok) "Download complete" else "Download failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        // queue button
        queueBtn?.setOnClickListener {
            val intent = android.content.Intent(this, QueueActivity::class.java)
            startActivity(intent)
        }

        val toggleLyrics = findViewById<MaterialButton>(R.id.full_toggle_lyrics)
        val lyricsOverlay = findViewById<View>(R.id.full_lyrics_overlay)
        val lyricsView = findViewById<View>(R.id.full_lyrics_view)

       // Set up seek listener for lyrics
try {
    val cls = Class.forName("com.example.moniq.views.LyricsView")
    if (lyricsView != null && cls.isInstance(lyricsView)) {
        val listener = object : kotlin.jvm.functions.Function1<Long, Unit> {
            override fun invoke(posMs: Long) {
                AudioPlayer.seekTo(posMs)
            }
        }
        val m = cls.getMethod("setOnSeekListener", kotlin.jvm.functions.Function1::class.java)
        m.invoke(lyricsView, listener)
    }
} catch (_: Exception) {}


        // Prepare views for fade animations
        val playControls = findViewById<LinearLayout>(R.id.playControls)
        val btmActions = findViewById<LinearLayout>(R.id.btmActions)

        toggleLyrics?.setOnClickListener {
            val checked = toggleLyrics.isChecked
            if (checked) {
                // Ensure lyrics are loaded when user opens the overlay
                try { loadLyricsIfNeeded(lyricsView) } catch (_: Exception) {}
                // Apply lyrics display settings
                val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this, true)
                val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this, true)
                lyricsSetShowTransliteration(lyricsView, showRomanization)
                lyricsSetShowTranslation(lyricsView, showTranslation)
                // show full-screen lyrics overlay and hide other UI elements
                try { titleView?.animate()?.alpha(0f)?.setDuration(250)?.start() } catch (_: Exception) {}
                try { artistView?.animate()?.alpha(0f)?.setDuration(250)?.start() } catch (_: Exception) {}
                try { playControls?.animate()?.alpha(0f)?.setDuration(250)?.start() } catch (_: Exception) {}
                try { btmActions?.animate()?.alpha(0f)?.setDuration(250)?.start() } catch (_: Exception) {}
                lyricsOverlay?.visibility = View.VISIBLE
                lyricsOverlay?.alpha = 0f
                lyricsOverlay?.animate()?.alpha(1f)?.setDuration(300)?.start()
                lyricsSetFocused(lyricsView, true)
            } else {
                // hide overlay and restore UI
                try { titleView?.animate()?.alpha(1f)?.setDuration(250)?.start() } catch (_: Exception) {}
                try { artistView?.animate()?.alpha(1f)?.setDuration(250)?.start() } catch (_: Exception) {}
                try { playControls?.animate()?.alpha(1f)?.setDuration(250)?.start() } catch (_: Exception) {}
                try { btmActions?.animate()?.alpha(1f)?.setDuration(250)?.start() } catch (_: Exception) {}
                lyricsOverlay?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction { lyricsOverlay.visibility = View.GONE }?.start()
                lyricsSetFocused(lyricsView, false)
            }
        }

        // Update slider periodically to reflect playback position
        sliderJob = lifecycleScope.launch(Dispatchers.Main) {
            var userSeeking = false
            progressSlider?.addOnChangeListener { _, value, fromUser ->
                if (fromUser) userSeeking = true
            }
            progressSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { userSeeking = true }
                override fun onStopTrackingTouch(slider: Slider) {
                    userSeeking = false
                    val dur = AudioPlayer.duration()
                    if (dur > 0) {
                        val targetMs = (slider.value / 100f * dur).toLong()
                        AudioPlayer.seekTo(targetMs)
                    }
                }
            })
            while (isActive) {
                try {
                    val dur = AudioPlayer.duration()
                    val pos = AudioPlayer.currentPosition()
                    if (!userSeeking && dur > 0) {
                        val percent = (pos.toFloat() / dur.toFloat()) * 100f
                        progressSlider?.value = percent
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }

        // Do not auto-fetch lyrics on metadata updates; user must toggle lyrics to fetch.

        lyricsJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                try {
                    val pos = AudioPlayer.currentPosition()
                    lyricsUpdatePosition(lyricsView, pos)
                } catch (_: Exception) {}
                delay(100)
            }
        }
        
        // Update time labels periodically
        timeJob = lifecycleScope.launch(Dispatchers.Main) {
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
        
        // Apply lyrics display preferences - force refresh
        val lyricsView = findViewById<View>(R.id.full_lyrics_view)
        val showRomanization = com.example.moniq.SessionStore.loadShowRomanization(this, true)
        val showTranslation = com.example.moniq.SessionStore.loadShowTranslation(this, true)
        
        // Apply settings and force view to refresh
        lyricsSetShowTransliteration(lyricsView, showRomanization)
        lyricsSetShowTranslation(lyricsView, showTranslation)
        
        // Force view to invalidate and redraw
        lyricsView?.post { 
            try {
                lyricsSetShowTransliteration(lyricsView, showRomanization)
                lyricsSetShowTranslation(lyricsView, showTranslation)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sliderJob?.cancel() } catch (_: Exception) {}
        try { lyricsJob?.cancel() } catch (_: Exception) {}
        try { timeJob?.cancel() } catch (_: Exception) {}
    }

    // Reflection helpers to interact with LyricsView without a compile-time dependency
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

    private fun lyricsSetFocused(view: View?, enabled: Boolean) {
        if (view == null) return
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("setFocusedMode", java.lang.Boolean::class.java)
                m.invoke(view, enabled)
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
        if (view == null) return
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("setShowTransliteration", java.lang.Boolean::class.java)
                m.invoke(view, show)
            }
        } catch (_: Exception) {}
    }

    private fun lyricsSetShowTranslation(view: View?, show: Boolean) {
        if (view == null) return
        try {
            val cls = Class.forName("com.example.moniq.views.LyricsView")
            if (cls.isInstance(view)) {
                val m = cls.getMethod("setShowTranslation", java.lang.Boolean::class.java)
                m.invoke(view, show)
            }
        } catch (_: Exception) {}
    }

    private fun extractDominantColor(bmp: Bitmap): Int {
        return try {
            val small = Bitmap.createScaledBitmap(bmp, 8, 8, true)
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
            for (x in 0 until small.width) for (y in 0 until small.height) {
                val p = small.getPixel(x, y)
                val alpha = (p shr 24) and 0xff
                if (alpha < 128) continue
                rSum += (p shr 16) and 0xff
                gSum += (p shr 8) and 0xff
                bSum += p and 0xff
                count++
            }
            if (count == 0) return 0xFF6200EE.toInt()
            val r = (rSum / count).toInt()
            val g = (gSum / count).toInt()
            val b = (bSum / count).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (_: Exception) { 0xFF6200EE.toInt() }
    }

    private fun applyAccentColor(color: Int, playPause: ImageButton?, prev: ImageButton?, next: ImageButton?, slider: Slider?, downloadBtn: MaterialButton?, queueBtn: MaterialButton?, stopBtn: MaterialButton?) {
        try {
            val csl = ColorStateList.valueOf(color)
            playPause?.backgroundTintList = csl
            prev?.backgroundTintList = ColorStateList.valueOf(adjustAlpha(color, 0.85f))
            next?.backgroundTintList = ColorStateList.valueOf(adjustAlpha(color, 0.85f))
            slider?.trackActiveTintList = ColorStateList.valueOf(color)
            slider?.trackInactiveTintList = ColorStateList.valueOf(adjustAlpha(color, 0.28f))
            slider?.thumbTintList = ColorStateList.valueOf(color)
            // tint material buttons text and icon to the dominant color
            downloadBtn?.setTextColor(color)
            queueBtn?.setTextColor(color)
            stopBtn?.setTextColor(color)
            try { downloadBtn?.iconTint = ColorStateList.valueOf(color) } catch (_: Exception) {}
            try { queueBtn?.iconTint = ColorStateList.valueOf(color) } catch (_: Exception) {}
            try { stopBtn?.iconTint = ColorStateList.valueOf(color) } catch (_: Exception) {}
            // also set toolbar menu icons and lyrics accent via reflection
            try {
                val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                toolbar?.overflowIcon?.setTint(color)
            } catch (_: Exception) {}
            try { // set lyrics accent color if LyricsView present
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
        val na = (a * factor).toInt().coerceIn(0,255)
        return (na shl 24) or (r shl 16) or (g shl 8) or b
    }
    
    private fun formatMs(ms: Long): String {
        val sec = (ms / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return String.format("%d:%02d", m, s)
    }
}