package com.example.moniq.util

import android.util.Log
import com.example.moniq.model.AlbumInfo
import com.example.moniq.model.ArtistInfo
import com.example.moniq.model.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object TrackInfoFetcher {
    
    suspend fun fetchTrackInfo(trackId: String): TrackInfo? = withContext(Dispatchers.IO) {
        val servers = ServerManager.getOrderedServers()
        
        for (server in servers) {
            try {
                val url = "$server/info/?id=$trackId"
                Log.d("TrackInfoFetcher", "Trying server: $server")
                
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
conn.connectTimeout = 10000
conn.readTimeout = 10000
conn.requestMethod = "GET"
conn.setRequestProperty("Accept", "*/*")
conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
conn.setRequestProperty("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
                
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    
                    // Parse the response
                    val trackInfo = parseTrackInfo(response)
                    
                    if (trackInfo != null) {
                        // Success! Record it and return immediately
                        ServerManager.recordSuccess(server)
                        Log.d("TrackInfoFetcher", "✓ Successfully fetched from $server")
                        return@withContext trackInfo
                    } else {
                        // Failed to parse
                        ServerManager.recordFailure(server)
                        Log.w("TrackInfoFetcher", "✗ Failed to parse response from $server")
                    }
                } else {
                    conn.disconnect()
                    ServerManager.recordFailure(server)
                    Log.w("TrackInfoFetcher", "✗ Server $server returned HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("TrackInfoFetcher", "✗ Exception from $server: ${e.message}")
                ServerManager.recordFailure(server)
                continue
            }
        }
        
        Log.e("TrackInfoFetcher", "All servers failed for track $trackId")
        null
    }
    
    private fun parseTrackInfo(json: String): TrackInfo? {
    try {
        val root = JSONObject(json)
        
        if (!root.has("data") || root.isNull("data")) {
            Log.e("TrackInfoFetcher", "Missing 'data' field in response")
            return null
        }
        
        val data = root.getJSONObject("data")
        
        // ✅ NEW: Parse highest quality from mediaMetadata.tags
        val audioQuality = try {
            val mediaMetadata = data.optJSONObject("mediaMetadata")
            val tags = mediaMetadata?.optJSONArray("tags")
            
            when {
                tags?.toString()?.contains("HIRES_LOSSLESS") == true -> "HI_RES_LOSSLESS"
                tags?.toString()?.contains("LOSSLESS") == true -> "LOSSLESS"
                else -> data.optString("audioQuality", "LOSSLESS")
            }
        } catch (e: Exception) {
            data.optString("audioQuality", "LOSSLESS")
        }
        
        // ✅ Parse bit depth and sample rate
        val bitDepth = data.optInt("bitDepth", 0).takeIf { it > 0 }
        val sampleRate = data.optInt("sampleRate", 0).takeIf { it > 0 }
        
        // Parse artist
        val artistObj = data.optJSONObject("artist")
        val artist = if (artistObj != null && !data.isNull("artist")) {
            try {
                ArtistInfo(
                    id = artistObj.optLong("id", 0L),
                    name = artistObj.optString("name", "Unknown Artist"),
                    type = artistObj.optString("type").takeIf { it.isNotEmpty() },
                    picture = artistObj.optString("picture").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                Log.w("TrackInfoFetcher", "Failed to parse artist: ${e.message}")
                null
            }
        } else null
        
        // Parse artists array
        val artistsArray = data.optJSONArray("artists")
        val artists = mutableListOf<ArtistInfo>()
        if (artistsArray != null) {
            for (i in 0 until artistsArray.length()) {
                try {
                    val a = artistsArray.optJSONObject(i)
                    if (a != null) {
                        artists.add(
                            ArtistInfo(
                                id = a.optLong("id", 0L),
                                name = a.optString("name", "Unknown Artist"),
                                type = a.optString("type").takeIf { it.isNotEmpty() },
                                picture = a.optString("picture").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w("TrackInfoFetcher", "Failed to parse artist at index $i: ${e.message}")
                    continue
                }
            }
        }
        
        // Parse album
        val albumObj = data.optJSONObject("album")
        val album = if (albumObj != null && !data.isNull("album")) {
            try {
                AlbumInfo(
                    id = albumObj.optLong("id", 0L),
                    title = albumObj.optString("title", "Unknown Album"),
                    cover = albumObj.optString("cover").takeIf { it.isNotEmpty() },
                    vibrantColor = albumObj.optString("vibrantColor").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                Log.w("TrackInfoFetcher", "Failed to parse album: ${e.message}")
                null
            }
        } else null
        
        // Parse audio modes
        val audioModesArray = data.optJSONArray("audioModes")
        val audioModes = mutableListOf<String>()
        if (audioModesArray != null) {
            for (i in 0 until audioModesArray.length()) {
                try {
                    val mode = audioModesArray.optString(i)
                    if (mode.isNotEmpty()) {
                        audioModes.add(mode)
                    }
                } catch (e: Exception) {
                    Log.w("TrackInfoFetcher", "Failed to parse audio mode at index $i: ${e.message}")
                    continue
                }
            }
        }
        
        // Validate required fields
        if (!data.has("id") || !data.has("title")) {
            Log.e("TrackInfoFetcher", "Missing required fields (id or title)")
            return null
        }
        
        return TrackInfo(
            id = data.optLong("id", 0L),
            title = data.optString("title", "Unknown Title"),
            duration = data.optInt("duration", 0),
            replayGain = if (data.has("replayGain") && !data.isNull("replayGain")) 
                data.optDouble("replayGain") else null,
            peak = if (data.has("peak") && !data.isNull("peak")) 
                data.optDouble("peak") else null,
            trackNumber = if (data.has("trackNumber") && !data.isNull("trackNumber")) 
                data.optInt("trackNumber") else null,
            volumeNumber = if (data.has("volumeNumber") && !data.isNull("volumeNumber")) 
                data.optInt("volumeNumber") else null,
            popularity = if (data.has("popularity") && !data.isNull("popularity")) 
                data.optInt("popularity") else null,
            copyright = data.optString("copyright").takeIf { it.isNotEmpty() },
            bpm = if (data.has("bpm") && !data.isNull("bpm")) 
                data.optInt("bpm") else null,
            key = data.optString("key").takeIf { it.isNotEmpty() },
            keyScale = data.optString("keyScale").takeIf { it.isNotEmpty() },
            isrc = data.optString("isrc").takeIf { it.isNotEmpty() },
            explicit = data.optBoolean("explicit", false),
            audioQuality = audioQuality,  // ✅ Use parsed quality
            audioModes = audioModes,
            artist = artist,
            artists = artists,
            album = album,
            bitDepth = bitDepth,  // ✅ NEW
            sampleRate = sampleRate  // ✅ NEW
        )
    } catch (e: Exception) {
        Log.e("TrackInfoFetcher", "Failed to parse track info: ${e.message}", e)
        return null
    }
}
}