package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moniq.adapters.TrackAdapter
import com.example.moniq.player.PlaylistManager
import com.example.moniq.player.AudioPlayer
import com.example.moniq.model.Track
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlaylistDetailActivity : ComponentActivity() {
    private lateinit var manager: PlaylistManager
    private var playlistId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)
        manager = PlaylistManager(applicationContext)
        playlistId = intent.getStringExtra("playlistId")
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.playlistTracksRecycler)
        val coverView = findViewById<android.widget.ImageView>(R.id.playlistCover)
        val nameInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.playlistNameInput)
        val descInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.playlistDescInput)
        val saveBtn = findViewById<android.widget.Button>(R.id.savePlaylistButton)
        val useFirstBtn = findViewById<android.widget.Button>(R.id.useFirstCoverButton)
        val clearCoverBtn = findViewById<android.widget.Button>(R.id.clearCoverButton)
        val chooseCoverBtn = findViewById<android.widget.Button>(R.id.chooseCoverButton)

        // Use an inline toggle button to reveal/hide cover-edit controls
        val toggleEditBtn = findViewById<android.widget.ImageButton>(R.id.toggleEditButton)
        toggleEditBtn?.setOnClickListener {
            toggleCoverEditControls()
        }

        var pickImageLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
        val adapter = TrackAdapter(emptyList(), onPlay = { t, pos -> AudioPlayer.playTrack(this, t.id, t.title, t.artist, t.coverArtId) }, onDownload = { t ->
            lifecycleScope.launch {
                try {
                    com.example.moniq.player.DownloadManager.downloadTrack(this@PlaylistDetailActivity, t.id, t.title, t.artist, null, t.coverArtId)
                } catch (_: Exception) {}
            }
        }, onAddToPlaylist = { /* no-op inside detail */ }, onRemoveFromPlaylist = { t ->
            playlistId?.let {
                manager.removeTrack(it, t.id)
                val a = recycler.adapter as? com.example.moniq.adapters.TrackAdapter
                if (a != null) load(a)
                android.widget.Toast.makeText(this, "Removed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, showRemoveOption = true)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                val p = playlistId?.let { manager.get(it) } ?: return@registerForActivityResult
                // process image: decode, center-crop to square, resize to max 1024, save to app files
                val saved = try { saveResizedCover(uri, p.id) } catch (e: Exception) { null }
                if (saved != null) {
                    p.coverArtId = saved
                    manager.update(p)
                    load(adapter)
                    android.widget.Toast.makeText(this, "Cover chosen", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Failed to process image", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        

        findViewById<android.widget.Button>(R.id.playAllButton).setOnClickListener {
            val p = playlistId?.let { manager.get(it) } ?: return@setOnClickListener
            AudioPlayer.setQueue(p.tracks.toList(), 0)
        }

        findViewById<android.widget.Button>(R.id.shuffleButton).setOnClickListener {
            val p = playlistId?.let { manager.get(it) } ?: return@setOnClickListener
            val shuffled = p.tracks.shuffled()
            AudioPlayer.setQueue(shuffled.toList(), 0)
        }

        findViewById<android.widget.Button>(R.id.addByIdButton).setOnClickListener {
            // Show a searchable picker dialog to find tracks (title/album/artist)
            val ctx = this
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(16,16,16,16)
            }
            val queryInput = com.google.android.material.textfield.TextInputEditText(ctx).apply {
                hint = "Search track, album or artist"
                setSingleLine(true)
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val resultsRecycler = androidx.recyclerview.widget.RecyclerView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 400)
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
            }
            val progress = android.widget.ProgressBar(ctx).apply {
                visibility = android.view.View.GONE
            }
            container.addView(queryInput)
            container.addView(progress)
            container.addView(resultsRecycler)

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Add track")
                .setView(container)
                .setNegativeButton("Close", null)
                .create()

            val searchRepo = com.example.moniq.search.SearchRepository()
            val resultsAdapter = com.example.moniq.adapters.TrackAdapter(emptyList(), onPlay = { t, pos ->
                // single-tap adds to playlist and keeps the dialog open
                playlistId?.let { manager.addTrack(it, t) }
                load(adapter)
                android.widget.Toast.makeText(ctx, "Added", android.widget.Toast.LENGTH_SHORT).show()
            }, onDownload = { t ->
                lifecycleScope.launch {
                    try {
                        com.example.moniq.player.DownloadManager.downloadTrack(ctx, t.id, t.title, t.artist, null, t.coverArtId)
                    } catch (_: Exception) {}
                }
            }, onAddToPlaylist = { t ->
                playlistId?.let { manager.addTrack(it, t) }
                load(adapter)
                android.widget.Toast.makeText(ctx, "Added", android.widget.Toast.LENGTH_SHORT).show()
            })

            resultsRecycler.adapter = resultsAdapter

            fun performSearch(q: String) {
                lifecycleScope.launch {
                    try {
                        progress.visibility = android.view.View.VISIBLE
                        val res = try { searchRepo.search(q) } catch (e: Exception) { null }
                        val songs = res?.songs ?: emptyList()
                        resultsAdapter.update(songs)
                    } catch (_: Exception) {
                    } finally {
                        progress.visibility = android.view.View.GONE
                    }
                }
            }

            queryInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    val q = queryInput.text?.toString() ?: ""
                    performSearch(q)
                    true
                } else false
            }

            // also perform search on typing after a short delay
            var searchJob: kotlinx.coroutines.Job? = null
            queryInput.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchJob?.cancel()
                    val q = s?.toString() ?: ""
                    searchJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(300)
                        if (q.isNotBlank()) performSearch(q)
                    }
                }
            })

            dialog.show()
        }

        load(adapter)

        // Ensure playback controls are visible and interactive (fix touch/inaccessibility bug)
        val playlistControls = findViewById<android.view.View>(R.id.playlistControls)
        playlistControls?.visibility = android.view.View.VISIBLE
        findViewById<android.widget.Button>(R.id.playAllButton)?.apply { alpha = 1.0f; isEnabled = true }
        findViewById<android.widget.Button>(R.id.shuffleButton)?.apply { alpha = 1.0f; isEnabled = true }
        findViewById<android.widget.Button>(R.id.addByIdButton)?.apply { alpha = 1.0f; isEnabled = true }

        saveBtn.setOnClickListener {
            val p = playlistId?.let { manager.get(it) } ?: return@setOnClickListener
            val newName = nameInput.text?.toString() ?: ""
            val newDesc = descInput.text?.toString()
            p.name = newName
            p.description = newDesc
            manager.update(p)
            findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title = p.name
            android.widget.Toast.makeText(this, "Saved", android.widget.Toast.LENGTH_SHORT).show()
        }

        chooseCoverBtn.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        useFirstBtn.setOnClickListener {
            val p = playlistId?.let { manager.get(it) } ?: return@setOnClickListener
            val first = p.tracks.firstOrNull()
            val host = com.example.moniq.SessionManager.host
            var chosen: String? = null
            if (first != null) {
                if (!first.coverArtId.isNullOrBlank()) chosen = first.coverArtId
                if (chosen != null && !chosen.startsWith("http") && host != null) {
                    chosen = android.net.Uri.parse(host)
                        .buildUpon()
                        .appendPath("rest")
                        .appendPath("getCoverArt.view")
                        .appendQueryParameter("id", chosen)
                        .appendQueryParameter("u", com.example.moniq.SessionManager.username ?: "")
                        .appendQueryParameter("p", com.example.moniq.SessionManager.password ?: "")
                        .build()
                        .toString()
                }
            }
            p.coverArtId = chosen
            manager.update(p)
            // refresh UI
            load(adapter)
            android.widget.Toast.makeText(this, "Cover set", android.widget.Toast.LENGTH_SHORT).show()
        }

        clearCoverBtn.setOnClickListener {
            val p = playlistId?.let { manager.get(it) } ?: return@setOnClickListener
            p.coverArtId = null
            manager.update(p)
            load(adapter)
            android.widget.Toast.makeText(this, "Cover cleared", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // helper: read uri, crop/resize, save to internal storage, return file:// uri string
    private fun saveResizedCover(uri: Uri, playlistId: String): String? {
        val maxSize = 1024
        val ins = contentResolver.openInputStream(uri) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ins, null, opts)
        ins.close()
        val w = opts.outWidth
        val h = opts.outHeight
        var inSample = 1
        val largest = if (w > h) w else h
        while (largest / inSample > maxSize) inSample *= 2
        val opts2 = BitmapFactory.Options().apply { inSampleSize = inSample }
        val ins2 = contentResolver.openInputStream(uri) ?: return null
        val bmp = BitmapFactory.decodeStream(ins2, null, opts2) ?: run { ins2.close(); return null }
        ins2.close()
        // center-crop to square
        val side = Math.min(bmp.width, bmp.height)
        val x = (bmp.width - side) / 2
        val y = (bmp.height - side) / 2
        val cropped = Bitmap.createBitmap(bmp, x, y, side, side)
        val finalBmp = Bitmap.createScaledBitmap(cropped, 512, 512, true)
        // save
        val dir = File(filesDir, "playlist_covers")
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, "cover_${playlistId}.jpg")
        val fos = FileOutputStream(outFile)
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
        fos.flush()
        fos.close()
        return Uri.fromFile(outFile).toString()
    }

    private fun toggleCoverEditControls() {
        val container = findViewById<android.view.View>(R.id.coverEditControls) ?: return
        if (container.visibility == android.view.View.VISIBLE) {
            container.animate().alpha(0.0f).setDuration(150).withEndAction {
                container.visibility = android.view.View.GONE
                setControlsEnabled(false)
            }
        } else {
            container.alpha = 0.0f
            container.visibility = android.view.View.VISIBLE
            container.animate().alpha(1.0f).setDuration(150).withEndAction {
                setControlsEnabled(true)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        // enable/disable the playlist edit controls (choose/use/clear/save)
        findViewById<android.view.View>(R.id.chooseCoverButton)?.isEnabled = enabled
        findViewById<android.view.View>(R.id.useFirstCoverButton)?.isEnabled = enabled
        findViewById<android.view.View>(R.id.clearCoverButton)?.isEnabled = enabled
        findViewById<android.view.View>(R.id.savePlaylistButton)?.isEnabled = enabled
    }

    private fun load(adapter: TrackAdapter) {
        val p = playlistId?.let { manager.get(it) }
        if (p == null) { finish(); return }
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.title = p.name
        val nameInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.playlistNameInput)
        val descInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.playlistDescInput)
        val coverView = findViewById<android.widget.ImageView>(R.id.playlistCover)
        nameInput.setText(p.name)
        descInput.setText(p.description ?: "")
        // try to load cover: prefer playlist.coverArtId, else try first track candidates
        val candidates = mutableListOf<String>()
        if (!p.coverArtId.isNullOrBlank()) candidates.add(p.coverArtId!!)
        val first = p.tracks.firstOrNull()
        val host = com.example.moniq.SessionManager.host
        if (first != null) {
            if (!first.coverArtId.isNullOrBlank()) {
                val c = first.coverArtId
                if (c!!.startsWith("http")) candidates.add(c)
            }
            if (!first.coverArtId.isNullOrBlank() && host != null && !first.coverArtId!!.startsWith("http")) {
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
                coverView.setImageResource(com.example.moniq.R.drawable.ic_album)
                return
            }
            val url = candidates[index]
            coverView.load(url) {
                placeholder(com.example.moniq.R.drawable.ic_album)
                crossfade(true)
                listener(onError = { _, _ -> tryLoad(index + 1) })
            }
        }

        if (candidates.isNotEmpty()) tryLoad(0) else coverView.setImageResource(com.example.moniq.R.drawable.ic_album)

        adapter.update(p.tracks.toList())
    }
}
