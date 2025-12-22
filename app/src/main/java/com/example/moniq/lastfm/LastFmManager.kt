package com.example.moniq.lastfm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest

object LastFmManager {
    private const val API_KEY = "f53cd1e3b1722078a3cfbb63f49b9320"
    private const val API_SECRET = "f514869dff1b1ff246d2710a6b819b88"
    private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
    
    private val client = OkHttpClient()
    private var sessionKey: String? = null
    
    // Track scrobble state
    private data class ScrobbleState(
        val trackId: String,
        val startTime: Long,
        val duration: Long,
        var scrobbled: Boolean = false
    )
    private var currentScrobble: ScrobbleState? = null
    
    /**
     * Clean artist name - extracts only the first artist from names like:
     * "Artist1 feat. Artist2" -> "Artist1"
     * "Artist1 & Artist2" -> "Artist1"
     * "Artist1, Artist2" -> "Artist1"
     */
    fun cleanArtistName(artist: String?): String {
        if (artist.isNullOrBlank()) return ""
        
        val separators = listOf(
            " feat. ", " feat ", " ft. ", " ft ", " featuring ",
            " & ", " and ",
            ", ",
            " vs. ", " vs ",
            " x "
        )
        
        var cleaned = artist.trim()
        for (sep in separators) {
            val idx = cleaned.indexOf(sep, ignoreCase = true)
            if (idx > 0) {
                cleaned = cleaned.substring(0, idx).trim()
                break
            }
        }
        
        return cleaned
    }
    
    /**
     * Authenticate with Last.fm using username and password
     */
    suspend fun authenticate(context: Context, username: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val params = mutableMapOf(
                    "method" to "auth.getMobileSession",
                    "username" to username,
                    "password" to password,
                    "api_key" to API_KEY
                )
                
                val signature = generateSignature(params)
                
                val formBody = FormBody.Builder()
                    .add("method", "auth.getMobileSession")
                    .add("username", username)
                    .add("password", password)
                    .add("api_key", API_KEY)
                    .add("api_sig", signature)
                    .add("format", "json")
                    .build()
                
                val request = Request.Builder()
                    .url(BASE_URL)
                    .post(formBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext false
                
                if (!response.isSuccessful) {
                    Log.e("LastFm", "Auth failed: $body")
                    return@withContext false
                }
                
                val json = JSONObject(body)
                if (json.has("session")) {
                    val session = json.getJSONObject("session")
                    sessionKey = session.getString("key")
                    
                    // Save credentials
                    com.example.moniq.SessionStore.saveLastFmSession(context, username, sessionKey!!)
                    
                    Log.d("LastFm", "Authenticated successfully")
                    return@withContext true
                } else {
                    Log.e("LastFm", "No session in response: $body")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e("LastFm", "Authentication error", e)
                false
            }
        }
    }
    
    /**
     * Load saved session
     */
    fun loadSession(context: Context) {
        val saved = com.example.moniq.SessionStore.loadLastFmSession(context)
        if (saved != null) {
            sessionKey = saved.second
            Log.d("LastFm", "Loaded Last.fm session for ${saved.first}")
        }
    }
    
    /**
     * Check if authenticated
     */
    fun isAuthenticated(): Boolean = sessionKey != null
    
    /**
     * Clear session
     */
    fun logout(context: Context) {
        sessionKey = null
        com.example.moniq.SessionStore.clearLastFmSession(context)
    }
    
