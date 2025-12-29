package com.example.moniq.search

import android.util.Log
import com.example.moniq.model.Album
import com.example.moniq.model.Artist
import com.example.moniq.model.Track
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.delay
import com.example.moniq.util.ServerManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers


data class SearchResults(
    val songs: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val code: Int = 0,
    val rawBody: String = ""
)

class SearchRepository {
    

    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)  // Increased from 10
    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)     // Increased from 15
    .retryOnConnectionFailure(true)  // ADD THIS
        .build()

    suspend fun search(query: String): SearchResults {
        if (query.isBlank()) {
            return SearchResults(
                songs = emptyList(),
                albums = emptyList(),
                artists = emptyList(),
                code = 0,
                rawBody = ""
            )
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // Get ordered servers (best ones first)
        val orderedServers = ServerManager.getOrderedServers()
        
        // Try each server one by one until one succeeds
        for ((index, site) in orderedServers.withIndex()) {
            try {
                val result = searchAllTypes(site, encodedQuery, query)
                if (result != null) {
                    ServerManager.recordSuccess(site)  // Record success!
                    Log.i("SearchRepository", "Successfully searched $site")
                    return result
                }
            } catch (e: Exception) {
                ServerManager.recordFailure(site)  // Record failure!
                Log.w("SearchRepository", "Failed to search $site: ${e.message}")
            }

            // Wait only 500ms before trying next site
            if (index < orderedServers.lastIndex) {
                Log.i("SearchRepository", "Waiting 500ms before next search site...")
                delay(500)
            }
        }

        return SearchResults(
            songs = emptyList(),
            albums = emptyList(),
            artists = emptyList(),
            code = -1,
            rawBody = "All search sites failed"
        )
    }
    
    private suspend fun searchAllTypes(site: String, encodedQuery: String, rawQuery: String): SearchResults? {
        val songs = mutableListOf<Track>()
        val albums = mutableMapOf<String, Album>()
        val artists = mutableMapOf<String, Artist>()
        
        // Search tracks: /search/?s=query (MUST succeed)
        try {
            val trackUrl = "$site/search/?s=$encodedQuery"
            val trackBody = fetchUrl(trackUrl) ?: return null
            parseTrackSearch(trackBody, site, songs)
        } catch (e: Exception) {
            Log.w("SearchRepository", "Track search failed on $site: ${e.message}")
            return null
        }
        
        // Search albums and artists sequentially (simpler, avoids coroutine scope issues)
        try {
            val albumUrl = "$site/search/?al=$encodedQuery"
            val albumBody = fetchUrl(albumUrl)
            if (albumBody != null) {
                parseAlbumSearch(albumBody, albums)
            }
        } catch (e: Exception) {
            Log.w("SearchRepository", "Album search failed on $site: ${e.message}")
        }
        
        try {
            val artistUrl = "$site/search/?a=$encodedQuery"
            val artistBody = fetchUrl(artistUrl)
            if (artistBody != null) {
                parseArtistSearch(artistBody, artists)
            }
        } catch (e: Exception) {
            Log.w("SearchRepository", "Artist search failed on $site: ${e.message}")
        }
        
        return SearchResults(
            songs = songs,
            albums = albums.values.toList(),
            artists = artists.values.toList(),
            code = 200,
            rawBody = "Success from $site"
        )
    }
    
    private suspend fun fetchUrl(url: String): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .addHeader("Accept", "*/*")
        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
        .addHeader("x-client", "BiniLossless/v3.3")
        // REMOVED Accept-Encoding header - let OkHttp handle it automatically
        .build()

    client.newCall(request).execute().use { response ->
        val body = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw HttpException(
                code = response.code,
                message = response.message,
                body = body.take(500), // Limit body in error
                url = url
            )
        }

        if (body.isEmpty()) {
            throw HttpException(
                code = response.code,
                message = "Empty response body",
                body = "",
                url = url
            )
        }

        // Validate that response looks like JSON
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            Log.e("SearchRepository", "Invalid JSON response from $url. Body starts with: ${body.take(100)}")
            throw HttpException(
                code = response.code,
                message = "Response is not valid JSON",
                body = body.take(500),
                url = url
            )
        }

        body
    }
}
    
    private fun parseTrackSearch(body: String, site: String, songs: MutableList<Track>) {
    try {
        // Validate body before parsing
        if (body.isBlank() || !body.trim().startsWith("{")) {
            Log.e("SearchRepository", "Invalid track search response. Body: ${body.take(200)}")
            throw IllegalArgumentException("Response is not valid JSON")
        }
        
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return
        val items = data.optJSONArray("items") ?: return

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue

            val trackId = item.opt("id")?.toString() ?: ""
            val trackTitle = item.optString("title", "Unknown")
            val duration = item.optInt("duration", 0)

            // Parse artist
            val artistObj = item.optJSONObject("artist")
            val artistName = artistObj?.optString("name", "Unknown Artist") ?: "Unknown Artist"

            // Parse album
            val albumObj = item.optJSONObject("album")
            val albumId = albumObj?.opt("id")?.toString() ?: ""
            val albumTitle = albumObj?.optString("title", "") ?: ""
            val albumCover = albumObj?.optString("cover", null)

            // 🔥 CRITICAL FIX: Parse quality tags from BOTH sources
            val audioQuality = item.optString("audioQuality", null)
            
            val audioModes = mutableListOf<String>()
            // 1. Top-level audioModes field
            item.optJSONArray("audioModes")?.let { arr ->
                for (j in 0 until arr.length()) {
                    audioModes.add(arr.optString(j))
                }
            }
            // 2. mediaMetadata.tags field (where HIRES_LOSSLESS lives)
            item.optJSONObject("mediaMetadata")?.optJSONArray("tags")?.let { tagsArr ->
                for (j in 0 until tagsArr.length()) {
                    val tag = tagsArr.optString(j)
                    // Only keep quality tags we care about
                    if (tag == "HIRES_LOSSLESS" || tag == "DOLBY_ATMOS") {
                        audioModes.add(tag)
                    }
                }
            }

            val track = Track(
                id = trackId,
                title = trackTitle,
                artist = artistName,
                duration = duration,
                albumId = albumId,
                albumName = albumTitle,
                coverArtId = albumCover,
                streamUrl = "$site/stream/?id=$trackId",
                audioQuality = audioQuality,
                audioModes = if (audioModes.isNotEmpty()) audioModes else null
            )
            songs.add(track)
        }
    } catch (e: Exception) {
        Log.e("SearchRepository", "Error parsing track search", e)
        throw e // Re-throw to trigger failure in searchAllTypes
    }
}
    
    private fun parseAlbumSearch(body: String, albums: MutableMap<String, Album>) {
        try {
            if (body.isBlank() || !body.trim().startsWith("{")) {
            Log.e("SearchRepository", "Invalid album search response. Body: ${body.take(200)}")
            throw IllegalArgumentException("Response is not valid JSON")
        }
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return
            val albumsData = data.optJSONObject("albums") ?: return
            val items = albumsData.optJSONArray("items") ?: return
            
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                
                // FIX: Album ID is a number
                val albumId = item.opt("id")?.toString() ?: ""
                val title = item.optString("title", "Unknown Album")
                val cover = item.optString("cover", null)
                val releaseDate = item.optString("releaseDate", null)
                
                // Parse artist
                val artistsArray = item.optJSONArray("artists")
                val artistName = if (artistsArray != null && artistsArray.length() > 0) {
                    artistsArray.optJSONObject(0)?.optString("name", "Unknown Artist") ?: "Unknown Artist"
                } else "Unknown Artist"
                
                // Extract year from release date
                val year = releaseDate?.take(4)?.toIntOrNull()
                
                val albumKey = "${title.lowercase()}:${artistName.lowercase()}"
                if (!albums.containsKey(albumKey)) {
                    albums[albumKey] = Album(
                        id = albumId,
                        name = title,
                        artist = artistName,
                        year = year,
                        coverArtId = cover
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SearchRepository", "Error parsing album search", e)
        }
    }
    
    private fun parseArtistSearch(body: String, artists: MutableMap<String, Artist>) {
        try {
            if (body.isBlank() || !body.trim().startsWith("{")) {
            Log.e("SearchRepository", "Invalid artist search response. Body: ${body.take(200)}")
            throw IllegalArgumentException("Response is not valid JSON")
        }
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return
            val artistsData = data.optJSONObject("artists") ?: return
            val items = artistsData.optJSONArray("items") ?: return
            
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                
                // FIX: Artist ID is a number
                val artistId = item.opt("id")?.toString() ?: ""
                val artistName = item.optString("name", "Unknown Artist")
                val picture = item.optString("picture", null)
                
                val artistKey = artistName.lowercase()
                if (!artists.containsKey(artistKey)) {
                    artists[artistKey] = Artist(
                        id = artistId,
                        name = artistName,
                        coverArtId = picture
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SearchRepository", "Error parsing artist search", e)
        }
    }
}

class HttpException(
    val code: Int,
    override val message: String,
    val body: String,
    val url: String
) : Exception(
    "HTTP $code $message\nURL: $url\nBody:\n$body"
)