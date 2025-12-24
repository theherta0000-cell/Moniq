package com.example.moniq.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.example.moniq.search.SearchRepository
import com.example.moniq.R
import com.example.moniq.model.Track

class TrackAdapter(
        var items: List<Track>,
        private val onPlay: (Track, Int) -> Unit,
        private val onDownload: (Track) -> Unit,
        private val onAddToPlaylist: (Track) -> Unit = {},
        private val onRemoveFromPlaylist: (Track) -> Unit = {},
        private val showRemoveOption: Boolean = false
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.trackTitle)
        val artist: TextView = itemView.findViewById(R.id.trackArtist)
        val duration: TextView = itemView.findViewById(R.id.trackDuration)
        val menuBtn: ImageButton = itemView.findViewById(R.id.trackMenu)
        val cover: ImageView = itemView.findViewById(R.id.trackCover)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_track_simple, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.title.text = t.title
        // show both artist and album name if available
        holder.artist.text =
                if (!t.albumName.isNullOrBlank()) {
                    if (t.artist.isBlank()) t.albumName else "${t.artist} · ${t.albumName}"
                } else t.artist
        // menu button removed — long-press provides the same options
        holder.menuBtn.visibility = View.GONE
        holder.itemView.setOnClickListener { onPlay(t, position) }
        // show formatted duration
        try {
            val s = t.durationSec
            holder.duration.text =
                    if (s <= 0) ""
                    else {
                        val m = s / 60
                        val sec = s % 60
                        String.format("%d:%02d", m, sec)
                    }
        } catch (_: Exception) {
            holder.duration.text = ""
        }
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            val popup = android.widget.PopupMenu(ctx, holder.itemView)
            popup.menu.add("Download")
            popup.menu.add("Add to Playlist")
            if (showRemoveOption) popup.menu.add("Delete from playlist")
            popup.menu.add("Play next")
            popup.menu.add("Add to queue")
            if (!t.artist.isNullOrBlank()) popup.menu.add(t.artist)
            if (!t.albumName.isNullOrBlank()) popup.menu.add(t.albumName)
            popup.setOnMenuItemClickListener { mi ->
                when (mi.title) {
                    "Download" -> {
                        onDownload(t)
                        true
                    }
                    "Add to Playlist" -> {
                        onAddToPlaylist(t)
                        true
                    }
                    "Delete from playlist" -> {
                        onRemoveFromPlaylist(t)
                        true
                    }
                    // Replace the "Play next" and "Add to queue" menu item handlers in TrackAdapter.kt with this:

"Play next" -> {
    com.example.moniq.player.AudioPlayer.initialize(ctx)
    // Build proper cover URL with correct password handling
    val enrichedTrack = t.copy(
        coverArtId = if (!t.coverArtId.isNullOrBlank() && t.coverArtId.startsWith("http")) {
            t.coverArtId
        } else {
            val coverId = t.coverArtId ?: t.albumId ?: t.id
            val host = com.example.moniq.SessionManager.host
            if (host != null) {
                // FIXED: Use proper password handling like AudioPlayer does
                val passwordRaw = com.example.moniq.SessionManager.password ?: ""
                val legacy = com.example.moniq.SessionManager.legacy
                val pwParam = if (legacy) passwordRaw else com.example.moniq.util.Crypto.md5(passwordRaw)
                
                android.net.Uri.parse(host).buildUpon()
                    .appendPath("rest")
                    .appendPath("getCoverArt.view")
                    .appendQueryParameter("id", coverId)
                    .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                    .appendQueryParameter("p", pwParam)  // Use the properly formatted password
                    .build().toString()
            } else t.coverArtId
        }
    )
    com.example.moniq.player.AudioPlayer.playNext(enrichedTrack)
    true
}
"Add to queue" -> {
    com.example.moniq.player.AudioPlayer.initialize(ctx)
    // Build proper cover URL with correct password handling
    val enrichedTrack = t.copy(
        coverArtId = if (!t.coverArtId.isNullOrBlank() && t.coverArtId.startsWith("http")) {
            t.coverArtId
        } else {
            val coverId = t.coverArtId ?: t.albumId ?: t.id
            val host = com.example.moniq.SessionManager.host
            if (host != null) {
                // FIXED: Use proper password handling like AudioPlayer does
                val passwordRaw = com.example.moniq.SessionManager.password ?: ""
                val legacy = com.example.moniq.SessionManager.legacy
                val pwParam = if (legacy) passwordRaw else com.example.moniq.util.Crypto.md5(passwordRaw)
                
                android.net.Uri.parse(host).buildUpon()
                    .appendPath("rest")
                    .appendPath("getCoverArt.view")
                    .appendQueryParameter("id", coverId)
                    .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                    .appendQueryParameter("p", pwParam)  // Use the properly formatted password
                    .build().toString()
            } else t.coverArtId
        }
    )
    com.example.moniq.player.AudioPlayer.addToQueue(enrichedTrack)
    true
}
                    else -> {
                        // If user tapped artist or album name string, try to navigate
                        val title = mi.title.toString()
                        // Artist: launch SearchActivity with query prefilled
                        if (!t.artist.isNullOrBlank() && title == t.artist) {
                            // Attempt to resolve the artist name to an id via SearchRepository
                            try {
                                GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        val repo = SearchRepository()
                                        val res = repo.search(t.artist)
                                        val match = res.artists.firstOrNull { a -> a.name.equals(t.artist, ignoreCase = true) }
                                        if (match != null && match.id.isNotBlank()) {
                                            val it = android.content.Intent(ctx, com.example.moniq.ArtistActivity::class.java)
                                            it.putExtra("artistId", match.id)
                                            it.putExtra("artistName", match.name)
                                            // pass cover id when available to help ArtistActivity render header quickly
                                            match.coverArtId?.let { c -> it.putExtra("artistCoverId", c) }
                                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post { ctx.startActivity(it) }
                                        } else {
                                            val it = android.content.Intent(ctx, com.example.moniq.SearchActivity::class.java)
                                            it.putExtra("query", t.artist)
                                            it.putExtra("filter", "ARTISTS")
                                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post { ctx.startActivity(it) }
                                        }
                                    } catch (_: Exception) {
                                        val it = android.content.Intent(ctx, com.example.moniq.SearchActivity::class.java)
                                        it.putExtra("query", t.artist)
                                        it.putExtra("filter", "ARTISTS")
                                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        android.os.Handler(android.os.Looper.getMainLooper()).post { ctx.startActivity(it) }
                                    }
                                }
                            } catch (_: Exception) {
                                val it = android.content.Intent(ctx, com.example.moniq.SearchActivity::class.java)
                                it.putExtra("query", t.artist)
                                it.putExtra("filter", "ARTISTS")
                                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(it)
                            }
                            return@setOnMenuItemClickListener true
                        }
                        // Album: open AlbumActivity if album id available
                        if (!t.albumName.isNullOrBlank() &&
                                        title == t.albumName &&
                                        !t.albumId.isNullOrBlank()
                        ) {
                            val it =
                                    android.content.Intent(
                                            ctx,
                                            com.example.moniq.AlbumActivity::class.java
                                    )
                            it.putExtra("albumId", t.albumId)
                            it.putExtra("albumTitle", t.albumName)
                            it.putExtra("albumArtist", t.artist)
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(it)
                            return@setOnMenuItemClickListener true
                        }
                        false
                    }
                }
            }
            popup.show()
            true
        }
        // load cover art with multiple fallbacks to improve reliability
        val host = com.example.moniq.SessionManager.host
        val candidates = mutableListOf<String>()
        // if coverArtId is an absolute URL, try it first
        if (!t.coverArtId.isNullOrBlank()) {
            val c = t.coverArtId
            if (c.startsWith("http")) candidates.add(c)
        }
        // next, try coverArtId via host
        if (!t.coverArtId.isNullOrBlank() && host != null && !t.coverArtId.startsWith("http")) {
            candidates.add(
                    android.net.Uri.parse(host)
                            .buildUpon()
                            .appendPath("rest")
                            .appendPath("getCoverArt.view")
                            .appendQueryParameter("id", t.coverArtId)
                            .appendQueryParameter(
                                    "u",
                                    com.example.moniq.SessionManager.username ?: ""
                            )
                            .appendQueryParameter(
                                    "p",
                                    com.example.moniq.SessionManager.password ?: ""
                            )
                            .build()
                            .toString()
            )
        }
        // fallback: albumId via host
        if (!t.albumId.isNullOrBlank() && host != null) {
            candidates.add(
                    android.net.Uri.parse(host)
                            .buildUpon()
                            .appendPath("rest")
                            .appendPath("getCoverArt.view")
                            .appendQueryParameter("id", t.albumId)
                            .appendQueryParameter(
                                    "u",
                                    com.example.moniq.SessionManager.username ?: ""
                            )
                            .appendQueryParameter(
                                    "p",
                                    com.example.moniq.SessionManager.password ?: ""
                            )
                            .build()
                            .toString()
            )
        }
        // finally, try track id via host
        if (!t.id.isNullOrBlank() && host != null) {
            candidates.add(
                    android.net.Uri.parse(host)
                            .buildUpon()
                            .appendPath("rest")
                            .appendPath("getCoverArt.view")
                            .appendQueryParameter("id", t.id)
                            .appendQueryParameter(
                                    "u",
                                    com.example.moniq.SessionManager.username ?: ""
                            )
                            .appendQueryParameter(
                                    "p",
                                    com.example.moniq.SessionManager.password ?: ""
                            )
                            .build()
                            .toString()
            )
        }

        fun tryLoad(index: Int) {
            if (index >= candidates.size) {
                holder.cover.setImageResource(R.drawable.ic_album)
                return
            }
            val url = candidates[index]
            android.util.Log.d("TrackAdapter", "Trying cover URL [$index/${candidates.size}]: $url")
            holder.cover.load(url) {
    placeholder(R.drawable.ic_album)
    crossfade(true)
    transformations(coil.transform.RoundedCornersTransformation(8f * holder.itemView.resources.displayMetrics.density))
    listener(
                        onSuccess = { _, _ ->
                            android.util.Log.d("TrackAdapter", "Cover load succeeded: $url")
                            // Persist resolved absolute cover URL for this track so Recently Played
                            // can use it
                            try {
                                val tid = t.id
                                if (!tid.isNullOrBlank()) {
                                    val ctx = holder.itemView.context.applicationContext
                                    kotlin.concurrent.thread {
                                        try {
                                            val rpm =
                                                    com.example.moniq.player.RecentlyPlayedManager(
                                                            ctx
                                                    )
                                            rpm.setCoverForTrack(tid, url)
                                            android.util.Log.d(
                                                    "TrackAdapter",
                                                    "Persisted cover for $tid: $url"
                                            )
                                        } catch (e: Exception) {
                                            android.util.Log.w(
                                                    "TrackAdapter",
                                                    "Failed to persist cover for $tid",
                                                    e
                                            )
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        },
                        onError = { _, error ->
                            android.util.Log.w(
                                    "TrackAdapter",
                                    "Cover load failed: $url",
                                    error.throwable
                            )
                            tryLoad(index + 1)
                        }
                )
            }
        }

        if (candidates.isNotEmpty()) tryLoad(0)
        else holder.cover.setImageResource(R.drawable.ic_album)
    }

    override fun getItemCount(): Int = items.size

    fun update(newList: List<Track>) {
        items = newList
        notifyDataSetChanged()
    }
}