    /**
     * Update Now Playing status
     */
    suspend fun updateNowPlaying(title: String, artist: String, album: String?, duration: Int) {
        if (!isAuthenticated()) return
        
        withContext(Dispatchers.IO) {
            try {
                val cleanedArtist = cleanArtistName(artist)
                
                val params = mutableMapOf(
                    "method" to "track.updateNowPlaying",
                    "artist" to cleanedArtist,
                    "track" to title,
                    "api_key" to API_KEY,
                    "sk" to sessionKey!!
                )
                
                if (!album.isNullOrBlank()) params["album"] = album
                if (duration > 0) params["duration"] = duration.toString()
                
                val signature = generateSignature(params)
                
                val formBuilder = FormBody.Builder()
                    .add("method", "track.updateNowPlaying")
                    .add("artist", cleanedArtist)
                    .add("track", title)
                    .add("api_key", API_KEY)
                    .add("sk", sessionKey!!)
                    .add("api_sig", signature)
                    .add("format", "json")
                
                if (!album.isNullOrBlank()) formBuilder.add("album", album)
                if (duration > 0) formBuilder.add("duration", duration.toString())
                
                val request = Request.Builder()
                    .url(BASE_URL)
                    .post(formBuilder.build())
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (response.isSuccessful) {
                    Log.d("LastFm", "Now playing updated: $title by $cleanedArtist")
                } else {
                    Log.e("LastFm", "Now playing failed: $body")
                }
            } catch (e: Exception) {
                Log.e("LastFm", "Now playing error", e)
            }
        }
    }
    
    /**
 * Scrobble a track
 */
suspend fun scrobble(
    title: String,
    artist: String,
    album: String?,
    duration: Int,
    timestamp: Long
): Boolean {
    if (!isAuthenticated()) return false

    return withContext(Dispatchers.IO) {
        try {
            val cleanedArtist = cleanArtistName(artist)

            // Use indexed parameters for scrobbling
            val params = mutableMapOf<String, String>(
                "method" to "track.scrobble",
                "artist[0]" to cleanedArtist,
                "track[0]" to title,
                "timestamp[0]" to timestamp.toString(),
                "api_key" to API_KEY,
                "sk" to sessionKey!!
            )

            if (!album.isNullOrBlank()) params["album[0]"] = album
            if (duration > 0) params["duration[0]"] = duration.toString()

            val signature = generateSignature(params)

            val formBuilder = FormBody.Builder()
                .add("method", "track.scrobble")
                .add("artist[0]", cleanedArtist)
                .add("track[0]", title)
                .add("timestamp[0]", timestamp.toString())
                .add("api_key", API_KEY)
                .add("sk", sessionKey!!)
                .add("api_sig", signature)
                .add("format", "json")

            if (!album.isNullOrBlank()) formBuilder.add("album[0]", album)
            if (duration > 0) formBuilder.add("duration[0]", duration.toString())

            val request = Request.Builder()
                .url(BASE_URL)
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e("LastFm", "Scrobble failed (HTTP ${response.code}): $body")
                    return@withContext false
                }

                val json = JSONObject(body)
                // check explicit error
                if (json.has("error")) {
                    Log.e("LastFm", "Scrobble error: ${json.optString("message")}; body=$body")
                    return@withContext false
                }

                // Successful scrobble response looks like { "scrobbles": { "@attr": { "accepted": "1", ... } } }
                val accepted = try {
                    json.getJSONObject("scrobbles")
                        .getJSONObject("@attr")
                        .optInt("accepted", 0)
                } catch (e: Exception) {
                    0
                }

                if (accepted > 0) {
                    Log.d("LastFm", "Scrobbled: $title by $cleanedArtist at $timestamp (accepted=$accepted)")
                    return@withContext true
                } else {
                    Log.e("LastFm", "Scrobble not accepted (body=$body)")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e("LastFm", "Scrobble error", e)
            false
        }
    }
}

    
    /**
     * Handle track start - updates now playing and prepares scrobble
     */
   suspend fun onTrackStart(trackId: String, title: String, artist: String, album: String?, durationMs: Long) {
    if (!isAuthenticated()) return
    
    val durationSec = (durationMs / 1000).toInt()
    
    // Update now playing
    updateNowPlaying(title, artist, album, durationSec)
    
    // Prepare scrobble state with thread safety
    synchronized(this) {
        currentScrobble = ScrobbleState(
            trackId = trackId,
            startTime = System.currentTimeMillis() / 1000,
            duration = durationMs,
            scrobbled = false
        )
    }
}
    
