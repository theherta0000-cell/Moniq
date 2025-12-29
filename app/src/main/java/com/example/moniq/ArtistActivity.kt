package com.example.moniq

import android.widget.Button
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.example.moniq.artist.ArtistRepository
import com.example.moniq.model.Album
import kotlinx.coroutines.launch
import com.example.moniq.player.AudioPlayer
import com.google.android.material.card.MaterialCardView
import coil.load
import coil.transform.RoundedCornersTransformation
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette

class ArtistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_artist)

        val artistName = intent.getStringExtra("artistName") ?: "Unknown Artist"
        val artistId = intent.getStringExtra("artistId") ?: ""

        // Ensure session is loaded (when launched from background/global context)
        SessionManager.ensureLoaded(this.applicationContext)

        // Setup toolbar
        // back navigation handled by toolbar in layout (no explicit wiring here)

        findViewById<TextView>(R.id.artistName).text = artistName

        val bioView = findViewById<TextView>(R.id.artistBio)
        val albumsRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.albumsRecycler)
        val albumAdapter = com.example.moniq.adapters.AlbumAdapter(emptyList(), { album ->
            val intent = Intent(this@ArtistActivity, AlbumActivity::class.java)
            intent.putExtra("albumId", album.id)
            intent.putExtra("albumTitle", album.name)
            intent.putExtra("albumArtist", album.artist)
            startActivity(intent)
        }, compact = true)
        albumsRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        albumsRecycler?.adapter = albumAdapter

        // Store all tracks for filtering (DECLARE FIRST!)
var allTracks = listOf<com.example.moniq.model.Track>()
var isSearchLoading = false

// Popular songs (randomized list)
val popularSongsRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.popularSongsRecycler)
val trackAdapter = com.example.moniq.adapters.TrackAdapter(emptyList(), onPlay = { t, pos -> 
    AudioPlayer.initialize(this)
    // Find the position in allTracks instead of using the filtered position
    val actualPos = allTracks.indexOfFirst { it.id == t.id }
    if (actualPos != -1 && allTracks.isNotEmpty()) {
        AudioPlayer.setQueue(allTracks, actualPos)  // ✅ Use the track's actual position in allTracks
    } else {
        AudioPlayer.playTrack(this, t.id, t.title, t.artist, t.coverArtId)
    }
}, onDownload = { /* no-op */ })
popularSongsRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
popularSongsRecycler?.adapter = trackAdapter

// Wire up song search
val songSearchInput = findViewById<com.google.android.material.textfield.TextInputEditText?>(R.id.artistSongSearch)
val searchLoadingIndicator = findViewById<android.widget.ProgressBar?>(R.id.searchLoadingIndicator)

songSearchInput?.addTextChangedListener(object : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        val query = s?.toString()?.lowercase()?.trim() ?: ""
        
        if (query.isEmpty()) {
            // Show the initial loaded tracks
            trackAdapter.update(allTracks.take(20))
        } else {
            // Search through loaded tracks first (instant feedback)
            val filtered = allTracks.filter { track ->
                track.title.lowercase().contains(query) ||
                track.artist?.lowercase()?.contains(query) == true ||
                track.albumName?.lowercase()?.contains(query) == true
            }
            trackAdapter.update(filtered)
            
            // If we haven't loaded all tracks yet and search is active, load them
            if (allTracks.size < 100 && !isSearchLoading) {
                isSearchLoading = true
                searchLoadingIndicator?.visibility = android.view.View.VISIBLE
                
                lifecycleScope.launch {
                    try {
                        val repo = ArtistRepository()
                        val albums = repo.getArtistAlbums(artistId)
                        val musicRepo = com.example.moniq.music.MusicRepository()
                        
                        val tempTracks = mutableListOf<com.example.moniq.model.Track>()
                        for (alb in albums) {
                            try {
                                val t = musicRepo.getAlbumTracks(alb.id)
                                val byArtist = t.filter { tr ->
                                    try {
                                        val an = artistName.lowercase().trim()
                                        val ta = (tr.artist ?: "").lowercase().trim()
                                        ta.contains(an) || an.contains(ta)
                                    } catch (_: Exception) { false }
                                }
                                if (byArtist.isNotEmpty()) tempTracks.addAll(byArtist) else tempTracks.addAll(t)
                            } catch (_: Throwable) {}
                        }
                        
                        allTracks = tempTracks.distinctBy { it.id }
                        
                        // Re-apply the current search query
                        val currentQuery = songSearchInput?.text?.toString()?.lowercase()?.trim() ?: ""
                        if (currentQuery.isNotEmpty()) {
                            val newFiltered = allTracks.filter { track ->
                                track.title.lowercase().contains(currentQuery) ||
                                track.artist?.lowercase()?.contains(currentQuery) == true ||
                                track.albumName?.lowercase()?.contains(currentQuery) == true
                            }
                            trackAdapter.update(newFiltered)
                        }
                        
                        searchLoadingIndicator?.visibility = android.view.View.GONE
                        isSearchLoading = false
                    } catch (t: Throwable) {
                        android.util.Log.w("ArtistActivity", "Failed to load all tracks for search", t)
                        searchLoadingIndicator?.visibility = android.view.View.GONE
                        isSearchLoading = false
                    }
                }
            }
        }
    }
})

        // populate random sample tracks
        val sampleTitles = listOf("Song 1","Song 2","Song 3","Song 4","Song 5","Song 6")
        val rand = java.util.Random()
        val randomTracks = (0 until 6).map { i ->
            val title = sampleTitles[rand.nextInt(sampleTitles.size)]
            com.example.moniq.model.Track(id = "rand_$i", title = title, artist = artistName, duration = 210, albumId = null, albumName = null, coverArtId = null)
        }
        trackAdapter.update(randomTracks)
        val artistArtView = findViewById<ImageView>(R.id.artistHeader)

