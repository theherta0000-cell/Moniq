package com.example.moniq.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.example.moniq.R
import com.example.moniq.model.Album
import com.example.moniq.util.ImageUrlHelper
import com.example.moniq.util.ServerManager

class AlbumAdapter(
    var items: List<Album>,
    private val onClick: (Album) -> Unit,
    private val compact: Boolean = false,
    private val onLongClick: ((Album) -> Unit)? = null
) : RecyclerView.Adapter<AlbumAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.albumCover)
        val name: TextView = itemView.findViewById(R.id.albumName)
        val artist: TextView = itemView.findViewById(R.id.albumArtist)
        val duration: TextView? = itemView.findViewById(R.id.albumDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (compact) R.layout.item_album_compact else R.layout.item_album_simple
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val alb = items[position]
        Log.d("AlbumAdapter", "onBind album id=${alb.id} name='${alb.name}' artist='${alb.artist}'")
        holder.name.text = if (alb.name.isNullOrBlank()) "(untitled album)" else alb.name
        Log.d("AlbumAdapter", "binding album: id=${alb.id}, name='${alb.name}', artist='${alb.artist}'")
        holder.artist.text = alb.artist ?: ""
        // ✅ No longer need SessionManager.host - ImageUrlHelper handles everything
val coverId = alb.coverArtId ?: alb.id
val coverUrl = ImageUrlHelper.getCoverArtUrl(coverId)

holder.image.load(coverUrl) {
    placeholder(R.drawable.ic_album)
    error(R.drawable.ic_album)  // ✅ Add error handling
    crossfade(true)
}
        holder.itemView.setOnClickListener { onClick(alb) }
        
        // Simple long click for compact mode to go to artist
        if (compact && onLongClick != null) {
            holder.itemView.setOnLongClickListener {
                onLongClick.invoke(alb)
                true
            }
        }

        // Album duration: compute by summing track durations (cached)
        try {
            val cached = durationCache[alb.id]
            if (cached != null) {
                holder.duration?.visibility = View.VISIBLE
                holder.duration?.text = formatDuration(cached)
            } else {
                holder.duration?.visibility = View.GONE
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val repo = com.example.moniq.music.MusicRepository()
                        val tracks = repo.getAlbumTracks(alb.id)
                        val total = tracks.sumOf { it.duration }
                        if (total > 0) {
                            durationCache[alb.id] = total
                            withContext(Dispatchers.Main) {
                                holder.duration?.visibility = View.VISIBLE
                                holder.duration?.text = formatDuration(total)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            val popup = android.widget.PopupMenu(ctx, holder.itemView)
            popup.menu.add("Download")
            popup.menu.add("Add to Playlist")
            popup.menu.add("Play next")
            popup.menu.add("Add to queue")
            if (!alb.artist.isNullOrBlank()) popup.menu.add(alb.artist)
            popup.setOnMenuItemClickListener { mi ->
                when (mi.title) {
                   "Download" -> {
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val repo = com.example.moniq.music.MusicRepository()
            val tracks = repo.getAlbumTracks(alb.id)
            tracks.forEach { track ->
                val streamUrl = findWorkingStreamUrl(track.id)
                if (streamUrl == null) {
                    Log.w("AlbumAdapter", "No server found for: ${track.title}")
                    return@forEach
                }
                com.example.moniq.player.DownloadManager.downloadTrack(
                    context = ctx,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = alb.name,
                    albumArtUrl = ImageUrlHelper.getCoverArtUrl(alb.coverArtId ?: alb.id),
                    streamUrl = streamUrl
                )
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, "Album download started", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, "Download failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    true
}
                    "Add to Playlist" -> {
                        val it = android.content.Intent(ctx, com.example.moniq.AlbumActivity::class.java)
                        it.putExtra("albumId", alb.id)
                        it.putExtra("albumTitle", alb.name)
                        it.putExtra("albumArtist", alb.artist)
                        it.putExtra("addAllToPlaylist", true)
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(it)
                        true
                    }
                    "Play next" -> {
    try {
        if (ctx is androidx.activity.ComponentActivity) {
            ctx.lifecycleScope.launch {
                val repo = com.example.moniq.music.MusicRepository()
                val tracks = repo.getAlbumTracks(alb.id)
                val tracksWithAlbum = tracks.map { it.copy(albumId = alb.id, albumName = alb.name) }
                com.example.moniq.player.AudioPlayer.initialize(ctx)
                com.example.moniq.player.AudioPlayer.playNext(tracksWithAlbum)
            }
                           } else {
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val repo = com.example.moniq.music.MusicRepository()
            val tracks = repo.getAlbumTracks(alb.id)
            val tracksWithAlbum = tracks.map { it.copy(albumId = alb.id, albumName = alb.name) }
            withContext(Dispatchers.Main) {
                com.example.moniq.player.AudioPlayer.initialize(ctx)
                com.example.moniq.player.AudioPlayer.playNext(tracksWithAlbum)
            }
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                        true
                    }
                    "Add to queue" -> {
                        try {
        if (ctx is androidx.activity.ComponentActivity) {
            ctx.lifecycleScope.launch {
                val repo = com.example.moniq.music.MusicRepository()
                val tracks = repo.getAlbumTracks(alb.id)
                val tracksWithAlbum = tracks.map { it.copy(albumId = alb.id, albumName = alb.name) }
                com.example.moniq.player.AudioPlayer.initialize(ctx)
                com.example.moniq.player.AudioPlayer.addToQueue(tracksWithAlbum)
            }
                            } else {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val repo = com.example.moniq.music.MusicRepository()
                    val tracks = repo.getAlbumTracks(alb.id)
                    val tracksWithAlbum = tracks.map { it.copy(albumId = alb.id, albumName = alb.name) }
                    withContext(Dispatchers.Main) {
                        com.example.moniq.player.AudioPlayer.initialize(ctx)
                        com.example.moniq.player.AudioPlayer.addToQueue(tracksWithAlbum)
                    }
                } catch (_: Exception) {}
            }
        }
                        } catch (_: Exception) {}
                        true
                    }
                    else -> {
                        val title = mi.title.toString()
                        if (!alb.artist.isNullOrBlank() && title == alb.artist) {
                            val it = android.content.Intent(ctx, com.example.moniq.SearchActivity::class.java)
                            it.putExtra("query", alb.artist)
                            it.putExtra("filter", "ARTISTS")
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
    }

    override fun getItemCount(): Int = items.size

    fun update(newList: List<Album>) {
        items = newList
        notifyDataSetChanged()
    }

    private fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private val durationCache = mutableMapOf<String, Int>()

   private suspend fun findWorkingStreamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
    val servers = ServerManager.getOrderedServers()
    
    for (server in servers) {
        try {
            val testUrl = "$server/track/?id=$trackId"
            val conn = java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"  // ✅ CHANGED FROM HEAD TO GET
            
            if (conn.responseCode == 200) {
                conn.disconnect()
                ServerManager.recordSuccess(server)
                return@withContext "$server/stream/?id=$trackId"
            }
            ServerManager.recordFailure(server)
            conn.disconnect()
        } catch (e: Exception) {
            continue
        }
    }
    return@withContext null
}
}
