package com.example.moniq

import androidx.media3.common.Player
import android.widget.Button
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.util.Log
import android.widget.Toast
import com.example.moniq.player.DownloadManager
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.moniq.adapters.ArtistAdapter
import com.example.moniq.adapters.AlbumAdapter
import com.example.moniq.adapters.TrackAdapter
import android.widget.ImageView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.example.moniq.search.SearchViewModel
import com.example.moniq.player.AudioPlayer
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton

class SearchActivity : ComponentActivity() {
    private lateinit var vm: SearchViewModel
    
    // View references will be local to onCreate and updateUIState will be a local lambda

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        vm = ViewModelProvider(this).get(SearchViewModel::class.java)

        val queryInput = findViewById<TextInputEditText?>(R.id.searchQuery)
        val searchButton = findViewById<MaterialButton?>(R.id.searchButton)
        val progress = findViewById<ProgressBar?>(R.id.searchProgress)
        
        // Initialize local view references (use runtime lookup to avoid R.id resolution issues)
        val emptyState = findViewById<LinearLayout?>(resources.getIdentifier("emptyState", "id", packageName))
        val artistsSection = findViewById<LinearLayout?>(resources.getIdentifier("artistsSection", "id", packageName))
        val albumsSection = findViewById<LinearLayout?>(resources.getIdentifier("albumsSection", "id", packageName))
        val songsSection = findViewById<LinearLayout?>(resources.getIdentifier("songsSection", "id", packageName))
        val errorCard = findViewById<MaterialCardView?>(resources.getIdentifier("errorCard", "id", packageName))
        val errorText = findViewById<TextView?>(resources.getIdentifier("errorText", "id", packageName))
        val noResultsState = findViewById<LinearLayout?>(resources.getIdentifier("noResultsState", "id", packageName))

        // Containers
        val artistsRecycler = findViewById<RecyclerView?>(resources.getIdentifier("artistsRecycler", "id", packageName))
        val albumsRecycler = findViewById<RecyclerView?>(resources.getIdentifier("albumsRecycler", "id", packageName))
        val songsRecycler = findViewById<RecyclerView?>(resources.getIdentifier("songsRecycler", "id", packageName))
        val filterGroup = findViewById<ChipGroup?>(resources.getIdentifier("searchFilterGroup", "id", packageName))
        val chipAll = findViewById<Chip?>(resources.getIdentifier("chipAll", "id", packageName))
        val chipSongs = findViewById<Chip?>(resources.getIdentifier("chipSongs", "id", packageName))
        val chipAlbums = findViewById<Chip?>(resources.getIdentifier("chipAlbums", "id", packageName))
        val chipArtists = findViewById<Chip?>(resources.getIdentifier("chipArtists", "id", packageName))

        // keep current search song results so play clicks can set the queue
        var currentSearchSongs: List<com.example.moniq.model.Track> = emptyList()

        // minimal adapters
        val artistAdapter = ArtistAdapter(emptyList()) { artist ->
            val intent = Intent(this, ArtistActivity::class.java)
            intent.putExtra("artistId", artist.id)
            intent.putExtra("artistName", artist.name)
            if (!artist.coverArtId.isNullOrEmpty()) intent.putExtra("artistCoverId", artist.coverArtId)
            startActivity(intent)
        }
        val albumAdapter = AlbumAdapter(emptyList(), { album ->
            val intent = Intent(this, AlbumActivity::class.java)
            intent.putExtra("albumId", album.id)
            intent.putExtra("albumTitle", album.name)
            intent.putExtra("albumArtist", album.artist)
            startActivity(intent)
        })
        val trackAdapter = TrackAdapter(
    emptyList(),
    onPlay = { t, pos ->
        // coverArtId is already a full proxy URL from search
        val albumArtUrl = t.coverArtId

        AudioPlayer.initialize(this)

        if (currentSearchSongs.isNotEmpty()) {
            AudioPlayer.setQueue(currentSearchSongs, pos)
        } else {
            AudioPlayer.playTrack(
                this,
                t.id,
                t.title,
                t.artist,
                albumArtUrl,
                t.albumId,
                t.albumName
            )
        }
    },
    onDownload = { t ->
    lifecycleScope.launch {
        val albumArtUrl = t.coverArtId
        Log.d("SearchActivity", "Download clicked for track: ${t.id}, streamUrl: ${t.streamUrl}")
        
        val ok = DownloadManager.downloadTrack(
            this@SearchActivity,
            t.id,
            t.title,
            t.artist,
            t.albumName,  // ✅ Pass album name
            albumArtUrl,
            streamUrl = t.streamUrl  // ✅ Pass streamUrl!
        )
        
        Log.d("SearchActivity", "Download result: $ok")
        Toast.makeText(
            this@SearchActivity,
            if (ok) "Downloaded" else "Download failed",
            Toast.LENGTH_LONG
        ).show()
    }
}
)


