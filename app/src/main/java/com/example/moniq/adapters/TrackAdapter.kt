package com.example.moniq.adapters

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.moniq.R
import com.example.moniq.model.Track
import com.example.moniq.search.SearchRepository
import com.example.moniq.util.ImageUrlHelper
import com.example.moniq.util.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    if (t.artist.isNullOrBlank()) {
                        t.albumName
                    } else {
                        "${t.artist} · ${t.albumName}"
                    }
                } else {
                    t.artist ?: ""
                }

        // menu button removed — long-press provides the same options
        holder.menuBtn.visibility = View.GONE
        holder.itemView.setOnClickListener { onPlay(t, position) }
        // show formatted duration
        try {
            val s = t.duration
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
        // Show audio quality badge
        val qualityView = holder.itemView.findViewById<TextView>(R.id.trackQuality)

        when {
    // ATMOS - check both audioModes and mediaMetadataTags
    t.audioModes?.contains("DOLBY_ATMOS") == true ||
    t.mediaMetadataTags?.contains("DOLBY_ATMOS") == true -> {
        qualityView.text = "ATMOS"
        qualityView.setBackgroundColor(0xFF1565C0.toInt()) // Blue
        qualityView.visibility = View.VISIBLE
    }
    // HI-RES - check both audioModes (search) and mediaMetadataTags (album)
    t.audioModes?.contains("HIRES_LOSSLESS") == true ||
    t.mediaMetadataTags?.contains("HIRES_LOSSLESS") == true || 
    t.mediaMetadataTags?.contains("HI_RES_LOSSLESS") == true -> {
        qualityView.text = "HI-RES"
        qualityView.setBackgroundColor(0xFF6A1B9A.toInt()) // Purple
        qualityView.visibility = View.VISIBLE
    }
    // LOSSLESS - check both sources
    t.mediaMetadataTags?.contains("LOSSLESS") == true || 
    t.audioModes?.contains("LOSSLESS") == true ||
    t.audioQuality == "LOSSLESS" -> {
        qualityView.text = "LOSSLESS"
        qualityView.setBackgroundColor(0xFF2E7D32.toInt()) // Green
        qualityView.visibility = View.VISIBLE
    }
    else -> qualityView.visibility = View.GONE
}
        holder.itemView.setOnLongClickListener {
    val ctx = holder.itemView.context
    val popup = android.widget.PopupMenu(ctx, holder.itemView)
    popup.menu.add("Download")
    popup.menu.add("Add to Playlist")
    if (showRemoveOption) popup.menu.add("Delete from playlist")
    popup.menu.add("Play next")
    popup.menu.add("Add to queue")
    popup.menu.add("Track Info")
    if (!t.artist.isNullOrBlank()) popup.menu.add(t.artist)
    if (!t.albumName.isNullOrBlank()) popup.menu.add(t.albumName)

    popup.setOnMenuItemClickListener { mi ->
        when (mi.title) {
            "Download" -> {
    val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
        .setTitle("Downloading...")
        .setMessage("Fetching track information...")
        .setView(android.widget.ProgressBar(ctx).apply { 
            isIndeterminate = true
            setPadding(48, 48, 48, 48)
        })
        .setCancelable(false)
        .create()
    progressDialog.show()
    
    GlobalScope.launch(Dispatchers.IO) {
        try {
            // Fetch full track info with extended metadata
            val info = com.example.moniq.util.TrackInfoFetcher.fetchTrackInfo(t.id)
            
            withContext(Dispatchers.Main) {
                progressDialog.setMessage("Downloading...")
            }
            
            // Download with full metadata
            val success = com.example.moniq.player.DownloadManager.downloadTrackWithProgress(
                ctx,
                t.id,
                info?.title ?: t.title,
                info?.artists?.joinToString(", ") { it.name } ?: t.artist,
                info?.album?.title ?: t.albumName,
                info?.album?.cover ?: t.coverArtId,
                info?.trackNumber,
                null, // year
                null, // genre
                t.streamUrl,
                info?.bpm,
                info?.isrc,
                info?.copyright,
                info?.replayGain
            ) { progress ->
                // Update progress on main thread
                GlobalScope.launch(Dispatchers.Main) {
                    if (progress > 0) {
                        progressDialog.setMessage("Downloading... $progress%")
                    }
                }
            }
            
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (success) {
                    android.widget.Toast.makeText(ctx, "✓ Downloaded successfully", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(ctx, "✗ Download failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    true
}
            "Add to Playlist" -> {
                val playlistManager = com.example.moniq.player.PlaylistManager(ctx)
                val playlists = playlistManager.list()
                
                if (playlists.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "No playlists found. Create one first.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val playlistNames = playlists.map { it.name }.toTypedArray()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("Add to Playlist")
                        .setItems(playlistNames) { _, which ->
                            val selectedPlaylist = playlists[which]
                            playlistManager.addTrack(selectedPlaylist.id, t)
                            android.widget.Toast.makeText(ctx, "Added to ${selectedPlaylist.name}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                true
            }
            "Delete from playlist" -> {
                onRemoveFromPlaylist(t)
                true
            }
            "Play next" -> {
                com.example.moniq.player.AudioPlayer.initialize(ctx)
                com.example.moniq.player.AudioPlayer.playNext(t)
                true
            }
            "Add to queue" -> {
                com.example.moniq.player.AudioPlayer.initialize(ctx)
                com.example.moniq.player.AudioPlayer.addToQueue(t)
                true
            }
            "Track Info" -> {
                val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("Loading track info...")
                    .setView(android.widget.ProgressBar(ctx).apply { 
                        isIndeterminate = true
                        setPadding(48, 48, 48, 48)
                    })
                    .setCancelable(true)
                    .create()
                progressDialog.show()
                
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val info = com.example.moniq.util.TrackInfoFetcher.fetchTrackInfo(t.id)
                        
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            
                            if (info != null) {
                                showTrackInfoDialog(ctx, info)
                            } else {
                                android.widget.Toast.makeText(ctx, "Failed to load track info", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            else -> {
                val title = mi.title.toString()
                
                // Artist handling
                if (!t.artist.isNullOrBlank() && title == t.artist) {
                    try {
                        GlobalScope.launch(Dispatchers.IO) {
                            val repo = SearchRepository()
                            val res = repo.search(t.artist)
                            val match = res.artists.firstOrNull { a -> a.name.equals(t.artist, ignoreCase = true) }
                            
                            val intent = if (match != null && match.id.isNotBlank()) {
                                android.content.Intent(ctx, com.example.moniq.ArtistActivity::class.java).apply {
                                    putExtra("artistId", match.id)
                                    putExtra("artistName", match.name)
                                    match.coverArtId?.let { c -> putExtra("artistCoverId", c) }
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            } else {
                                android.content.Intent(ctx, com.example.moniq.SearchActivity::class.java).apply {
                                    putExtra("query", t.artist)
                                    putExtra("filter", "ARTISTS")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).post { ctx.startActivity(intent) }
                        }
                    } catch (_: Exception) {
                        val intent = android.content.Intent(ctx, com.example.moniq.SearchActivity::class.java).apply {
                            putExtra("query", t.artist)
                            putExtra("filter", "ARTISTS")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    }
                    return@setOnMenuItemClickListener true
                }

                // Album handling
                if (!t.albumName.isNullOrBlank() && title == t.albumName && !t.albumId.isNullOrBlank()) {
                    val intent = android.content.Intent(ctx, com.example.moniq.AlbumActivity::class.java).apply {
                        putExtra("albumId", t.albumId)
                        putExtra("albumTitle", t.albumName)
                        putExtra("albumArtist", t.artist)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    return@setOnMenuItemClickListener true
                }

                false
            }
        }
    }

    popup.show()
    true
}


        // Load cover art using ImageUrlHelper
        val coverId = t.coverArtId ?: t.albumId ?: t.id

        val coverUrl = ImageUrlHelper.getCoverArtUrl(coverId)

        holder.cover.load(coverUrl) {
            placeholder(R.drawable.ic_album)
            error(R.drawable.ic_album)
            crossfade(true)
            transformations(
                    coil.transform.RoundedCornersTransformation(
                            8f * holder.itemView.resources.displayMetrics.density
                    )
            )
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newList: List<Track>) {
        items = newList
        notifyDataSetChanged()
    }

    private suspend fun findWorkingStreamUrl(trackId: String): String? =
            withContext(Dispatchers.IO) {
                Log.d("TrackAdapter", "findWorkingStreamUrl called for track: $trackId")

                val servers = ServerManager.getOrderedServers()

                for (server in servers) {
                    try {
                        val testUrl = "$server/track/?id=$trackId"
                        Log.d("TrackAdapter", "Testing: $testUrl")

                        val conn =
                                java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.requestMethod = "GET" // ✅ CHANGED FROM HEAD TO GET

                        val responseCode = conn.responseCode
                        Log.d("TrackAdapter", "Server $server responded with: $responseCode")

                        if (responseCode == 200) {
                            conn.disconnect()
                            ServerManager.recordSuccess(server)
                            val streamUrl = "$server/stream/?id=$trackId"
                            Log.d("TrackAdapter", "SUCCESS! Using: $streamUrl")
                            return@withContext streamUrl
                        }
                        ServerManager.recordFailure(server)
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e("TrackAdapter", "Server $server failed: ${e.message}", e)
                        continue
                    }
                }

                Log.e("TrackAdapter", "All servers failed for track $trackId")
                return@withContext null
            }
    
    // ← ADD THIS HELPER FUNCTION AT THE END OF THE CLASS
    private fun showTrackInfoDialog(ctx: Context, info: com.example.moniq.model.TrackInfo) {
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        
        fun addInfoRow(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            
            row.addView(android.widget.TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF999999.toInt())
            })
            
            row.addView(android.widget.TextView(ctx).apply {
                text = value
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 4, 0, 0)
            })
            
            container.addView(row)
        }
        
        // Basic info
        addInfoRow("Title", info.title)
        addInfoRow("Artist", info.artists.joinToString(", ") { it.name })
        addInfoRow("Album", info.album?.title)
        
        // Duration
        val minutes = info.duration / 60
        val seconds = info.duration % 60
        addInfoRow("Duration", String.format("%d:%02d", minutes, seconds))
        
        // Audio quality
        val qualityText = buildString {
            append(info.audioQuality.replace("_", " "))
            if (info.audioModes.isNotEmpty()) {
                append(" • ${info.audioModes.joinToString(", ")}")
            }
        }
        addInfoRow("Audio Quality", qualityText)
        
        // Track number
        info.trackNumber?.let { 
            addInfoRow("Track Number", "$it${info.volumeNumber?.let { v -> " (Disc $v)" } ?: ""}")
        }
        
        // Musical info
        if (info.bpm != null || info.key != null) {
            val musicalInfo = buildString {
                info.bpm?.let { append("$it BPM") }
                info.key?.let { 
                    if (isNotEmpty()) append(" • ")
                    append(it)
                    info.keyScale?.let { scale -> 
                        append(" ${scale.lowercase().replaceFirstChar { c -> c.uppercase() }}") 
                    }
                }
            }
            addInfoRow("Musical Info", musicalInfo)
        }
        
        // Popularity
        info.popularity?.let { addInfoRow("Popularity", "$it%") }
        
        // Replay Gain
        info.replayGain?.let { 
            addInfoRow("Replay Gain", String.format("%.2f dB", it))
        }
        
        // ISRC
        addInfoRow("ISRC", info.isrc)
        
        // Copyright
        addInfoRow("Copyright", info.copyright)
        
        // Explicit
        if (info.explicit) {
            addInfoRow("Content", "Explicit")
        }
        
        // Create scrollable view
        val scrollView = androidx.core.widget.NestedScrollView(ctx).apply {
            addView(container)
        }
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Track Information")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }
}