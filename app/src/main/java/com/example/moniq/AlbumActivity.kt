package com.example.moniq

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.example.moniq.music.MusicRepository
import com.example.moniq.model.Track
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.graphics.BitmapFactory
import coil.load
import coil.transform.RoundedCornersTransformation
import androidx.palette.graphics.Palette
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.example.moniq.player.AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale

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

        // Show placeholder immediately
        val albumArtView = findViewById<ImageView>(R.id.albumHeader)
        albumArtView.setImageResource(R.drawable.ic_album)

        val tracksRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tracksRecycler)
        var currentTracks: List<Track> = emptyList()
        val trackAdapter = com.example.moniq.adapters.TrackAdapter(emptyList(), onPlay = { track, pos ->
            AudioPlayer.initialize(this@AlbumActivity)
            if (currentTracks.isNotEmpty()) {
                AudioPlayer.setQueue(currentTracks, pos)
            } else {
                lifecycleScope.launch {
                    val repo = MusicRepository()
                    val tracks: List<Track> = repo.getAlbumTracks(albumId)
                    currentTracks = tracks
                    AudioPlayer.setQueue(tracks, pos)
                }
            }
        }, onDownload = { track ->
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
        })
        tracksRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        tracksRecycler?.adapter = trackAdapter

        // Load tracks and THEN load album art (avoid race condition)
        lifecycleScope.launch {
            val repo = MusicRepository()
            val tracks: List<Track> = repo.getAlbumTracks(albumId)
            currentTracks = tracks
            trackAdapter.update(tracks)

            // NOW load album art with proper cover ID from tracks
            val coverIds = listOfNotNull(
                tracks.firstOrNull()?.coverArtId,  // Try track's cover first
                albumId,                             // Then album ID
                tracks.firstOrNull()?.id            // Finally try first track's ID
            ).distinct()

            loadAlbumArtRobust(coverIds)

            // Get the best album art URL for downloads
            val albumArtUrl = buildCoverUrl(tracks.firstOrNull()?.coverArtId ?: albumId)

            // Wire album download button
            val downloadAlbumBtn = findViewById<MaterialButton>(R.id.downloadAlbumButton)
            downloadAlbumBtn.setOnClickListener {
                lifecycleScope.launch {
                    val infos = tracks.mapIndexed { idx, tr ->
                        com.example.moniq.player.DownloadManager.TrackInfo(
                            tr.id, tr.title, tr.artist, albumTitle, albumArtUrl
                        )
                    }
                    val results = com.example.moniq.player.DownloadManager.downloadAlbum(
                        this@AlbumActivity, infos
                    )
                    val successCount = results.count { it.second }
                    android.widget.Toast.makeText(
                        this@AlbumActivity,
                        "Downloaded $successCount of ${results.size} tracks",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            val startDownload = intent.getBooleanExtra("startDownload", false)
            if (startDownload) {
                downloadAlbumBtn.performClick()
            }

            val addAll = intent.getBooleanExtra("addAllToPlaylist", false)
            if (addAll) {
                val pm = com.example.moniq.player.PlaylistManager(this@AlbumActivity.applicationContext)
                val playlists = pm.list()
                val names = playlists.map { it.name }.toMutableList()
                names.add("Create new playlist...")
                val items = names.map { it as CharSequence }.toTypedArray()
                android.app.AlertDialog.Builder(this@AlbumActivity)
                    .setTitle("Add album to playlist")
                    .setItems(items) { _, idx ->
                        if (idx == playlists.size) {
                            val edit = android.widget.EditText(this@AlbumActivity)
                            android.app.AlertDialog.Builder(this@AlbumActivity)
                                .setTitle("New playlist")
                                .setView(edit)
                                .setPositiveButton("Create") { _, _ ->
                                    val name = edit.text.toString().trim()
                                    if (name.isNotEmpty()) {
                                        val p = pm.create(name)
                                        for (tr in tracks) pm.addTrack(p.id, tr)
                                        android.widget.Toast.makeText(this@AlbumActivity, "Added ${tracks.size} tracks to $name", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            val p = playlists[idx]
                            for (tr in tracks) pm.addTrack(p.id, tr)
                            android.widget.Toast.makeText(this@AlbumActivity, "Added ${tracks.size} tracks to ${p.name}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }

            val playAllBtn = findViewById<MaterialButton>(R.id.playAllButton)
            playAllBtn.setOnClickListener {
                if (tracks.isNotEmpty()) {
                    AudioPlayer.setQueue(tracks, 0)
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
            if (artUrl.isNullOrBlank()) {
                miniArt?.setImageResource(R.drawable.ic_album)
            } else {
                miniArt?.load(artUrl) { 
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

    private fun buildCoverUrl(coverId: String): String? {
        return if (SessionManager.host != null) {
            android.net.Uri.parse(SessionManager.host).buildUpon()
                .appendPath("rest")
                .appendPath("getCoverArt.view")
                .appendQueryParameter("id", coverId)
                .appendQueryParameter("u", SessionManager.username ?: "")
                .appendQueryParameter("p", SessionManager.password ?: "")
                .appendQueryParameter("size", "500")  // Request reasonable size
                .build().toString()
        } else null
    }

    private suspend fun loadAlbumArtRobust(coverIds: List<String>) = withContext(Dispatchers.IO) {
        if (albumArtLoaded) return@withContext  // Prevent duplicate loads
        
        val host = SessionManager.host
        if (host == null) {
            withContext(Dispatchers.Main) { setDefaultAlbumArt() }
            return@withContext
        }

        // Try each cover ID until one succeeds. Execute Coil requests synchronously
        // so we only try the next ID after the previous one completes.
        val albumHeader = findViewById<ImageView>(R.id.albumHeader)
        val imageLoader = coil.ImageLoader(this@AlbumActivity)
        for (coverId in coverIds) {
            val coverUrl = buildCoverUrl(coverId) ?: continue
            try {
                val request = ImageRequest.Builder(this@AlbumActivity)
                    .data(coverUrl)
                    .allowHardware(false)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .scale(Scale.FIT)
                    .build()

                val result = withContext(Dispatchers.IO) {
                    try {
                        imageLoader.execute(request)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (result is coil.request.SuccessResult) {
                    withContext(Dispatchers.Main) {
                        albumHeader.setImageDrawable(result.drawable)
                        albumArtLoaded = true
                    }

                    // Extract palette colors using fetched bytes (best accuracy)
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

                    break
                }
                // else try next coverId
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // If nothing worked, set default
        if (!albumArtLoaded) {
            withContext(Dispatchers.Main) {
                setDefaultAlbumArt()
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

    // Helper to apply dominant color to buttons in the album view
    private fun applyButtonTint(dominant: Int) {
        try {
            val downloadBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.downloadAlbumButton)
            val playAllBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.playAllButton)
            downloadBtn?.backgroundTintList = android.content.res.ColorStateList.valueOf(dominant)
            playAllBtn?.backgroundTintList = android.content.res.ColorStateList.valueOf(dominant)
            // also tint icon if present
            downloadBtn?.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        } catch (_: Exception) {}
    }

    private fun formatDuration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%d:%02d", m, s)
    }
}