        artistsRecycler?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        artistsRecycler?.adapter = artistAdapter
        albumsRecycler?.layoutManager = LinearLayoutManager(this)
        albumsRecycler?.adapter = albumAdapter
        songsRecycler?.layoutManager = LinearLayoutManager(this)
        songsRecycler?.adapter = trackAdapter

        // Local UI state updater (queries views directly to avoid scoping issues)
        var currentFilter = "ALL"
        val activeQualityFilters = mutableSetOf<String>()
    val updateUIState: () -> Unit = {
    val hasArtists = vm.artists.value?.isNotEmpty() == true
    val hasAlbums = vm.albums.value?.isNotEmpty() == true
    val hasSongs = vm.songs.value?.isNotEmpty() == true
    val hasError = !vm.error.value.isNullOrEmpty()
    val isLoading = vm.loading.value == true
    

// Handle main states (no animations for better performance)
    when {
        isLoading -> {
            emptyState?.visibility = View.GONE
            noResultsState?.visibility = View.GONE
        }
        hasError -> {
            emptyState?.visibility = View.GONE
            noResultsState?.visibility = View.GONE
        }
        !hasArtists && !hasAlbums && !hasSongs -> {
            if (vm.responseCode.value != null) {
                noResultsState?.visibility = View.VISIBLE
                emptyState?.visibility = View.GONE
            } else {
                emptyState?.visibility = View.VISIBLE
                noResultsState?.visibility = View.GONE
            }
        }
        else -> {
            emptyState?.visibility = View.GONE
            noResultsState?.visibility = View.GONE
        }
    }
    
    // Show/hide filter chips
    val hasSearchRun = vm.responseCode.value != null || hasArtists || hasAlbums || hasSongs
    filterGroup?.visibility = if (hasSearchRun && (hasArtists || hasAlbums || hasSongs)) View.VISIBLE else View.GONE
    val audioFormatGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.audioFormatChipGroup)
    audioFormatGroup?.visibility = if (hasSearchRun && (hasArtists || hasAlbums || hasSongs)) View.VISIBLE else View.GONE
    
    // Ensure default selection
    if (filterGroup?.visibility == View.VISIBLE && (filterGroup.checkedChipId == View.NO_ID || filterGroup.checkedChipId == -1)) {
        chipAll?.isChecked = true
    }
    
