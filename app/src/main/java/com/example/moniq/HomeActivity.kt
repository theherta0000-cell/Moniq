package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.widget.Button
import android.widget.ImageButton
import coil.load
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.appbar.MaterialToolbar
import android.view.View
import android.content.Intent
import android.widget.TextView
import com.example.moniq.player.AudioPlayer

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Optional navigation buttons (may not exist in redesigned layout)
        // Optional navigation buttons (may not exist in redesigned layout)
        val openAlbumId = resources.getIdentifier("openAlbumButton", "id", packageName)
        if (openAlbumId != 0) {
            findViewById<Button>(openAlbumId).setOnClickListener {
                val intent = Intent(this, AlbumActivity::class.java)
                intent.putExtra("albumTitle", "Sample Album")
                intent.putExtra("albumArtist", "Artist A")
                intent.putExtra("albumId", "1")
                startActivity(intent)
            }
        }

        val openArtistId = resources.getIdentifier("openArtistButton", "id", packageName)
        if (openArtistId != 0) {
            findViewById<Button>(openArtistId).setOnClickListener {
                val intent = Intent(this, ArtistActivity::class.java)
                intent.putExtra("artistName", "Artist A")
                intent.putExtra("artistId", "1")
                startActivity(intent)
            }
        }

        val openSearchId = resources.getIdentifier("openSearchButton", "id", packageName)
        if (openSearchId != 0) {
            findViewById<Button>(openSearchId).setOnClickListener {
                val intent = Intent(this, SearchActivity::class.java)
                startActivity(intent)
            }
        }

        // Miniplayer wiring
        AudioPlayer.initialize(this)
        // Ensure session restored if process restarted and HomeActivity is starting
        SessionManager.ensureLoaded(this.applicationContext)
        // Restore last played track into the player (paused) so the miniplayer shows previous song
        try { AudioPlayer.restoreLast(this.applicationContext) } catch (_: Exception) {}
        // Toolbar/menu wiring
        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        topAppBar?.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
        // Also make the navigation (menu/hamburger) icon open Search as a quick shortcut
        topAppBar?.setNavigationOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            startActivity(intent)
        }
        val miniTitle = findViewById<TextView?>(R.id.miniplayerTitle)
        val miniArtist = findViewById<TextView?>(R.id.miniplayerArtist)
        val miniPlay = findViewById<ImageButton?>(R.id.miniplayerPlayPause)
        val miniArt = findViewById<android.view.View?>(R.id.miniplayerArt) as? android.widget.ImageView
        val miniRoot = findViewById<android.view.View?>(R.id.miniplayerRoot)
        val miniSpeed = findViewById<TextView?>(R.id.miniplayerSpeed)
        val miniProgressFg = findViewById<View?>(R.id.miniplayer_progress_fg)

        // observe dominant color for miniplayer progress bar
        com.example.moniq.player.AudioPlayer.currentDominantColor.observe(this) { col ->
            try {
                if (col != null && miniProgressFg != null) miniProgressFg.setBackgroundColor(col)
            } catch (_: Exception) {}
        }

        fun updateMiniplayerVisibility() {
            val title = AudioPlayer.currentTitle.value
            val playing = AudioPlayer.isPlaying.value
            if ((title == null || title.isEmpty()) && (playing != true)) {
                com.example.moniq.util.ViewUtils.animateVisibility(miniRoot, false)
            } else {
                com.example.moniq.util.ViewUtils.animateVisibility(miniRoot, true)
            }
        }

        AudioPlayer.currentTitle.observe(this) { t -> miniTitle?.text = t ?: ""; updateMiniplayerVisibility() }
        AudioPlayer.currentArtist.observe(this) { a -> miniArtist?.text = a ?: ""; updateMiniplayerVisibility() }
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
            if (playing == true) {
                miniPlay?.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                miniPlay?.setImageResource(android.R.drawable.ic_media_play)
            }
            updateMiniplayerVisibility()
        }

        AudioPlayer.playbackSpeed.observe(this) { s ->
            miniSpeed?.text = String.format("%.2fx", s ?: 1.0f)
        }

        // Periodically update mini progress indicator
        lifecycleScope.launch {
            while (true) {
                try {
                    val dur = AudioPlayer.duration()
                    val pos = AudioPlayer.currentPosition()
                    if (dur > 0 && miniProgressFg != null && miniRoot != null) {
                        val pct = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                        val width = (miniRoot.width * pct).toInt()
                        val lp = miniProgressFg.layoutParams
                        lp.width = width
                        miniProgressFg.layoutParams = lp
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(500)
            }
        }

        miniPlay?.setOnClickListener { AudioPlayer.togglePlayPause() }

        // Open fullscreen player when miniplayer pressed
        miniRoot?.setOnClickListener {
            val intent = android.content.Intent(this, FullscreenPlayerActivity::class.java)
            startActivity(intent)
        }

        // Wire search bar in header
        val homeSearch = findViewById<com.google.android.material.textfield.TextInputEditText?>(R.id.home_search)
        homeSearch?.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            startActivity(intent)
        }

        // Load Recently Played (the RecyclerView is now swipe-refreshable)
        val recentSwipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout?>(R.id.recentSwipe)
        val recentRecycler = findViewById<androidx.recyclerview.widget.RecyclerView?>(R.id.recentRecycler)

        val recentAdapter = com.example.moniq.adapters.TrackAdapter(emptyList(), onPlay = { t, _ ->
    val coverId = t.coverArtId ?: t.albumId ?: t.id
    val albumArtUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(coverId)
    AudioPlayer.playTrack(this@HomeActivity, t.id, t.title, t.artist, albumArtUrl, t.albumId, t.albumName)
}, onDownload = { t ->
    lifecycleScope.launch {
        val coverId = t.coverArtId ?: t.albumId ?: t.id
        val albumArtUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(coverId)
        val ok = com.example.moniq.player.DownloadManager.downloadTrack(this@HomeActivity, t.id, t.title, t.artist, null, albumArtUrl)
        android.widget.Toast.makeText(this@HomeActivity, if (ok) "Downloaded" else "Download failed", android.widget.Toast.LENGTH_LONG).show()
    }
}, onAddToPlaylist = { t ->
            // show dialog to pick a playlist or create a new one
            val pm = com.example.moniq.player.PlaylistManager(this@HomeActivity.applicationContext)
            val playlists = pm.list()
            val names = playlists.map { it.name }.toMutableList()
            names.add("Create new playlist...")
            val items = names.map { it as CharSequence }.toTypedArray()
            android.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("Add to playlist")
                .setItems(items) { _, idx ->
                    if (idx == playlists.size) {
                        val edit = android.widget.EditText(this@HomeActivity)
                        android.app.AlertDialog.Builder(this@HomeActivity)
                            .setTitle("New playlist")
                            .setView(edit)
                            .setPositiveButton("Create") { _, _ ->
                                val name = edit.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    val p = pm.create(name)
                                    pm.addTrack(p.id, t)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        val p = playlists[idx]
                        pm.addTrack(p.id, t)
                    }
                }
                .show()
        })

        recentRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recentRecycler?.adapter = recentAdapter

        fun loadRecent() {
            lifecycleScope.launch {
                try {
                    val rpm = com.example.moniq.player.RecentlyPlayedManager(this@HomeActivity.applicationContext)
                    val latest = rpm.all(3)
                    recentAdapter.update(latest)
                } catch (_: Exception) {}
                recentSwipe?.isRefreshing = false
            }
        }

        recentSwipe?.setOnRefreshListener {
            loadRecent()
        }

        // Full-home refresh (pull-to-refresh on header area)
        val homeSwipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout?>(R.id.homeSwipe)
        fun refreshHome() {
            // refresh recent, album picks, and playlists
            try { loadRecent() } catch (_: Exception) {}
            try {
                val pmLocal = com.example.moniq.player.PlaylistManager(this.applicationContext)
                val playlists = pmLocal.list()
                val playlistsRecyclerLocal = findViewById<androidx.recyclerview.widget.RecyclerView?>(R.id.homePlaylistsRecycler)
                playlistsRecyclerLocal?.adapter?.let { (it as? com.example.moniq.adapters.PlaylistAdapter)?.update(playlists) }
            } catch (_: Exception) {}
            homeSwipe?.isRefreshing = false
        }
        homeSwipe?.setOnRefreshListener { refreshHome() }

        // initial load
        loadRecent()

        // Wire Playlists "See All" button on home
        val playlistsSeeAll = findViewById<com.google.android.material.button.MaterialButton?>(R.id.home_playlists_see_all)
        playlistsSeeAll?.setOnClickListener {
            val intent = Intent(this, PlaylistsActivity::class.java)
            startActivity(intent)
        }

        // Wire Recently Played "See All" button
        val recentSeeAll = findViewById<com.google.android.material.button.MaterialButton?>(R.id.recent_see_all)
        recentSeeAll?.setOnClickListener {
            try {
                val intent = Intent(this, com.example.moniq.RecentlyListActivity::class.java)
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // Playlists preview on home
        val playlistsRecycler = findViewById<androidx.recyclerview.widget.RecyclerView?>(R.id.homePlaylistsRecycler)
        val pm = com.example.moniq.player.PlaylistManager(this.applicationContext)
        val playlistAdapter = com.example.moniq.adapters.PlaylistAdapter(emptyList(), onOpen = { p ->
            val intent = android.content.Intent(this, PlaylistDetailActivity::class.java)
            intent.putExtra("playlistId", p.id)
            startActivity(intent)
        }, onDelete = { p ->
            pm.delete(p.id)
            playlistsRecycler?.adapter?.let { (it as? com.example.moniq.adapters.PlaylistAdapter)?.update(pm.list()) }
        })
        playlistsRecycler?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        playlistsRecycler?.adapter = playlistAdapter

        // initial load
        try { playlistAdapter.update(pm.list()) } catch (_: Exception) {}

        // refresh playlists when returning to Home
        lifecycleScope.launch {
            // simple observe-on-resume: refresh once after startup
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val playlistsRecycler = findViewById<androidx.recyclerview.widget.RecyclerView?>(R.id.homePlaylistsRecycler)
            val pm = com.example.moniq.player.PlaylistManager(this.applicationContext)
            playlistsRecycler?.adapter?.let { (it as? com.example.moniq.adapters.PlaylistAdapter)?.update(pm.list()) }
        } catch (_: Exception) {}
    }
}
