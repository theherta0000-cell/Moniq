package com.example.moniq

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.moniq.player.AudioPlayer
import com.example.moniq.player.RecentlyPlayedManager
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentlyListActivity : ComponentActivity() {
    data class AlbumInfo(
            val albumId: String,
            val albumName: String,
            val artist: String,
            val playCount: Int,
            val coverArtId: String?
    )
    private var showingAllTracks = false
    private var showingAllArtists = false
    private var showingAllAlbums = false

    private var allTracks: List<com.example.moniq.model.Track> = emptyList()
    private var allArtists: List<Pair<String, Int>> = emptyList()
    private var allAlbums: List<AlbumInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recently_list)

        val tracksRecycler = findViewById<RecyclerView?>(R.id.recent_list_tracks)
        val artistsRecycler = findViewById<RecyclerView?>(R.id.recent_list_artists)
        val albumsRecycler = findViewById<RecyclerView?>(R.id.recent_list_albums)

        val btnShowMoreTracks = findViewById<Button?>(R.id.btn_show_more_tracks)
        val btnShowMoreArtists = findViewById<Button?>(R.id.btn_show_more_artists)
        val btnShowMoreAlbums = findViewById<Button?>(R.id.btn_show_more_albums)

        // Track adapter
        val trackAdapter =
                com.example.moniq.adapters.TrackAdapter(
                        emptyList(),
                        onPlay = { t, pos ->
                            val coverId = t.coverArtId ?: t.albumId ?: t.id
                            val albumArtUrl =
                                    com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(coverId)
                            AudioPlayer.playTrack(
                                    this,
                                    t.id,
                                    t.title,
                                    t.artist,
                                    albumArtUrl,
                                    t.albumId,
                                    t.albumName
                            )
                        },
                        onDownload = { t ->
                            lifecycleScope.launch {
                                val coverId = t.coverArtId ?: t.albumId ?: t.id
                                val albumArtUrl =
                                        com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(
                                                coverId
                                        )
                                val ok =
                                        com.example.moniq.player.DownloadManager.downloadTrack(
                                                this@RecentlyListActivity,
                                                t.id,
                                                t.title,
                                                t.artist,
                                                null,
                                                albumArtUrl
                                        )
                                android.widget.Toast.makeText(
                                                this@RecentlyListActivity,
                                                if (ok) "Downloaded" else "Download failed",
                                                android.widget.Toast.LENGTH_LONG
                                        )
                                        .show()
                            }
                        }
                )
        tracksRecycler?.layoutManager = LinearLayoutManager(this)
        tracksRecycler?.adapter = trackAdapter

        // Artist adapter with images
        val artistAdapter =
                ArtistImageAdapter(this) { name, coverArtId ->
                    try {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val repo = com.example.moniq.search.SearchRepository()
                                val res = repo.search(name)
                                val match =
                                        res.artists.firstOrNull {
                                            it.name.equals(name, ignoreCase = true)
                                        }
                                withContext(Dispatchers.Main) {
                                    if (match != null && match.id.isNotBlank()) {
                                        val i =
                                                Intent(
                                                        this@RecentlyListActivity,
                                                        com.example.moniq.ArtistActivity::class.java
                                                )
                                        i.putExtra("artistId", match.id)
                                        i.putExtra("artistName", match.name)
                                        if (!coverArtId.isNullOrBlank()) {
                                            i.putExtra("artistCoverId", coverArtId)
                                        }
                                        startActivity(i)
                                    } else {
                                        val i =
                                                Intent(
                                                        this@RecentlyListActivity,
                                                        com.example.moniq.SearchActivity::class.java
                                                )
                                        i.putExtra("query", name)
                                        startActivity(i)
                                    }
                                }
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main) {
                                    val i =
                                            Intent(
                                                    this@RecentlyListActivity,
                                                    com.example.moniq.SearchActivity::class.java
                                            )
                                    i.putExtra("query", name)
                                    startActivity(i)
                                }
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

        // Album adapter with images
        val albumAdapter =
                AlbumImageAdapter(this) { albumId, albumName, artist ->
                    val i = Intent(this, AlbumActivity::class.java)
                    i.putExtra("albumId", albumId)
                    i.putExtra("albumTitle", albumName)
                    i.putExtra("albumArtist", artist)
                    startActivity(i)
                }
        albumsRecycler?.layoutManager = LinearLayoutManager(this)
        albumsRecycler?.adapter = albumAdapter

        // Button click listeners
        btnShowMoreTracks?.setOnClickListener {
            showingAllTracks = !showingAllTracks
            trackAdapter.update(if (showingAllTracks) allTracks else allTracks.take(10))
            btnShowMoreTracks.text = if (showingAllTracks) "Show Less" else "Show More"
        }

        btnShowMoreArtists?.setOnClickListener {
            showingAllArtists = !showingAllArtists
            artistAdapter.update(if (showingAllArtists) allArtists else allArtists.take(10))
            btnShowMoreArtists.text = if (showingAllArtists) "Show Less" else "Show More"
        }

        btnShowMoreAlbums?.setOnClickListener {
            showingAllAlbums = !showingAllAlbums
            albumAdapter.update(if (showingAllAlbums) allAlbums else allAlbums.take(10))
            btnShowMoreAlbums.text = if (showingAllAlbums) "Show Less" else "Show More"
        }

        // Load data
        lifecycleScope.launch {
            try {
                val rpm = RecentlyPlayedManager(this@RecentlyListActivity.applicationContext)
                allTracks = rpm.all(100)
                trackAdapter.update(allTracks.take(10))
                btnShowMoreTracks?.visibility = if (allTracks.size > 10) View.VISIBLE else View.GONE

                val artistCounts =
                        rpm.getArtistPlayCounts().toList().sortedByDescending { it.second }
                allArtists = artistCounts.map { Pair(it.first, it.second) }
                artistAdapter.update(allArtists.take(10))
                btnShowMoreArtists?.visibility =
                        if (allArtists.size > 10) View.VISIBLE else View.GONE

                val albumIdToInfo = mutableMapOf<String, Triple<String, String, String?>>()
                allTracks.forEach { track ->
                    if (!track.albumId.isNullOrBlank() && !track.albumName.isNullOrBlank()) {
                        // Store album name, artist, and cover art ID (prefer existing if present)
                        if (!albumIdToInfo.containsKey(track.albumId)) {
                            albumIdToInfo[track.albumId] =
                                    Triple(
                                            track.albumName,
                                            track.artist ?: "Unknown Artist",
                                            track.coverArtId // This should already be an absolute
                                            // URL from RecentlyPlayedManager
                                            )
                        }
                    }
                }

                val albumCounts = rpm.getAlbumPlayCounts().toList().sortedByDescending { it.second }
                allAlbums =
                        albumCounts.mapNotNull { (albumId, count) ->
                            val info = albumIdToInfo[albumId]
                            if (info != null)
                                    AlbumInfo(albumId, info.first, info.second, count, info.third)
                            else null
                        }
                albumAdapter.update(allAlbums.take(10))
                btnShowMoreAlbums?.visibility = if (allAlbums.size > 10) View.VISIBLE else View.GONE
            } catch (_: Exception) {}
        }
    }

    // Artist adapter with circular images
    class ArtistImageAdapter(
            private val activity: RecentlyListActivity,
            val onClick: (String, String?) -> Unit
    ) : RecyclerView.Adapter<ArtistImageAdapter.ImageItemViewHolder>() {
        private var items: List<Pair<String, Int>> = emptyList()
        fun update(list: List<Pair<String, Int>>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
                parent: android.view.ViewGroup,
                viewType: Int
        ): ImageItemViewHolder {
            val v =
                    android.view.LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_artist_album_with_image, parent, false)
            return ImageItemViewHolder(v)
        }

        override fun onBindViewHolder(holder: ImageItemViewHolder, position: Int) {
            val (name, count) = items[position]
            holder.title.text = name
            holder.subtitle.text = "Plays: $count"
            var coverArtId: String? = null
            holder.itemView.setOnClickListener { onClick(name, coverArtId) }

            // Try to load artist image using Coil
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val repo = com.example.moniq.search.SearchRepository()
                    val res = repo.search(name)
                    val match = res.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    if (match != null && !match.coverArtId.isNullOrBlank()) {
                        coverArtId = match.coverArtId
                        val imageUrl =
                                com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(
                                        match.coverArtId
                                )

                        withContext(Dispatchers.Main) {
                            if (imageUrl != null) {
                                holder.image.load(imageUrl) {
                                    placeholder(android.R.drawable.ic_menu_gallery)
                                    error(android.R.drawable.ic_menu_gallery)
                                    crossfade(true)
                                    transformations(coil.transform.CircleCropTransformation())
                                    listener(
                                            onError = { _, result ->
                                                android.util.Log.e(
                                                        "ArtistImageAdapter",
                                                        "Failed to load artist art: $imageUrl",
                                                        result.throwable
                                                )
                                            }
                                    )
                                }
                            } else {
                                holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class ImageItemViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.item_image)
            val title: TextView = v.findViewById(android.R.id.text1)
            val subtitle: TextView = v.findViewById(android.R.id.text2)
        }
    }

    // Album adapter with circular images
    class AlbumImageAdapter(
            private val activity: RecentlyListActivity,
            val onClick: (String, String, String) -> Unit
    ) : RecyclerView.Adapter<AlbumImageAdapter.ImageItemViewHolder>() {
        private var items: List<AlbumInfo> = emptyList()
        fun update(list: List<AlbumInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
                parent: android.view.ViewGroup,
                viewType: Int
        ): ImageItemViewHolder {
            val v =
                    android.view.LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_artist_album_with_image, parent, false)
            return ImageItemViewHolder(v)
        }

        override fun onBindViewHolder(holder: ImageItemViewHolder, position: Int) {
            val album = items[position]
            holder.title.text = album.albumName
            holder.subtitle.text = "${album.artist} • ${album.playCount} plays"
            holder.itemView.setOnClickListener {
                onClick(album.albumId, album.albumName, album.artist)
            }

            // Load album cover using Coil
            val imageUrl =
                    com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(
                            album.coverArtId ?: album.albumId
                    )

            if (imageUrl != null) {
                holder.image.load(imageUrl) {
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_gallery)
                    crossfade(true)
                    transformations(coil.transform.CircleCropTransformation())
                    listener(
                            onError = { _, result ->
                                android.util.Log.e(
                                        "AlbumImageAdapter",
                                        "Failed to load: $imageUrl",
                                        result.throwable
                                )
                            }
                    )
                }
            } else {
                holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        override fun getItemCount(): Int = items.size

        class ImageItemViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.item_image)
            val title: TextView = v.findViewById(android.R.id.text1)
            val subtitle: TextView = v.findViewById(android.R.id.text2)
        }
    }

    companion object {
        private fun loadBitmap(urlString: String): Bitmap? {
            return try {
                val url = URL(urlString)
                val connection = url.openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                val input = connection.getInputStream()
                BitmapFactory.decodeStream(input)
            } catch (_: Exception) {
                null
            }
        }

        private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
            val size = minOf(bitmap.width, bitmap.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val paint =
                    Paint().apply {
                        isAntiAlias = true
                        color = Color.BLACK
                    }

            val rect = Rect(0, 0, size, size)
            val rectF = RectF(rect)

            canvas.drawOval(rectF, paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

            val left = (bitmap.width - size) / 2
            val top = (bitmap.height - size) / 2
            val srcRect = Rect(left, top, left + size, top + size)

            canvas.drawBitmap(bitmap, srcRect, rect, paint)

            return output
        }
    }
}
