package com.example.moniq.util

import com.example.moniq.SessionManager
import com.example.moniq.model.Track
import com.example.moniq.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object MetadataFetcher {

    // Simple in-memory cache to avoid refetching the same id repeatedly
    private val cache = ConcurrentHashMap<String, Track>()

    // Limit concurrent network requests (tweak permits according to your server)
    private val concurrencyLimit = Semaphore(8)

    private fun JSONObject.optStringOrNull(key: String): String? =
        this.optString(key).takeIf { it.isNotBlank() && it != "null" }

    /**
     * Fetches complete metadata for a track from the server.
     * Uses the RetrofitClient.api which should return Response<String>.
     */
    suspend fun fetchTrackMetadata(trackId: String): Track? {
        // Return cached value if present
        cache[trackId]?.let { return it }

        return withContext(Dispatchers.IO) {
            // Limit concurrent network calls
            concurrencyLimit.withPermit {
                try {
                    val username = SessionManager.username ?: return@withContext null
                    val password = SessionManager.password ?: return@withContext null
                    val api = RetrofitClient.api ?: return@withContext null

                    val response = api.getSong(username = username, password = password, id = trackId)
                    if (!response.isSuccessful) return@withContext null

                    // Since ScalarsConverterFactory is used, body() should be String
                    val bodyString = response.body() ?: return@withContext null

                    val subsonic = JSONObject(bodyString).optJSONObject("subsonic-response") ?: return@withContext null
                    val song = subsonic.optJSONObject("song") ?: return@withContext null

                    val id = song.optStringOrNull("id") ?: trackId
                    val title = song.optStringOrNull("title") ?: "Unknown"
                    val artist = song.optStringOrNull("artist") ?: "Unknown Artist"
                    val duration = song.optInt("duration", 0)
                    val albumId = song.optStringOrNull("albumId")
                    val albumName = song.optStringOrNull("album")
                    val coverArtId = song.optStringOrNull("coverArt")

                    val track = Track(
                        id = id,
                        title = title,
                        artist = artist,
                        durationSec = duration,
                        albumId = albumId,
                        albumName = albumName,
                        coverArtId = coverArtId
                    )

                    // Cache result (immutable Track assumed)
                    cache[trackId] = track
                    track
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }
    }

    /**
     * Enriches a single track with metadata fetched from server.
     * Prefers existing values unless they are blank/unknown.
     */
    suspend fun enrichTrackMetadata(track: Track): Track {
        val needsFetch = track.title.isBlank() ||
                track.title == "Unknown" ||
                track.artist.isBlank() ||
                track.artist == "Unknown Artist" ||
                track.albumName.isNullOrBlank()

        if (!needsFetch) return track

        val fetched = fetchTrackMetadata(track.id) ?: return track

        return track.copy(
            title = if (track.title.isBlank() || track.title == "Unknown") fetched.title else track.title,
            artist = if (track.artist.isBlank() || track.artist == "Unknown Artist") fetched.artist else track.artist,
            durationSec = if (track.durationSec == 0) fetched.durationSec else track.durationSec,
            albumId = track.albumId.takeUnless { it.isNullOrBlank() } ?: fetched.albumId,
            albumName = track.albumName.takeUnless { it.isNullOrBlank() } ?: fetched.albumName,
            coverArtId = track.coverArtId.takeUnless { it.isNullOrBlank() } ?: fetched.coverArtId
        )
    }

    /**
     * Enriches multiple tracks in parallel (bounded by concurrencyLimit).
     * Returns a new list where each Track is enriched where possible.
     */
    suspend fun enrichTracksMetadata(tracks: List<Track>): List<Track> = coroutineScope {
        tracks.map { t ->
            async { enrichTrackMetadata(t) }
        }.awaitAll()
    }

    /**
     * Utility: clear the in-memory cache (useful for debugging)
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Optional helper to prefetch a set of IDs into cache (bounded concurrency).
     */
    suspend fun prefetch(ids: List<String>) = coroutineScope {
        ids.map { id ->
            async { fetchTrackMetadata(id) }
        }.awaitAll()
    }
}
