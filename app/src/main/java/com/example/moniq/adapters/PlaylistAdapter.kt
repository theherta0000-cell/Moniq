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
        // prefer playlist custom cover, else try to load cover from first track in playlist
        val candidates = mutableListOf<String>()
        if (!p.coverArtId.isNullOrBlank()) {
            candidates.add(p.coverArtId!!)
        }
        // try to load cover from first track
        val host = com.example.moniq.SessionManager.host
        val first = p.tracks.firstOrNull()
        if (first != null) {
            if (!first.coverArtId.isNullOrBlank()) {
                val c = first.coverArtId
                if (c.startsWith("http")) candidates.add(c)
            }
            if (!first.coverArtId.isNullOrBlank() && host != null && !first.coverArtId.startsWith("http")) {
                candidates.add(
                        android.net.Uri.parse(host)
                                .buildUpon()
                                .appendPath("rest")
                                .appendPath("getCoverArt.view")
                                .appendQueryParameter("id", first.coverArtId)
                                .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                                .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                                .build()
                                .toString()
                )
            }
            if (!first.albumId.isNullOrBlank() && host != null) {
                candidates.add(
                        android.net.Uri.parse(host)
                                .buildUpon()
                                .appendPath("rest")
                                .appendPath("getCoverArt.view")
                                .appendQueryParameter("id", first.albumId)
                                .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                                .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                                .build()
                                .toString()
                )
            }
            if (!first.id.isNullOrBlank() && host != null) {
                candidates.add(
                        android.net.Uri.parse(host)
                                .buildUpon()
                                .appendPath("rest")
                                .appendPath("getCoverArt.view")
                                .appendQueryParameter("id", first.id)
                                .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                                .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                                .build()
                                .toString()
                )
            }
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
                    "Download" -> { onDownload(p); true }
                    "Delete" -> { onDelete(p); true }
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
}
