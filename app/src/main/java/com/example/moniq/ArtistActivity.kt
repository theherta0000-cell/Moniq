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
        albumsRecycler?.setHasFixedSize(true)

        // Popular songs (randomized list)
        val popularSongsRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.popularSongsRecycler)
        val trackAdapter = com.example.moniq.adapters.TrackAdapter(emptyList(), onPlay = { t, pos -> AudioPlayer.playTrack(this, t.id, t.title, t.artist, t.coverArtId) }, onDownload = { /* no-op */ })
        popularSongsRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        popularSongsRecycler?.adapter = trackAdapter

        // populate random sample tracks
        val sampleTitles = listOf("Thinkin Bout You","Provider","Pink + White","Nikes","Ivy","Seigfried")
        val rand = java.util.Random()
        val randomTracks = (0 until 6).map { i ->
            val title = sampleTitles[rand.nextInt(sampleTitles.size)]
            com.example.moniq.model.Track(id = "rand_$i", title = title, artist = artistName, durationSec = 210, albumId = null, albumName = null, coverArtId = null)
        }
        trackAdapter.update(randomTracks)
        val artistArtView = findViewById<ImageView>(R.id.artistHeader)

        // Determine artist cover id: prefer intent-provided cover id (from search), else fallback to artistId
        val intentCoverId = intent.getStringExtra("artistCoverId")
        val coverCandidate = if (!intentCoverId.isNullOrEmpty()) intentCoverId else artistId

        // Load artist image if available (use Uri builder to encode params)
        val host = SessionManager.host
        val user = SessionManager.username ?: ""
        val pass = SessionManager.password ?: ""
        if (host != null) {
            val artistCover = android.net.Uri.parse(host).buildUpon()
                .appendPath("rest")
                .appendPath("getCoverArt.view")
                .appendQueryParameter("id", coverCandidate)
                .appendQueryParameter("u", user)
                .appendQueryParameter("p", pass)
                .build()

            lifecycleScope.launch {
                val bytes = com.example.moniq.network.ImageFetcher.fetchUrlBytes(artistCover.toString())
                if (bytes != null) {
                    artistArtView.load(bytes) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_report_image)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                    try {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) applyArtistPalette(bmp)
                    } catch (_: Exception) {}
                } else {
                    artistArtView.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }
        }

        val fetchRow = findViewById<LinearLayout?>(R.id.artistFetchRow)
        val fetchLinkView = findViewById<TextView?>(R.id.artistFetchLink)
        val viewRespBtn = findViewById<Button?>(R.id.artistViewResponse)
        var latestResponse: String? = null

        lifecycleScope.launch {
            try {
                val repo = ArtistRepository()
                val info = repo.getArtistInfo(artistId)
                // info: Triple<name, biography, coverId>
                val repoName = info.first
                val repoBio = info.second
                val repoCoverId = info.third
                bioView.text = repoBio ?: "No biography available"
            // If no intent-provided coverId, prefer the one from repo
            if ((intentCoverId == null || intentCoverId.isEmpty()) && !repoCoverId.isNullOrEmpty()) {
                lifecycleScope.launch {
                    val host2 = SessionManager.host
                    val user2 = SessionManager.username ?: ""
                    val pass2 = SessionManager.password ?: ""
                    if (host2 != null) {
                        val artUri = android.net.Uri.parse(host2).buildUpon()
                            .appendPath("rest")
                            .appendPath("getCoverArt.view")
                            .appendQueryParameter("id", repoCoverId)
                            .appendQueryParameter("u", user2)
                            .appendQueryParameter("p", pass2)
                            .build()
                        val bytes = com.example.moniq.network.ImageFetcher.fetchUrlBytes(artUri.toString())
                        if (bytes != null) {
                            artistArtView.load(bytes) {
                                crossfade(true)
                                placeholder(android.R.drawable.ic_menu_report_image)
                                error(android.R.drawable.ic_menu_report_image)
                            }
                            try {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) applyArtistPalette(bmp)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

                // Also fetch raw response for artistInfo so we can inspect it in UI
                try {
                    val host = SessionManager.host
                    val base = run {
                        var b = host?.trim() ?: ""
                        if (b.isNotBlank() && !b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
                        if (b.isNotBlank() && !b.endsWith("/")) b += "/"
                        b
                    }
                    if (base.isNotBlank()) {
                        val retrofit = com.example.moniq.network.RetrofitClient.create(base)
                        val api = retrofit.create(com.example.moniq.network.OpenSubsonicApi::class.java)
                        val pwParam = if (SessionManager.legacy) SessionManager.password ?: "" else com.example.moniq.util.Crypto.md5(SessionManager.password ?: "")
                        val resp = api.getArtistInfo(SessionManager.username ?: "", pwParam, artistId)
                        latestResponse = resp.body() ?: ""
                        // build visible link used
                        val link = android.net.Uri.parse(base).buildUpon()
                            .appendPath("rest").appendPath("getArtistInfo.view")
                            .appendQueryParameter("u", SessionManager.username ?: "")
                            .appendQueryParameter("p", pwParam)
                            .appendQueryParameter("id", artistId)
                            .appendQueryParameter("v", "1.16.1")
                            .appendQueryParameter("c", "Moniq")
                            .build().toString()
                        fetchRow?.visibility = android.view.View.VISIBLE
                        fetchLinkView?.text = "fetched using: $link"
                        viewRespBtn?.visibility = android.view.View.VISIBLE
                    }
                } catch (_: Throwable) {}

                val albums: List<Album> = repo.getArtistAlbums(artistId)
                albumAdapter.update(albums)

                // Pick random albums and fetch tracks from them to show as "Popular songs"
                try {
                    val musicRepo = com.example.moniq.music.MusicRepository()
                    val candidates = albums.shuffled().take(6)
                    val allTracks = mutableListOf<com.example.moniq.model.Track>()
                    for (alb in candidates) {
                        try {
                            val t = musicRepo.getAlbumTracks(alb.id)
                            allTracks.addAll(t)
                        } catch (_: Throwable) {}
                    }
                    val unique = allTracks.distinctBy { it.id }
                    val picks = unique.shuffled().take(6)
                    trackAdapter.update(picks)
                } catch (_: Throwable) {
                    // network failures are non-fatal; leave the randomized list empty
                }
            } catch (t: Throwable) {
                android.util.Log.w("ArtistActivity", "Failed to load artist details", t)
                bioView.text = "Failed to load biography"
                albumAdapter.update(emptyList())
            }
            // wire response button after network attempt
            viewRespBtn?.setOnClickListener {
                val resp = latestResponse ?: "(no response captured)"
                val tv = TextView(this@ArtistActivity)
                tv.text = resp
                tv.setPadding(16)
                tv.isVerticalScrollBarEnabled = true
                val container = android.widget.ScrollView(this@ArtistActivity)
                container.addView(tv, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                val dlg = androidx.appcompat.app.AlertDialog.Builder(this@ArtistActivity)
                    .setTitle("Artist API Response")
                    .setView(container)
                    .setPositiveButton("Close", null)
                    .create()
                dlg.show()
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
                miniArt?.load(artUrl) { placeholder(R.drawable.ic_album); crossfade(true) }
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
            val defaultColor = resources.getColor(com.example.moniq.R.color.purple_500, theme)
            val dominant = Palette.from(bmp).generate().getDominantColor(defaultColor)
            val collapsing = findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.collapsingToolbar)
            collapsing?.setContentScrimColor(dominant)
            val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar?.setBackgroundColor(dominant)
        } catch (_: Exception) {}
    }
}