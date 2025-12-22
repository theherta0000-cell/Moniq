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

class AlbumAdapter(
    var items: List<Album>,
    private val onClick: (Album) -> Unit,
    private val compact: Boolean = false
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
        val host = com.example.moniq.SessionManager.host
        if (host != null) {
            val coverId = alb.coverArtId ?: alb.id
            val coverUri = android.net.Uri.parse(host).buildUpon()
                .appendPath("rest")
                .appendPath("getCoverArt.view")
                .appendQueryParameter("id", coverId)
                .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                .build().toString()
            holder.image.load(coverUri) {
                placeholder(R.drawable.ic_album)
                crossfade(true)
            }
        } else {
            holder.image.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        holder.itemView.setOnClickListener { onClick(alb) }

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
                        val total = tracks.sumOf { it.durationSec }
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
                        val it = android.content.Intent(ctx, com.example.moniq.AlbumActivity::class.java)
                        it.putExtra("albumId", alb.id)
                        it.putExtra("albumTitle", alb.name)
                        it.putExtra("albumArtist", alb.artist)
                        it.putExtra("startDownload", true)
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(it)
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
}
