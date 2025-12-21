package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.moniq.player.RecentlyPlayedManager
import com.example.moniq.player.AudioPlayer
import com.example.moniq.SessionManager

class RecentlyListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recently_list)

        val tracksRecycler = findViewById<RecyclerView?>(R.id.recent_list_tracks)
        val artistsRecycler = findViewById<RecyclerView?>(R.id.recent_list_artists)
        val albumsRecycler = findViewById<RecyclerView?>(R.id.recent_list_albums)

        // Simple adapters
        val trackAdapter = com.example.moniq.adapters.TrackAdapter(emptyList(), { t, pos ->
    val coverId = t.coverArtId ?: t.albumId ?: t.id
    val albumArtUrl = if (SessionManager.host != null) {
        if (coverId.startsWith("http")) coverId
        else android.net.Uri.parse(SessionManager.host).buildUpon()
            .appendPath("rest").appendPath("getCoverArt.view")
            .appendQueryParameter("id", coverId)
            .appendQueryParameter("u", SessionManager.username ?: "")
            .appendQueryParameter("p", SessionManager.password ?: "")
            .build().toString()
    } else null
    AudioPlayer.playTrack(this, t.id, t.title, t.artist, albumArtUrl, t.albumId, t.albumName)
}, onDownload = { t ->
            // try to download track (best-effort)
            lifecycleScope.launch {
                val coverId = t.coverArtId ?: t.albumId ?: t.id
                val albumArtUrl = if (SessionManager.host != null) {
                    if (coverId.startsWith("http")) coverId
                    else android.net.Uri.parse(SessionManager.host).buildUpon()
                        .appendPath("rest").appendPath("getCoverArt.view")
                        .appendQueryParameter("id", coverId)
                        .appendQueryParameter("u", SessionManager.username ?: "")
                        .appendQueryParameter("p", SessionManager.password ?: "")
                        .build().toString()
                } else null
                val ok = com.example.moniq.player.DownloadManager.downloadTrack(this@RecentlyListActivity, t.id, t.title, t.artist, null, albumArtUrl)
                android.widget.Toast.makeText(this@RecentlyListActivity, if (ok) "Downloaded" else "Download failed", android.widget.Toast.LENGTH_LONG).show()
            }
        })
        tracksRecycler?.layoutManager = LinearLayoutManager(this)
        tracksRecycler?.adapter = trackAdapter

        // Artist and Album simple list adapters
        val artistAdapter = SimpleTextCountAdapter { name ->
            // Try to resolve artist name -> id via SearchRepository and open ArtistActivity
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val repo = com.example.moniq.search.SearchRepository()
                        val res = repo.search(name)
                        val match = res.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        if (match != null && match.id.isNotBlank()) {
                            val i = Intent(this@RecentlyListActivity, com.example.moniq.ArtistActivity::class.java)
                            i.putExtra("artistId", match.id)
                            i.putExtra("artistName", match.name)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        } else {
                            val i = Intent(this@RecentlyListActivity, com.example.moniq.SearchActivity::class.java)
                            i.putExtra("query", name)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        }
                    } catch (_: Exception) {
                        val i = Intent(this@RecentlyListActivity, com.example.moniq.SearchActivity::class.java)
                        i.putExtra("query", name)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    }
                }
            } catch (_: Exception) {
                val i = Intent(this, SearchActivity::class.java)
                i.putExtra("query", name)
                startActivity(i)
            }
        }
        artistsRecycler?.layoutManager = LinearLayoutManager(this)
        artistsRecycler?.adapter = artistAdapter

       // Store mapping of albumId -> albumName for display
        val albumIdToName = mutableMapOf<String, String>()
        
        val albumAdapter = AlbumIdAdapter { albumId, albumName ->
            val i = Intent(this, AlbumActivity::class.java)
            i.putExtra("albumId", albumId)
            i.putExtra("albumTitle", albumName)
            startActivity(i)
        }
        albumsRecycler?.layoutManager = LinearLayoutManager(this)
        albumsRecycler?.adapter = albumAdapter

        // Load data
        lifecycleScope.launch {
            try {
                val rpm = RecentlyPlayedManager(this@RecentlyListActivity.applicationContext)
                val recent = rpm.all(100)
                trackAdapter.update(recent)

                val artistCounts = rpm.getArtistPlayCounts().toList().sortedByDescending { it.second }
                artistAdapter.update(artistCounts.map { Pair(it.first, it.second) })

                // Build mapping of albumId -> albumName from recent tracks
                recent.forEach { track ->
                    if (!track.albumId.isNullOrBlank() && !track.albumName.isNullOrBlank()) {
                        albumIdToName[track.albumId] = track.albumName
                    }
                }

                val albumCounts = rpm.getAlbumPlayCounts().toList().sortedByDescending { it.second }
                // Create triples with albumId, albumName, and count
                albumAdapter.update(albumCounts.map { (albumId, count) -> 
                    Triple(albumId, albumIdToName[albumId] ?: albumId, count) 
                })
            } catch (_: Exception) {}
        }
    }

    // Simple adapter to show text + count rows
    class SimpleTextCountAdapter(val onClick: (String) -> Unit) : RecyclerView.Adapter<SimpleTextCountViewHolder>() {
        private var items: List<Pair<String, Int>> = emptyList()
        fun update(list: List<Pair<String, Int>>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SimpleTextCountViewHolder {
            val v = android.view.LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return SimpleTextCountViewHolder(v)
        }
        override fun onBindViewHolder(holder: SimpleTextCountViewHolder, position: Int) {
            val (text, count) = items[position]
            holder.title.text = text
            holder.subtitle.text = "Plays: $count"
            holder.itemView.setOnClickListener { onClick(text) }
        }
        override fun getItemCount(): Int = items.size
    }

    // Adapter for albums that stores both ID and name
    class AlbumIdAdapter(val onClick: (String, String) -> Unit) : RecyclerView.Adapter<SimpleTextCountViewHolder>() {
        private var items: List<Triple<String, String, Int>> = emptyList() // (albumId, albumName, count)
        fun update(list: List<Triple<String, String, Int>>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SimpleTextCountViewHolder {
            val v = android.view.LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return SimpleTextCountViewHolder(v)
        }
        override fun onBindViewHolder(holder: SimpleTextCountViewHolder, position: Int) {
            val (albumId, albumName, count) = items[position]
            holder.title.text = albumName
            holder.subtitle.text = "Plays: $count"
            holder.itemView.setOnClickListener { onClick(albumId, albumName) }
        }
        override fun getItemCount(): Int = items.size
    }

    class SimpleTextCountViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(android.R.id.text1)
        val subtitle: TextView = v.findViewById(android.R.id.text2)
    }
}