    // Apply current filter (single pass, no animations)
    when (currentFilter) {
        "ALL" -> {
            songsSection?.visibility = if (hasSongs) View.VISIBLE else View.GONE
            albumsSection?.visibility = if (hasAlbums) View.VISIBLE else View.GONE
            artistsSection?.visibility = if (hasArtists) View.VISIBLE else View.GONE
        }
        "SONGS" -> {
            songsSection?.visibility = if (hasSongs) View.VISIBLE else View.GONE
            albumsSection?.visibility = View.GONE
            artistsSection?.visibility = View.GONE
        }
        "ALBUMS" -> {
            songsSection?.visibility = View.GONE
            albumsSection?.visibility = if (hasAlbums) View.VISIBLE else View.GONE
            artistsSection?.visibility = View.GONE
        }
        "ARTISTS" -> {
            songsSection?.visibility = View.GONE
            albumsSection?.visibility = View.GONE
            artistsSection?.visibility = if (hasArtists) View.VISIBLE else View.GONE
        }
    }
}

        // Show empty state initially
        emptyState?.visibility = View.VISIBLE

        // FIXED QUALITY FILTER CHIPS WITH DEBUG LOGS
        val audioFormatGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.audioFormatChipGroup)!!
        val chipLossless = audioFormatGroup.findViewById<Chip>(R.id.chipLossless)!!
        val chipHiRes = audioFormatGroup.findViewById<Chip>(R.id.chipHiRes)!!
        val chipAtmos = audioFormatGroup.findViewById<Chip>(R.id.chipAtmos)!!

        listOf(
            chipLossless to "LOSSLESS",
            chipHiRes to "HIRES",
            chipAtmos to "ATMOS"
        ).forEach { (chip, filterType) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                Log.d("CHIP_DEBUG", "Checked changed ${chip.text}, checked=$isChecked, activeFilters=$activeQualityFilters")
                if (isChecked) {
                    activeQualityFilters.add(filterType)
                } else {
                    activeQualityFilters.remove(filterType)
                }
                Log.d("CHIP_DEBUG", "After change: activeFilters=$activeQualityFilters")
                // Manually trigger the song observer to re-filter
                vm.songs.value?.let { vm.songs.postValue(it) }
            }
        }

        // Search action (use local function to allow return)
        fun performSearch() {
    activeQualityFilters.clear()
    chipLossless.isChecked = false
    chipHiRes.isChecked = false
    chipAtmos.isChecked = false
    
    val q = queryInput?.text?.toString() ?: ""
    Log.i("SearchActivity", "search button clicked, query='$q'")
    
    // ✅ REMOVED: No longer checking SessionManager.host since we use proxy servers
    // if (SessionManager.host.isNullOrEmpty()) {
    //     vm.error.postValue("No server configured. Please set server in settings.")
    //     return@performSearch
    // }
    
    try {
        vm.search(q)
    } catch (t: Throwable) {
        vm.error.postValue(t.message ?: "Search failed")
    }

    // Hide keyboard
    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.hideSoftInputFromWindow(queryInput?.windowToken, 0)
}

        searchButton?.setOnClickListener { performSearch() }

        queryInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        // Filter control wiring (chip views declared earlier)

        filterGroup?.setOnCheckedChangeListener { _, checkedId ->
            val prev = currentFilter
            currentFilter = when (checkedId) {
                chipAll?.id -> "ALL"
                chipSongs?.id -> "SONGS"
                chipAlbums?.id -> "ALBUMS"
                chipArtists?.id -> "ARTISTS"
                else -> "ALL"
            }
            Log.d("SearchActivity", "filter changed: prev=$prev new=$currentFilter checkedId=$checkedId")
            updateUIState()
        }

        // Ensure chip clicks always apply the filter (some devices/layouts may not toggle check reliably)
        chipAll?.setOnClickListener {
            it as Chip
            it.isChecked = true
            currentFilter = "ALL"
            Log.d("SearchActivity", "chipAll clicked")
            updateUIState()
        }
        chipSongs?.setOnClickListener {
            it as Chip
            it.isChecked = true
            currentFilter = "SONGS"
            Log.d("SearchActivity", "chipSongs clicked")
            updateUIState()
        }
        chipAlbums?.setOnClickListener {
            it as Chip
            it.isChecked = true
            currentFilter = "ALBUMS"
            Log.d("SearchActivity", "chipAlbums clicked")
            updateUIState()
        }
        chipArtists?.setOnClickListener {
            it as Chip
            it.isChecked = true
            currentFilter = "ARTISTS"
            Log.d("SearchActivity", "chipArtists clicked")
            updateUIState()
        }

        // Loading state
        vm.loading.observe(this) { loading ->
            progress.visibility = if (loading == true) View.VISIBLE else View.GONE
            
            if (loading == true) {
                // Hide all states when loading
                emptyState?.visibility = View.GONE
                errorCard?.visibility = View.GONE
                noResultsState?.visibility = View.GONE
            }
        }

        // Miniplayer wiring
        AudioPlayer.initialize(this)
        val miniTitle = findViewById<TextView?>(R.id.miniplayerTitle)
        val miniArtist = findViewById<TextView?>(R.id.miniplayerArtist)
        val miniArt = findViewById<android.view.View?>(R.id.miniplayerArt) as? android.widget.ImageView
        val miniPlay = findViewById<ImageButton?>(R.id.miniplayerPlayPause)

        AudioPlayer.currentTitle.observe(this) { t -> miniTitle?.text = t ?: "" }
        AudioPlayer.currentArtist.observe(this) { a -> miniArtist?.text = a ?: "" }
        AudioPlayer.currentAlbumArt.observe(this) { artUrl ->
    val loadUrl = com.example.moniq.util.ImageUrlHelper.getCoverArtUrl(artUrl)
    if (loadUrl.isNullOrBlank()) {
        miniArt?.setImageResource(R.drawable.ic_album)
    } else {
        miniArt?.load(loadUrl) { 
            placeholder(R.drawable.ic_album)
            error(R.drawable.ic_album)
            crossfade(true) 
        }
    }
}
        AudioPlayer.isPlaying.observe(this) { playing ->
    val res = if (playing == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
    miniPlay?.setImageResource(res)
}

val miniLoading = findViewById<ProgressBar>(R.id.miniplayerLoading)
AudioPlayer.playbackState.observe(this) { state ->
    val isBuffering = state == Player.STATE_BUFFERING
    miniLoading?.visibility = if (isBuffering) View.VISIBLE else View.GONE
}

miniPlay?.setOnClickListener { AudioPlayer.togglePlayPause() }

        val miniRoot = findViewById<View?>(R.id.miniplayerRoot)
        miniRoot?.setOnClickListener {
            val intent = Intent(this, FullscreenPlayerActivity::class.java)
            startActivity(intent)
        }

        // Artists
        vm.artists.observe(this) { list ->
            artistsSection?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            artistAdapter.update(list)
            
            updateUIState()
        }

        // Albums
        vm.albums.observe(this) { list ->
            albumsSection?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            albumAdapter.update(list)
            Log.d("SearchActivity", "Albums observed: count=${list.size} names=[${list.joinToString { it.name }}]")
            updateUIState()
        }
        
       // FIXED SONG FILTERING WITH MULTI-SELECT QUALITY
vm.songs.observe(this) { list ->
    Log.d("FILTER_DEBUG", "Original song count: ${list.size}")
    Log.d("FILTER_DEBUG", "Active quality filters: $activeQualityFilters")
    
    val albumsForMatch = vm.albums.value ?: emptyList()
    
    // 🔥 CORRECTED FILTERING LOGIC
    val filtered = if (activeQualityFilters.isEmpty()) {
        list
    } else {
        list.filter { track ->
            var match = false
            
            // ATMOS check - look for "DOLBY_ATMOS" in audioModes OR mediaMetadata.tags
            if (activeQualityFilters.contains("ATMOS")) {
                if (track.audioModes?.any { it.contains("DOLBY_ATMOS", ignoreCase = true) } == true) match = true
            }
            
            // HIRES check - look for "HIRES_LOSSLESS" (NOT "HIRES")
            if (activeQualityFilters.contains("HIRES")) {
                if (track.audioModes?.any { it.contains("HIRES_LOSSLESS", ignoreCase = true) } == true) match = true
            }
            
            // LOSSLESS check - look for "LOSSLESS" in audioQuality OR tags
            if (activeQualityFilters.contains("LOSSLESS")) {
                if (track.audioQuality?.contains("LOSSLESS", ignoreCase = true) == true) match = true
                if (track.audioModes?.any { it.contains("LOSSLESS", ignoreCase = true) } == true) match = true
            }
            
            match
        }
    }
    
    Log.d("FILTER_DEBUG", "Filtered song count: ${filtered.size}")
    
    // Match albums for missing IDs
    val updated = filtered.map { t ->
        if (t.albumId.isNullOrBlank() && !t.albumName.isNullOrBlank()) {
            val match = albumsForMatch.firstOrNull { a -> 
                a.name.equals(t.albumName, ignoreCase = true) 
            }
            if (match != null) {
                t.copy(albumId = match.id, coverArtId = t.coverArtId ?: match.coverArtId)
            } else t
        } else t
    }
    
    songsSection?.visibility = if (updated.isEmpty()) View.GONE else View.VISIBLE
    currentSearchSongs = updated
    trackAdapter.update(updated)
    updateUIState()
}
        

        // Error handling
        vm.error.observe(this) { err ->
    if (!err.isNullOrEmpty()) {
        errorCard?.visibility = View.VISIBLE
        
        // Show the full error message for debugging
        // You can make this user-friendly later if needed
        if (errorText != null) errorText.text = err
        
        emptyState?.visibility = View.GONE
        noResultsState?.visibility = View.GONE
    } else {
        errorCard?.visibility = View.GONE
    }

    updateUIState()
}

        // Handle intent-driven initial query/filter
        val initialQuery = intent.getStringExtra("query")
        val initialFilter = intent.getStringExtra("filter")
        if (!initialQuery.isNullOrBlank()) {
            queryInput?.setText(initialQuery)
            // set filter chip if provided
            when (initialFilter) {
                "ARTISTS" -> chipArtists?.isChecked = true
                "ALBUMS" -> chipAlbums?.isChecked = true
                "SONGS" -> chipSongs?.isChecked = true
                else -> chipAll?.isChecked = true
            }
            // perform search after setting text
            // invoke the local function by calling the button handler
            searchButton?.performClick()
        }
    }

    // updateUIState moved inside onCreate to access local view references
}