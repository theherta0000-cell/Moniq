package com.example.moniq.music

import android.util.Log
import com.example.moniq.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.example.moniq.util.ServerManager  

class MusicRepository {

    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    suspend fun getAlbumTracks(albumId: String): List<Track> = withContext(Dispatchers.IO) {
        val orderedServers = ServerManager.getOrderedServers()
        // Try each server until one succeeds
        for (server in orderedServers) {
            try {
                val url = "$server/album/?id=$albumId"
                Log.d("MusicRepository", "Fetching album from: $url")
                
                val request = Request.Builder()
    .url(url)
    .addHeader("Accept", "application/json")
    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    .addHeader("x-client", "BiniLossless/v3.3")  // Add the required header
    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    ServerManager.recordFailure(server)  // ADD THIS
                    continue
                }
                
                val body = response.body?.string() ?: continue
                val tracks = parseAlbumTracks(body, server)
                
                if (tracks.isNotEmpty()) {
                    ServerManager.recordSuccess(server)  // ADD THIS
                    Log.i("MusicRepository", "Successfully fetched ${tracks.size} tracks from $server")
                    return@withContext tracks
                }
            } catch (e: Exception) {
                ServerManager.recordFailure(server)  // ADD THIS
                Log.w("MusicRepository", "Failed to fetch album from $server: ${e.message}")
            }
        }
        
        emptyList()
    }
    
    private fun parseAlbumTracks(body: String, server: String): List<Track> {
    val tracks = mutableListOf<Track>()
    
    try {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return tracks
        val items = data.optJSONArray("items") ?: return tracks
        
        // Get album info from first track
        var albumCover: String? = null
        var albumName: String? = null
        
        for (i in 0 until items.length()) {
            val itemWrapper = items.optJSONObject(i) ?: continue
            val item = itemWrapper.optJSONObject("item") ?: continue
            
            val trackId = item.optString("id", "")
            val title = item.optString("title", "Unknown")
            val duration = item.optInt("duration", 0)
            
            // Validate duration
            if (duration <= 0) {
                Log.w("MusicRepository", "Track '$title' has invalid duration: $duration seconds")
            }
            
            // Parse artist
            val artistObj = item.optJSONObject("artist")
            val artistName = artistObj?.optString("name", "Unknown Artist") ?: "Unknown Artist"
            
            // Parse album
            val albumObj = item.optJSONObject("album")
            if (albumObj != null) {
                if (albumName == null) {
                    albumName = albumObj.optString("title", "Unknown Album")
                }
                if (albumCover == null) {
                    albumCover = albumObj.optString("cover", null)
                }
            }
            
            val albumId = albumObj?.optString("id", "") ?: ""
            
            // Parse audio quality info
            val audioQuality = item.optString("audioQuality", null)
            val audioModesArray = item.optJSONArray("audioModes")
            val audioModes = if (audioModesArray != null) {
                (0 until audioModesArray.length()).mapNotNull { 
                    audioModesArray.optString(it)?.takeIf { it.isNotBlank() }
                }
            } else null
            
            // ✅ NEW: Parse mediaMetadata.tags
            val mediaMetadataTags = try {
                val mediaMetadata = item.optJSONObject("mediaMetadata")
                val tagsArray = mediaMetadata?.optJSONArray("tags")
                if (tagsArray != null) {
                    (0 until tagsArray.length()).mapNotNull { 
                        tagsArray.optString(it)?.takeIf { it.isNotBlank() }
                    }
                } else null
            } catch (e: Exception) {
                Log.w("MusicRepository", "Failed to parse mediaMetadata.tags for '$title'")
                null
            }
            
            // Store raw UUID - ImageUrlHelper will convert to Tidal URL
            val track = Track(
                id = trackId,
                title = title,
                artist = artistName,
                duration = duration,
                albumId = albumId,
                albumName = albumName,
                coverArtId = albumCover,
                streamUrl = "$server/stream/?id=$trackId",
                audioQuality = audioQuality,
                audioModes = audioModes,
                mediaMetadataTags = mediaMetadataTags  // ✅ ADD THIS
            )
            
            Log.d("MusicRepository", "Parsed track: '$title', dur=${duration}s, quality=$audioQuality, tags=$mediaMetadataTags")
            
            // Only add tracks with valid duration
            if (duration > 0) {
                tracks.add(track)
            } else {
                Log.w("MusicRepository", "Skipping track with 0 duration: $title")
            }
        }
    } catch (e: Exception) {
        Log.e("MusicRepository", "Error parsing album tracks", e)
    }
    
    return tracks
}
    
    suspend fun getAlbumList2(type: String, size: Int = 20, artistId: String? = null): List<com.example.moniq.model.Album> = 
        withContext(Dispatchers.IO) {
            // This endpoint might not be available in the new API
            // Return empty list for now
            emptyList()
        }
}