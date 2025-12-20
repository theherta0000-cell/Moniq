package com.example.moniq.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.moniq.R
import coil.load
import com.example.moniq.model.Track

class QueueAdapter(private var items: List<Track>, private val callbacks: Callback) : RecyclerView.Adapter<QueueAdapter.VH>() {
    private var currentIndex: Int = -1

    fun setCurrentIndex(idx: Int) {
        currentIndex = idx
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        val mutable = items.toMutableList()
        val it = mutable.removeAt(from)
        mutable.add(to, it)
        items = mutable
        notifyItemMoved(from, to)
    }

    fun removeAt(pos: Int) {
        val mutable = items.toMutableList()
        mutable.removeAt(pos)
        items = mutable
        notifyItemRemoved(pos)
    }
    interface Callback {
        fun onPlay(position: Int)
        fun onRemove(position: Int)
    }

    fun update(list: List<Track>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.queue_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.title.text = t.title
        holder.artist.text = t.artist
        // load cover art into queue item (support full URIs or server-side cover IDs)
        try {
            val host = com.example.moniq.SessionManager.host
            val coverId = t.coverArtId ?: t.albumId ?: t.id
            val coverSrc: String? = when {
                coverId.isNullOrBlank() -> null
                coverId.startsWith("http://") || coverId.startsWith("https://") -> coverId
                coverId.startsWith("content://") || coverId.startsWith("file://") -> coverId
                host != null -> android.net.Uri.parse(host).buildUpon()
                    .appendPath("rest").appendPath("getCoverArt.view")
                    .appendQueryParameter("id", coverId)
                    .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                    .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                    .build().toString()
                else -> null
            }
            if (!coverSrc.isNullOrBlank()) {
                holder.cover?.load(coverSrc) { placeholder(R.drawable.ic_album); crossfade(true) }
            } else {
                holder.cover?.setImageResource(R.drawable.ic_album)
            }
        } catch (_: Exception) {}
        holder.play.setOnClickListener { callbacks.onPlay(position) }
        holder.remove.setOnClickListener { callbacks.onRemove(position) }
        // highlight currently playing
        if (position == currentIndex) {
            holder.itemView.setBackgroundColor(android.graphics.Color.parseColor("#143B82"))
            holder.title.setTextColor(android.graphics.Color.WHITE)
            holder.artist.setTextColor(android.graphics.Color.WHITE)
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.title.setTextColor(android.graphics.Color.parseColor("#2D3748"))
            holder.artist.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: android.widget.ImageView? = view.findViewById(R.id.queue_item_cover)
        val title: TextView = view.findViewById(R.id.queue_item_title)
        val artist: TextView = view.findViewById(R.id.queue_item_artist)
        val play: ImageButton = view.findViewById(R.id.queue_item_play)
        val remove: ImageButton = view.findViewById(R.id.queue_item_remove)
    }
}
