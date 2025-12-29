package com.example.moniq.util

import android.util.Log
import com.example.moniq.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MetadataFetcher {
    /**
     * Enriches a single track with metadata from search if streamUrl is present
     */
    suspend fun enrichTrackMetadata(track: Track): Track = withContext(Dispatchers.IO) {
        if (track.streamUrl?.contains("/stream/?id=") == true) {
            try {
                // Extract track ID from streamUrl
                val trackId = track.streamUrl.substringAfter("id=").substringBefore("&")
                    .takeIf { it.isNotBlank() } ?: track.id
                
                // Resolve to actual streaming URL
                val resolvedUrl = StreamUrlResolver.resolveStreamUrl(
                    track.streamUrl, 
                    trackId, 
                    "LOSSLESS"
                )
                
                if (resolvedUrl != null) {
                    Log.d("MetadataFetcher", "Resolved URL for ${track.title}: $resolvedUrl")
                    return@withContext track.copy(streamUrl = resolvedUrl)
                }
            } catch (e: Exception) {
                Log.e("MetadataFetcher", "Failed to enrich track ${track.title}", e)
            }
        }
        return@withContext track
    }
    
    /**
     * Enriches multiple tracks with metadata
     */
    suspend fun enrichTracksMetadata(tracks: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        tracks.map { track ->
            try {
                enrichTrackMetadata(track)
            } catch (e: Exception) {
                Log.e("MetadataFetcher", "Failed to enrich track", e)
                track
            }
        }
    }
}