 /**
 * Check if track should be scrobbled based on playback position
 * Scrobble when: track >= 30 seconds AND (played >= 240 seconds OR played >= 50% of track)
 */
suspend fun checkAndScrobble(trackId: String, title: String, artist: String, album: String?, currentPositionMs: Long, durationMs: Long) {
    Log.d("LastFm", "DEBUG: checkAndScrobble entered for '$title' by '$artist', Track ID: $trackId")

    if (!isAuthenticated()) {
         Log.d("LastFm", "DEBUG: checkAndScrobble - Not authenticated, skipping for '$title'.")
         return
    }

    Log.d("LastFm", "DEBUG: checkAndScrobble - Authenticated, proceeding for '$title'.")

    val state = synchronized(this) {
        currentScrobble?.takeIf { it.trackId == trackId && !it.scrobbled }
    }
    if (state == null) {
        Log.d("LastFm", "DEBUG: checkAndScrobble - No matching scrobble state found or already scrobbled for '$title' (ID: $trackId), skipping.")
        synchronized(this) {
            Log.d("LastFm", "DEBUG: checkAndScrobble - Current scrobble state object: $currentScrobble")
        }
        return
    }
    Log.d("LastFm", "DEBUG: checkAndScrobble - Found matching scrobble state for '$title'.")

    val durationSec = (durationMs / 1000).toInt()
    Log.d("LastFm", "DEBUG: checkAndScrobble - Total Duration (ms): $durationMs, Calculated (sec): $durationSec")

    if (durationSec < 30) {
        Log.d("LastFm", "DEBUG: checkAndScrobble - Track duration ($durationSec sec) is less than 30 seconds, NOT scrobblable for '$title'.")
        return
    } else {
        Log.d("LastFm", "DEBUG: checkAndScrobble - Track duration ($durationSec sec) meets minimum requirement (>= 30s) for '$title'.")
    }

    val playedMs = currentPositionMs
    val playedSec = (playedMs / 1000).toInt()
    Log.d("LastFm", "DEBUG: checkAndScrobble - Played Time (ms): $playedMs, (sec): $playedSec for '$title'")

    val condition1 = playedSec >= 240
    val condition2 = playedMs >= (durationMs * 0.5)
    Log.d("LastFm", "DEBUG: checkAndScrobble - Condition 1 (played >= 240s): $condition1 for '$title'")
    Log.d("LastFm", "DEBUG: checkAndScrobble - Condition 2 (played >= 50% of ${durationMs}ms): $condition2 for '$title'")

    val shouldScrobble = condition1 || condition2
    Log.d("LastFm", "DEBUG: checkAndScrobble - Should scrobble: $shouldScrobble for '$title'")

    if (shouldScrobble) {
    Log.d("LastFm", "DEBUG: checkAndScrobble - Attempting to scrobble '$title' by '$artist'...")
    val success = scrobble(title, artist, album, durationSec, state.startTime)
    if (success) {
        synchronized(this) {
            currentScrobble?.let {
                if (it.trackId == trackId) {
                    currentScrobble = it.copy(scrobbled = true)
                    Log.d("LastFm", "DEBUG: checkAndScrobble - Marked scrobbled=true for '$title'.")
                }
            }
        }
    } else {
        Log.d("LastFm", "DEBUG: checkAndScrobble - Scrobble attempt failed for '$title'. Will retry next cycle.")
    }
}
}

    
    /**
     * Reset scrobble state when track changes
     */
    fun resetScrobbleState() {
    synchronized(this) {
        currentScrobble = null
    }
}
    
    /**
     * Generate API signature for Last.fm
     */
    private fun generateSignature(params: Map<String, String>): String {
        val sorted = params.toSortedMap()
        val builder = StringBuilder()
        for ((key, value) in sorted) {
            builder.append(key).append(value)
        }
        builder.append(API_SECRET)
        
        return md5(builder.toString())
    }
    
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}