
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
    
    // Check if coverArtId is already a full URL or just an ID
val coverUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(a.coverArtId)

holder.iv.load(coverUrl) {
    placeholder(R.drawable.ic_music_note)
    error(R.drawable.ic_music_note)
    crossfade(true)
}
    
    holder.itemView.setOnClickListener { onClick(a) }
}

    override fun getItemCount(): Int = items.size

    fun update(newList: List<Artist>) {
        items = newList
        notifyDataSetChanged()
    }
}