// Prefer coverId from repository, fallback to intent (search results)
val intentCoverId = intent.getStringExtra("artistCoverId")

lifecycleScope.launch {
    try {
        val repo = ArtistRepository()
        val info = repo.getArtistInfo(artistId)
        // info: Triple<name, biography, coverId>
        val repoName = info.first
        val repoBio = info.second
        val repoCoverId = info.third

        // Biography
        bioView.text = repoBio ?: "No biography available"

        // Decide final cover id
        val finalCoverId = repoCoverId ?: intentCoverId

        // Load artist image using ImageUrlHelper (same as adapters)
if (!finalCoverId.isNullOrEmpty()) {
    val imageUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(finalCoverId)
    if (!imageUrl.isNullOrEmpty()) {
        artistArtView.load(imageUrl) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_report_image)
            error(android.R.drawable.ic_menu_report_image)
            listener(
                onSuccess = { _, result ->
                    try {
                        val drawable = result.drawable
                        if (drawable is android.graphics.drawable.BitmapDrawable) {
                            val bmp = drawable.bitmap
                            if (bmp != null) applyArtistPalette(bmp)
                        }
                    } catch (_: Exception) {}
                }
            )
        }
    } else {
        artistArtView.setImageResource(android.R.drawable.ic_menu_report_image)
    }
} else {
    artistArtView.setImageResource(android.R.drawable.ic_menu_report_image)
}

        // Albums
        val albums: List<Album> = repo.getArtistAlbums(artistId)
        albumAdapter.update(albums)

        // Popular songs (randomized)
        try {
            val musicRepo = com.example.moniq.music.MusicRepository()
            val candidates = albums.shuffled().take(8)
            val tempTracks = mutableListOf<com.example.moniq.model.Track>()

            for (alb in candidates) {
                try {
                    val tracks = musicRepo.getAlbumTracks(alb.id)
                    val byArtist = tracks.filter { tr ->
                        try {
                            val an = artistName.lowercase().trim()
                            val ta = (tr.artist ?: "").lowercase().trim()
                            ta.contains(an) || an.contains(ta)
                        } catch (_: Exception) { false }
                    }
                    if (byArtist.isNotEmpty()) tempTracks.addAll(byArtist)
                    else tempTracks.addAll(tracks)
                } catch (_: Throwable) {}
            }

            allTracks = tempTracks.distinctBy { it.id }.shuffled()
            trackAdapter.update(allTracks.take(20))
        } catch (_: Throwable) {
            // non-fatal
        }

    } catch (t: Throwable) {
        android.util.Log.w("ArtistActivity", "Failed to load artist details", t)
        bioView.text = "Failed to load biography"
        albumAdapter.update(emptyList())
        artistArtView.setImageResource(android.R.drawable.ic_menu_report_image)
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

    private fun applyArtistPalette(bmp: android.graphics.Bitmap) {
    try {
        val palette = Palette.from(bmp).generate()
        
        // Try to get the best color in order of preference
        val dominantColor = palette.vibrantSwatch?.rgb 
            ?: palette.darkVibrantSwatch?.rgb 
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.darkMutedSwatch?.rgb
            ?: palette.getDominantColor(resources.getColor(com.example.moniq.R.color.purple_500, theme))
        
        // Apply to collapsing toolbar
        val collapsing = findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.collapsingToolbar)
        collapsing?.setContentScrimColor(dominantColor)
        collapsing?.setStatusBarScrimColor(dominantColor)
        
        // Apply to toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.setBackgroundColor(dominantColor)
        
        // Apply to AppBarLayout
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        appBar?.setBackgroundColor(dominantColor)
        
        // Apply to search bar with better contrast
        val searchLayout = findViewById<com.google.android.material.textfield.TextInputLayout?>(R.id.artistSongSearchLayout)
        searchLayout?.boxStrokeColor = dominantColor
        searchLayout?.hintTextColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        searchLayout?.setBoxBackgroundColorStateList(
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.argb(30, 
                    android.graphics.Color.red(dominantColor),
                    android.graphics.Color.green(dominantColor),
                    android.graphics.Color.blue(dominantColor)
                )
            )
        )
        
        val searchInput = findViewById<com.google.android.material.textfield.TextInputEditText?>(R.id.artistSongSearch)
        searchInput?.setTextColor(android.graphics.Color.WHITE)
        
        // Update status bar color to match
        window.statusBarColor = darkenColor(dominantColor, 0.7f)
        
    } catch (e: Exception) {
        android.util.Log.e("ArtistActivity", "Failed to apply palette", e)
    }
}

// Helper function to darken colors
private fun darkenColor(color: Int, factor: Float): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    hsv[2] *= factor // Darken the value
    return android.graphics.Color.HSVToColor(hsv)
}
}