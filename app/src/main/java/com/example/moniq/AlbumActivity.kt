package com.example.moniq

import com.example.moniq.util.MetadataFetcher
import com.example.moniq.util.ImageUrlHelper
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.moniq.music.MusicRepository
import com.example.moniq.model.Track
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import android.graphics.BitmapFactory
import coil.load
import androidx.palette.graphics.Palette
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.example.moniq.player.AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import android.util.Log
import com.example.moniq.util.ServerManager


class AlbumActivity : ComponentActivity() {
    private var albumArtLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album)

        val albumTitle = intent.getStringExtra("albumTitle") ?: "Unknown Album"
        val albumArtist = intent.getStringExtra("albumArtist") ?: "Unknown Artist"
        val albumId = intent.getStringExtra("albumId") ?: "1"

        findViewById<TextView>(R.id.albumTitle).text = albumTitle
        findViewById<TextView>(R.id.albumArtist).text = albumArtist

        val albumArtView = findViewById<ImageView>(R.id.albumHeader)
        albumArtView.setImageResource(R.drawable.ic_album)

        val tracksRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tracksRecycler)
        var currentTracks: List<Track> = emptyList()
        
        val trackAdapter = com.example.moniq.adapters.TrackAdapter(
            emptyList(), 
            onPlay = { track, pos ->
                AudioPlayer.initialize(this@AlbumActivity)
                if (currentTracks.isNotEmpty()) {
                    AudioPlayer.setQueue(currentTracks, pos)
                } else {
                    lifecycleScope.launch {
                        val repo = MusicRepository()
                        val tracks: List<Track> = repo.getAlbumTracks(albumId)
                        currentTracks = tracks
                        val tracksWithAlbum = tracks.map { it.copy(albumId = albumId, albumName = albumTitle) }
                        AudioPlayer.setQueue(tracksWithAlbum, pos)
                    }
                }
            }, 
            onDownload = { track ->
                lifecycleScope.launch {
                    val ok = com.example.moniq.player.DownloadManager.downloadTrack(
                        this@AlbumActivity, track.id, track.title, track.artist, albumTitle, null
                    )
                    android.widget.Toast.makeText(
                        this@AlbumActivity,
                        if (ok) "Downloaded" else "Download failed",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
        
        tracksRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        tracksRecycler?.adapter = trackAdapter

      lifecycleScope.launch {
    val repo = MusicRepository()
    val tracks: List<Track> = repo.getAlbumTracks(albumId)

    // ✅ NO enrichment needed - data is already complete!
    val tracksWithAlbum = tracks.map { track ->
        track.copy(
            albumId = albumId,
            albumName = albumTitle,
            coverArtId = track.coverArtId ?: albumId  // Fallback to albumId
        )
    }

    currentTracks = tracksWithAlbum
    trackAdapter.update(tracksWithAlbum)

    // Display audio quality - check ALL tracks for highest quality
if (tracksWithAlbum.isNotEmpty()) {
    val qualityParts = mutableListOf<String>()

    // ✅ Find the highest quality across all tracks
    val hasHiRes = tracksWithAlbum.any { track ->
        track.audioQuality == "HI_RES_LOSSLESS" || 
        track.audioQuality == "HI_RES" ||
        track.mediaMetadataTags?.contains("HIRES_LOSSLESS") == true ||
        track.mediaMetadataTags?.contains("HI_RES_LOSSLESS") == true
    }
    
    val hasLossless = tracksWithAlbum.any { track ->
        track.audioQuality == "LOSSLESS" ||
        track.mediaMetadataTags?.contains("LOSSLESS") == true
    }

    // Display highest quality found
    when {
        hasHiRes -> qualityParts.add("Hi-Res Lossless")
        hasLossless -> qualityParts.add("Lossless")
        else -> qualityParts.add("High Quality")
    }

    // Add Dolby Atmos if ANY track has it
    if (tracksWithAlbum.any { it.audioModes?.contains("DOLBY_ATMOS") == true }) {
        qualityParts.add("Dolby Atmos")
    }

    val qualityText = qualityParts.joinToString(" • ")

    findViewById<TextView>(R.id.albumQuality)?.apply {
        text = qualityText
        visibility = View.VISIBLE
    }
}

    // ✅ Load album art (simpler - just use first valid ID)
    val coverArtId = tracksWithAlbum.firstOrNull()?.coverArtId ?: albumId
    loadAlbumArt(coverArtId)

    val albumArtUrl = ImageUrlHelper.getCoverArtUrl(coverArtId)

    // Setup download button
    findViewById<MaterialButton>(R.id.downloadAlbumButton).setOnClickListener {
        lifecycleScope.launch {
            // ✅ Use first working server for ALL tracks (faster)
            val workingServer = findFirstWorkingServer(tracksWithAlbum.first().id)
            
            if (workingServer == null) {
                android.widget.Toast.makeText(
                    this@AlbumActivity,
                    "No servers available",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val infos = tracksWithAlbum.map { tr ->
                com.example.moniq.player.DownloadManager.TrackInfo(
                    tr.id,
                    tr.title,
                    tr.artist,
                    albumTitle,
                    albumArtUrl,
                    "$workingServer/stream/?id=${tr.id}"  // Same server for all
                )
            }

            val results = com.example.moniq.player.DownloadManager.downloadAlbum(
                this@AlbumActivity,
                infos
            )

            val successCount = results.count { it.second }
            android.widget.Toast.makeText(
                this@AlbumActivity,
                "Downloaded $successCount of ${results.size} tracks",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    findViewById<MaterialButton>(R.id.playAllButton).setOnClickListener {
        if (tracksWithAlbum.isNotEmpty()) {
            AudioPlayer.setQueue(tracksWithAlbum, 0)
        }
    }
}

        // Miniplayer wiring
        AudioPlayer.initialize(this)
        val miniTitle = findViewById<TextView?>(R.id.miniplayerTitle)
        val miniArtist = findViewById<TextView?>(R.id.miniplayerArtist)
        val miniPlay = findViewById<ImageButton?>(R.id.miniplayerPlayPause)
        val miniArt = findViewById<android.view.View?>(R.id.miniplayerArt) as? android.widget.ImageView

        AudioPlayer.currentTitle.observe(this) { t -> miniTitle?.text = t ?: "" }
        AudioPlayer.currentArtist.observe(this) { a -> miniArtist?.text = a ?: "" }
        AudioPlayer.currentAlbumArt.observe(this) { artUrl ->
            val loadUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(artUrl)
            if (loadUrl.isNullOrBlank()) {
                miniArt?.setImageResource(R.drawable.ic_album)
            } else {
                miniArt?.load(loadUrl) { 
                    placeholder(R.drawable.ic_album)
                    error(R.drawable.ic_album)
                    crossfade(true) 
                }
            }
        }
        
        AudioPlayer.isPlaying.observe(this) { playing ->
            val res = if (playing == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            miniPlay?.setImageResource(res)
        }

        miniPlay?.setOnClickListener {
            AudioPlayer.togglePlayPause()
        }

        val miniRoot = findViewById<android.view.View?>(R.id.miniplayerRoot)
        miniRoot?.setOnClickListener {
            val intent = android.content.Intent(this, FullscreenPlayerActivity::class.java)
            startActivity(intent)
        }
    }

   private suspend fun loadAlbumArt(coverArtId: String) = withContext(Dispatchers.IO) {
    if (albumArtLoaded) return@withContext
    
    val albumHeader = findViewById<ImageView>(R.id.albumHeader)
    val coverUrl = ImageUrlHelper.getCoverArtUrl(coverArtId)
    
    if (coverUrl == null) {
        withContext(Dispatchers.Main) { setDefaultAlbumArt() }
        return@withContext
    }
    
    withContext(Dispatchers.Main) {
        albumHeader.load(coverUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_album)
            error(R.drawable.ic_album)
            listener(
                onSuccess = { _, result ->
                    albumArtLoaded = true
                    
                    // Extract colors in background
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val bytes = com.example.moniq.network.ImageFetcher.fetchUrlBytes(coverUrl)
                            if (bytes != null) {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    withContext(Dispatchers.Main) {
                                        applyPaletteColors(bmp)
                                        applyButtonTint(paletteColorOrDefault(bmp))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                onError = { _, _ ->
                    setDefaultAlbumArt()
                }
            )
        }
    }
}

    private fun setDefaultAlbumArt() {
        val albumHeader = findViewById<ImageView>(R.id.albumHeader)
        albumHeader.setImageResource(R.drawable.ic_album)
        albumArtLoaded = true
        
        val defaultColor = resources.getColor(com.example.moniq.R.color.purple_500, theme)
        val collapsing = findViewById<CollapsingToolbarLayout>(R.id.collapsingToolbar)
        collapsing?.setContentScrimColor(defaultColor)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setBackgroundColor(defaultColor)
    }

    private fun applyPaletteColors(bmp: android.graphics.Bitmap) {
        Palette.from(bmp).generate { palette: Palette? ->
            val defaultColor = resources.getColor(com.example.moniq.R.color.purple_500, theme)
            val dominant = palette?.getDominantColor(defaultColor) ?: defaultColor
            val collapsing = findViewById<CollapsingToolbarLayout>(R.id.collapsingToolbar)
            collapsing?.setContentScrimColor(dominant)
            val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar?.setBackgroundColor(dominant)
        }
    }

    private fun paletteColorOrDefault(bmp: android.graphics.Bitmap): Int {
        return try {
            val palette = Palette.from(bmp).generate()
            val defaultColor = resources.getColor(com.example.moniq.R.color.purple_500, theme)
            palette.getDominantColor(defaultColor)
        } catch (_: Exception) {
            resources.getColor(com.example.moniq.R.color.purple_500, theme)
        }
    }

    private fun applyButtonTint(dominant: Int) {
        try {
            val downloadBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.downloadAlbumButton)
            val playAllBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.playAllButton)
            downloadBtn?.backgroundTintList = android.content.res.ColorStateList.valueOf(dominant)
            playAllBtn?.backgroundTintList = android.content.res.ColorStateList.valueOf(dominant)
            downloadBtn?.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        } catch (_: Exception) {}
    }

    private fun formatDuration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%d:%02d", m, s)
    }

    private suspend fun findFirstWorkingServer(testTrackId: String): String? = withContext(Dispatchers.IO) {
    val servers = ServerManager.getOrderedServers()
    
    for (server in servers) {
        try {
            val testUrl = "$server/track/?id=$testTrackId"
            val conn = java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
conn.connectTimeout = 3000
conn.readTimeout = 3000
conn.requestMethod = "GET"
conn.setRequestProperty("Accept", "*/*")
conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br, zstd")
conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
conn.setRequestProperty("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
            
            if (conn.responseCode == 200) {
                conn.disconnect()
                ServerManager.recordSuccess(server)
                return@withContext server
            }
            conn.disconnect()
        } catch (e: Exception) {
            continue
        }
    }
    return@withContext null

}
}