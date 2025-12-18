
package com.example.moniq.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.moniq.R
import com.example.moniq.model.Artist

class ArtistAdapter(
    var items: List<Artist>,
    private val onClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iv: ImageView = itemView.findViewById(R.id.artistImage)
        val tv: TextView = itemView.findViewById(R.id.artistName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_artist_simple, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.tv.text = a.name
        val host = com.example.moniq.SessionManager.host
        if (host != null) {
            val coverId = a.coverArtId ?: a.id
            val coverUri = android.net.Uri.parse(host).buildUpon()
                .appendPath("rest")
                .appendPath("getCoverArt.view")
                .appendQueryParameter("id", coverId)
                .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                .build().toString()
            holder.iv.load(coverUri) {
                placeholder(R.drawable.ic_music_note)
                crossfade(true)
            }
        } else {
            holder.iv.setImageResource(android.R.drawable.ic_menu_myplaces)
        }
        holder.itemView.setOnClickListener { onClick(a) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newList: List<Artist>) {
        items = newList
        notifyDataSetChanged()
    }
}
