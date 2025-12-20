package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.moniq.adapters.PlaylistAdapter
import com.example.moniq.player.PlaylistManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlaylistsActivity : ComponentActivity() {
    private lateinit var manager: PlaylistManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)
        manager = PlaylistManager(applicationContext)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.playlists_menu)

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.playlistRecycler)
        val adapter = PlaylistAdapter(emptyList(), onOpen = { p ->
            val intent = android.content.Intent(this, PlaylistDetailActivity::class.java)
            intent.putExtra("playlistId", p.id)
            startActivity(intent)
        }, onDelete = { p ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete playlist")
                .setMessage("Are you sure you want to delete playlist '${p.name}'?")
                .setPositiveButton("Delete") { _, _ ->
                    manager.delete(p.id)
                    recycler.adapter?.let { (it as? PlaylistAdapter)?.update(manager.list()) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, onDownload = { p ->
            // show dialog with per-track progress bars and start downloads
            val dlgView = layoutInflater.inflate(R.layout.dialog_playlist_download, null)
            val listView = dlgView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.downloadList)
            val closeBtn = dlgView.findViewById<android.widget.Button>(R.id.closeDownloadButton)
            val dlAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                var items = p.tracks.toList()
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                    val v = layoutInflater.inflate(R.layout.item_download_progress, parent, false)
                    return object: androidx.recyclerview.widget.RecyclerView.ViewHolder(v){}
                }
                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    val t = items[position]
                    val v = holder.itemView
                    v.findViewById<android.widget.TextView>(R.id.downloadTitle).text = t.title
                    v.findViewById<android.widget.TextView>(R.id.downloadArtist).text = t.artist
                    val pb = v.findViewById<android.widget.ProgressBar>(R.id.downloadProgress)
                    pb.isIndeterminate = true
                    pb.progress = 0
                }
                override fun getItemCount(): Int = items.size
                fun setProgress(idx: Int, pct: Int) {
                    val vh = listView.findViewHolderForAdapterPosition(idx) ?: return
                    val pb = vh.itemView.findViewById<android.widget.ProgressBar>(R.id.downloadProgress)
                    if (pct >= 0) {
                        pb.isIndeterminate = false
                        pb.progress = pct
                    } else {
                        pb.isIndeterminate = true
                    }
                }
            }
            listView.layoutManager = LinearLayoutManager(this)
            listView.adapter = dlAdapter

            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle("Download playlist: ${p.name}")
                .setView(dlgView)
                .setNegativeButton("Close", null)
                .show()

            // start downloads sequentially and update progress
            lifecycleScope.launch {
                val dm = com.example.moniq.player.DownloadManager
                for ((idx, t) in p.tracks.withIndex()) {
                    try {
                        dlAdapter.setProgress(idx, -1)
                        val ok = try {
                            dm.downloadTrackWithProgress(applicationContext, t.id, t.title, t.artist, t.albumName, t.coverArtId) { pct ->
                                runOnUiThread { dlAdapter.setProgress(idx, pct) }
                            }
                        } catch (e: Exception) { false }
                        runOnUiThread {
                            if (ok) dlAdapter.setProgress(idx, 100) else dlAdapter.setProgress(idx, 0)
                        }
                    } catch (_: Exception) {}
                }
            }
        })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.addPlaylistFab)
            .setOnClickListener {
                val edit = android.widget.EditText(this)
                MaterialAlertDialogBuilder(this)
                    .setTitle("New playlist")
                    .setView(edit)
                    .setPositiveButton("Create") { _, _ ->
                        val name = edit.text.toString().trim()
                        if (name.isNotEmpty()) manager.create(name)
                        load(adapter)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

        // Register file picker for CSV import
        val getCsv = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            // ask which playlist to import into
            val playlists = manager.list()
            if (playlists.isEmpty()) {
                val edit = android.widget.EditText(this)
                MaterialAlertDialogBuilder(this)
                    .setTitle("No playlists found - create one")
                    .setView(edit)
                    .setPositiveButton("Create") { _, _ ->
                        val name = edit.text.toString().trim()
                        if (name.isNotEmpty()) {
                            val p = manager.create(name)
                            importCsvTo(uri, p.id, adapter)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@registerForActivityResult
            }
            val names = playlists.map { it.name }.toMutableList()
            names.add("Create new playlist...")
            val items = names.map { it as CharSequence }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Import into playlist")
                .setItems(items) { _, idx ->
                    if (idx == playlists.size) {
                        val edit = android.widget.EditText(this)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("New playlist")
                            .setView(edit)
                            .setPositiveButton("Create") { _, _ ->
                                val name = edit.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    val p = manager.create(name)
                                    importCsvTo(uri, p.id, adapter)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        val p = playlists[idx]
                        importCsvTo(uri, p.id, adapter)
                    }
                }
                .show()
        }

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_import_csv -> { getCsv.launch("text/*"); true }
                else -> false
            }
        }

        load(adapter)
    }

    private fun showImportResultsDialog(results: List<com.example.moniq.player.PlaylistImporter.ImportResult>, playlistId: String, adapter: PlaylistAdapter, manager: PlaylistManager) {
        val ctx = this
        val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val rv = androidx.recyclerview.widget.RecyclerView(this)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val listAdapter = object: androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            var items = results.toMutableList()
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_import_result, parent, false)
                return object: androidx.recyclerview.widget.RecyclerView.ViewHolder(v){}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val entry = items[position]
                val v = holder.itemView
                val titleView = v.findViewById<android.widget.TextView>(R.id.importOriginal)
                val statusView = v.findViewById<android.widget.TextView>(R.id.importStatus)
                val removeBtn = v.findViewById<android.widget.Button>(R.id.importRemove)
                val replaceBtn = v.findViewById<android.widget.Button>(R.id.importReplace)
                titleView.text = entry.originalQuery
                if (entry.matched != null) {
                    statusView.text = "Matched: ${entry.matched.title} — ${entry.matched.artist}"
                    removeBtn.isEnabled = true
                    replaceBtn.isEnabled = true
                } else {
                    statusView.text = "Not found"
                    removeBtn.isEnabled = false
                    replaceBtn.isEnabled = true
                }
                removeBtn.setOnClickListener {
                    try {
                        // remove matched track from playlist and update dialog + main UI
                        val matched = entry.matched
                        if (matched != null) {
                            manager.removeTrack(playlistId, matched.id)
                            // remove item from dialog list and notify
                            val idx = holder.adapterPosition
                            if (idx >= 0 && idx < items.size) {
                                items.removeAt(idx)
                                notifyItemRemoved(idx)
                            }
                            adapter.update(manager.list())
                        }
                    } catch (_: Exception) {}
                }
                replaceBtn.setOnClickListener {
                    // allow user to search/edit query and choose a replacement
                    (ctx as PlaylistsActivity).lifecycleScope.launch {
                        try {
                            var query = entry.originalQuery
                            var songs = withContext(Dispatchers.IO) { com.example.moniq.search.SearchRepository().search(query).songs }
                            selectionLoop@ while (true) {
                                if (songs.isEmpty()) {
                                    // prompt to edit query or cancel
                                    val edit = android.widget.EditText(ctx)
                                    edit.setText(query)
                                    val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                                        MaterialAlertDialogBuilder(ctx)
                                            .setTitle("No results for: $query")
                                            .setView(edit)
                                            .setPositiveButton("Search") { _, _ -> cont.resume(true) {} }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    }
                                    if (!ok) break@selectionLoop
                                    query = edit.text?.toString() ?: query
                                    songs = withContext(Dispatchers.IO) { com.example.moniq.search.SearchRepository().search(query).songs }
                                    continue@selectionLoop
                                }
                                val labels = songs.map { s -> if (!s.artist.isNullOrBlank()) "${s.title} — ${s.artist}" else s.title }.toTypedArray()
                                val choice = kotlinx.coroutines.suspendCancellableCoroutine<Int?> { cont ->
                                    val dlg = MaterialAlertDialogBuilder(ctx)
                                        .setTitle("Choose replacement for:\n${entry.originalQuery}")
                                    dlg.setItems(labels) { _, which -> cont.resume(which) {} }
                                    dlg.setPositiveButton("Edit query") { _, _ -> cont.resume(-1) {} }
                                    val d = dlg.show()
                                    cont.invokeOnCancellation { try { d.dismiss() } catch (_: Exception) {} }
                                }
                                if (choice == null) break@selectionLoop
                                if (choice == -1) {
                                    val edit = android.widget.EditText(ctx)
                                    edit.setText(query)
                                    val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                                        MaterialAlertDialogBuilder(ctx)
                                            .setTitle("Edit search")
                                            .setView(edit)
                                            .setPositiveButton("Search") { _, _ -> cont.resume(true) {} }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    }
                                    if (!ok) break@selectionLoop
                                    query = edit.text?.toString() ?: query
                                    songs = withContext(Dispatchers.IO) { com.example.moniq.search.SearchRepository().search(query).songs }
                                    continue@selectionLoop
                                }
                                val sel = songs[choice]
                                withContext(Dispatchers.Main) {
                                    try {
                                        if (entry.matched != null) manager.replaceTrack(playlistId, entry.matched.id, com.example.moniq.model.Track(sel.id, sel.title, sel.artist, sel.durationSec, albumId = sel.albumId, albumName = sel.albumName, coverArtId = sel.coverArtId))
                                        else manager.addTrack(playlistId, com.example.moniq.model.Track(sel.id, sel.title, sel.artist, sel.durationSec, albumId = sel.albumId, albumName = sel.albumName, coverArtId = sel.coverArtId))
                                        // update dialog entry to show matched replacement
                                        val idx = holder.adapterPosition
                                        if (idx >= 0 && idx < items.size) {
                                            items[idx] = items[idx].copy(matched = com.example.moniq.model.Track(sel.id, sel.title, sel.artist, sel.durationSec, albumId = sel.albumId, albumName = sel.albumName, coverArtId = sel.coverArtId))
                                            notifyItemChanged(idx)
                                        }
                                        adapter.update(manager.list())
                                    } catch (_: Exception) {}
                                }
                                break@selectionLoop
                            }
                        } catch (e: Exception) { runOnUiThread { android.widget.Toast.makeText(ctx, "Replacement failed", android.widget.Toast.LENGTH_SHORT).show() } }
                    }
                }
            }
            override fun getItemCount(): Int = items.size
        }
        rv.adapter = listAdapter

        // Ensure item layout exists; create minimal layout if missing
        try {
            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle("Import results")
                .setView(rv)
                .setPositiveButton("Close", null)
                .create()
            dlg.show()
        } catch (_: Exception) {}
    }

    private fun load(adapter: PlaylistAdapter) {
        adapter.update(manager.list())
    }

    private fun importCsvTo(uri: android.net.Uri, playlistId: String, adapter: PlaylistAdapter) {
        val importer = com.example.moniq.player.PlaylistImporter(this.applicationContext)

        // Ask user whether to run automatic import or manual interactive import
        MaterialAlertDialogBuilder(this)
            .setTitle("Import mode")
            .setMessage("Choose import mode for this CSV")
            .setPositiveButton("Automatic") { _, _ ->
                // existing automatic import
                val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
                val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
                progressBar.isIndeterminate = false
                progressBar.max = 100
                val tv = android.widget.TextView(this)
                tv.text = "Importing..."
                val container = android.widget.LinearLayout(this)
                container.orientation = android.widget.LinearLayout.VERTICAL
                container.setPadding(24, 24, 24, 24)
                container.addView(tv)
                container.addView(progressBar)

                val dlg = MaterialAlertDialogBuilder(this)
                    .setTitle("Importing CSV")
                    .setView(container)
                    .setCancelable(false)
                    .setNegativeButton("Cancel") { _, _ -> }
                    .show()

                importer.importing.observe(this) { importing: Boolean ->
                    if (!importing) {
                        dlg.dismiss()
                        adapter.update(manager.list())
                    }
                }
                importer.progress.observe(this) { p: Int ->
                    progressBar.progress = p
                    tv.text = "Importing... $p%"
                }
                importer.importInto(playlistId, uri) { imported, details ->
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Imported $imported tracks", android.widget.Toast.LENGTH_LONG).show()
                        // Show results review dialog with ability to remove/replace
                        showImportResultsDialog(details, playlistId, adapter, manager)
                    }
                }
            }
            .setNegativeButton("Manual") { _, _ ->
                // Manual interactive mode: build per-line search queries and prompt for each
                val list = importer.prepareSearchList(uri)
                if (list.isEmpty()) {
                    android.widget.Toast.makeText(this, "No items found in CSV", android.widget.Toast.LENGTH_LONG).show()
                    return@setNegativeButton
                }
                // iterate items sequentially
                lifecycleScope.launch {
                    val searchRepo = com.example.moniq.search.SearchRepository()
                    var imported = 0
                    for ((index, pair) in list.withIndex()) {
                        val q = pair.first
                        val dur = pair.second
                        // perform search
                        android.util.Log.i("PlaylistsActivity", "manual import line=${index+1} initial query=\"$q\"")
                        val res = try { withContext(kotlinx.coroutines.Dispatchers.IO) { searchRepo.search(q) } } catch (e: Exception) { android.util.Log.w("PlaylistsActivity", "search failed", e); null }
                        val songs = res?.songs ?: emptyList()
                        android.util.Log.i("PlaylistsActivity", "search returned ${songs.size} songs for query=\"$q\"")
                        if (songs.isEmpty()) {
                            // offer Skip
                            val skipped = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                                MaterialAlertDialogBuilder(this@PlaylistsActivity)
                                    .setTitle("No matches for:\n$q")
                                    .setMessage("Skip this track?")
                                    .setPositiveButton("Skip") { _, _ -> cont.resume(true) {} }
                                    .setNegativeButton("Cancel import") { _, _ -> cont.resume(false) {} }
                                    .show()
                            }
                            if (!skipped) break
                            else continue
                        }

                        val labels = songs.map { s -> if (!s.artist.isNullOrBlank()) "${s.title} — ${s.artist}" else s.title }.toTypedArray()

                            // Show selection dialog with ability to re-run search
                            var currentQuery = q
                            var currentSongs = songs
                            var chosenIndex: Int? = null
                            selectionLoop@ while (true) {
                                val labels2 = currentSongs.map { s -> if (!s.artist.isNullOrBlank()) "${s.title} — ${s.artist}" else s.title }.toTypedArray()
                                val choice = kotlinx.coroutines.suspendCancellableCoroutine<Int?> { cont ->
                                    val builder = MaterialAlertDialogBuilder(this@PlaylistsActivity)
                                        .setTitle("Line ${index + 1}/${list.size}")
                                        .setMessage("Searched for: \"$currentQuery\"")
                                    builder.setItems(labels2) { _, which -> cont.resume(which) {} }
                                    builder.setPositiveButton("Search") { _, _ -> cont.resume(-1) {} }
                                    builder.setNeutralButton("Skip") { _, _ -> cont.resume(null) {} }
                                    builder.setNegativeButton("Cancel import") { _, _ -> cont.resume(-2) {} }
                                    val dlg = builder.show()
                                    cont.invokeOnCancellation { try { dlg.dismiss() } catch (_: Exception) {} }
                                }
                                if (choice == -2) { break@selectionLoop }
                                if (choice == null) { break@selectionLoop }
                                if (choice == -1) {
                                    // prompt for new query and re-search
                                    val input = android.widget.EditText(this@PlaylistsActivity)
                                    input.setText(currentQuery)
                                    val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                                        MaterialAlertDialogBuilder(this@PlaylistsActivity)
                                            .setTitle("Edit search for line ${index + 1}")
                                            .setView(input)
                                            .setPositiveButton("Search") { _, _ -> cont.resume(true) {} }
                                            .setNegativeButton("Cancel") { _, _ -> cont.resume(false) {} }
                                            .show()
                                    }
                                    if (!ok) {
                                        // keep existing results
                                        continue@selectionLoop
                                    }
                                    currentQuery = input.text?.toString() ?: currentQuery
                                    // rerun search
                                    currentSongs = try { withContext(kotlinx.coroutines.Dispatchers.IO) { searchRepo.search(currentQuery)?.songs ?: emptyList() } } catch (_: Exception) { emptyList() }
                                    if (currentSongs.isEmpty()) {
                                        // notify nothing found and go back to selection loop
                                        android.widget.Toast.makeText(this@PlaylistsActivity, "No results for: $currentQuery", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    continue@selectionLoop
                                }
                                // user selected index
                                chosenIndex = choice
                                break@selectionLoop
                            }
                            if (chosenIndex == null) continue
                            val sel = currentSongs[chosenIndex]
                        try {
                            manager.addTrack(playlistId, com.example.moniq.model.Track(sel.id, sel.title, sel.artist, sel.durationSec, albumId = sel.albumId, albumName = sel.albumName, coverArtId = sel.coverArtId))
                            imported++
                            adapter.update(manager.list())
                        } catch (_: Exception) {}
                    }
                    runOnUiThread { android.widget.Toast.makeText(this@PlaylistsActivity, "Imported $imported tracks (manual)", android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }
}
