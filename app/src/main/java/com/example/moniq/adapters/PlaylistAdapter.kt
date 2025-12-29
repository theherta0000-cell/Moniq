package com.example.moniq.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import coil.load
import androidx.recyclerview.widget.RecyclerView
import com.example.moniq.R
import com.example.moniq.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import com.example.moniq.util.ServerManager

class PlaylistAdapter(
    var items: List<Playlist>,
    private val onOpen: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit,
    private val onDownload: (Playlist) -> Unit = {}
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.playlistName)
        val count: TextView = itemView.findViewById(R.id.playlistCount)
        val menu: ImageButton = itemView.findViewById(R.id.playlistMenu)
        val cover: ImageView = itemView.findViewById(R.id.playlistCover)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.count.text = "${p.tracks.size}"
        
        val candidates = mutableListOf<String>()
        if (!p.coverArtId.isNullOrBlank()) {
            val artUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(p.coverArtId)
            if (!artUrl.isNullOrEmpty()) candidates.add(artUrl)
        }
        
        val first = p.tracks.firstOrNull()
        if (first != null && !first.coverArtId.isNullOrBlank()) {
            val artUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(first.coverArtId)
            if (!artUrl.isNullOrEmpty()) candidates.add(artUrl)
        }

        fun tryLoad(index: Int) {
            if (index >= candidates.size) {
                holder.cover.setImageResource(R.drawable.ic_album)
                return
            }
            val url = candidates[index]
            holder.cover.load(url) {
                placeholder(R.drawable.ic_album)
                crossfade(true)
                listener(onError = { _, _ -> tryLoad(index + 1) })
            }
        }

        if (candidates.isNotEmpty()) tryLoad(0) else holder.cover.setImageResource(R.drawable.ic_album)
        holder.itemView.setOnClickListener { onOpen(p) }
        holder.menu.setOnClickListener {
            val popup = android.widget.PopupMenu(holder.itemView.context, holder.menu)
            popup.menu.add("Download")
            popup.menu.add("Delete")
            popup.setOnMenuItemClickListener { mi ->
                when (mi.title) {
                    "Download" -> {
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                for (track in p.tracks) {
                                    val streamUrl = findWorkingStreamUrl(track.id)
                                    if (streamUrl == null) {
                                        Log.w("PlaylistAdapter", "No server found for: ${track.title}")
                                        continue
                                    }
                                    com.example.moniq.player.DownloadManager.downloadTrack(
                                        context = holder.itemView.context,
                                        trackId = track.id,
                                        title = track.title,
                                        artist = track.artist,
                                        album = track.albumName,
                                        albumArtUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(
                                            track.coverArtId ?: track.albumId ?: track.id
                                        ),
                                        streamUrl = streamUrl
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        holder.itemView.context,
                                        "Playlist download started",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        holder.itemView.context,
                                        "Download failed: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        true
                    }
                    "Delete" -> {
                        onDelete(p)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(list: List<Playlist>) {
        items = list
        notifyDataSetChanged()
    }

    private suspend fun findWorkingStreamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
    val servers = ServerManager.getOrderedServers()
        
        for (server in servers) {
            try {
                val testUrl = "$server/track/?id=$trackId"
                val conn = java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                
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