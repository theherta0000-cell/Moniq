package com.example.moniq.player

import android.content.Context
import com.example.moniq.model.Track
import com.example.moniq.SessionManager
import org.json.JSONArray
import org.json.JSONObject

class RecentlyPlayedManager(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("recently_played", Context.MODE_PRIVATE)
    private val key = "recent_list"
    private val countsKey = "recent_counts"

    fun add(track: Track) {
        val arr = JSONArray(prefs.getString(key, "[]"))
        // remove existing with same id
        val newArr = JSONArray()
        newArr.put(trackToJson(track))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == track.id) continue
            newArr.put(o)
        }
        prefs.edit().putString(key, newArr.toString()).apply()
        // Also increment play counts for track/artist/album
        try {
            val counts = JSONObject(prefs.getString(countsKey, "{}"))
            val tracksObj = counts.optJSONObject("tracks") ?: JSONObject()
            val artistsObj = counts.optJSONObject("artists") ?: JSONObject()
            val albumsObj = counts.optJSONObject("albums") ?: JSONObject()

            // track
            val trId = track.id
            val prevTr = tracksObj.optInt(trId, 0)
            tracksObj.put(trId, prevTr + 1)

            // artist (use name)
            val art = track.artist ?: ""
            if (art.isNotEmpty()) {
                val prevA = artistsObj.optInt(art, 0)
                artistsObj.put(art, prevA + 1)
            }

            // album
            val alb = track.albumId ?: ""
            if (alb.isNotEmpty()) {
                val prevAl = albumsObj.optInt(alb, 0)
                albumsObj.put(alb, prevAl + 1)
            }

            val out = JSONObject()
            out.put("tracks", tracksObj)
            out.put("artists", artistsObj)
            out.put("albums", albumsObj)
            prefs.edit().putString(countsKey, out.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getTrackPlayCount(trackId: String): Int {
        try {
            val counts = JSONObject(prefs.getString(countsKey, "{}"))
            val tracksObj = counts.optJSONObject("tracks") ?: return 0
            return tracksObj.optInt(trackId, 0)
        } catch (_: Exception) { return 0 }
    }

    fun getArtistPlayCounts(): Map<String, Int> {
        try {
            val counts = JSONObject(prefs.getString(countsKey, "{}"))
            val artistsObj = counts.optJSONObject("artists") ?: return emptyMap()
            val map = mutableMapOf<String, Int>()
            val keys = artistsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = artistsObj.optInt(k, 0)
            }
            return map
        } catch (_: Exception) { return emptyMap() }
    }

    fun getAlbumPlayCounts(): Map<String, Int> {
        try {
            val counts = JSONObject(prefs.getString(countsKey, "{}"))
            val albumsObj = counts.optJSONObject("albums") ?: return emptyMap()
            val map = mutableMapOf<String, Int>()
            val keys = albumsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = albumsObj.optInt(k, 0)
            }
            return map
        } catch (_: Exception) { return emptyMap() }
    }

    fun latest(): Track? {
        val arr = JSONArray(prefs.getString(key, "[]"))
        if (arr.length() == 0) return null
        val o = arr.optJSONObject(0) ?: return null
        return jsonToTrack(o)
    }

    fun all(limit: Int = 20): List<Track> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        val list = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            if (list.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            jsonToTrack(o)?.let { list.add(it) }
        }
        return list
    }

    private fun trackToJson(t: Track): JSONObject {
        val o = JSONObject()
        o.put("id", t.id)
        o.put("title", t.title)
        o.put("artist", t.artist)
        o.put("albumId", t.albumId)
        // Normalize coverArtId to an absolute URL when possible so recent items always show covers
        val cover = when {
            t.coverArtId.isNullOrBlank() -> null
            t.coverArtId.startsWith("http") -> t.coverArtId
            SessionManager.host != null -> android.net.Uri.parse(SessionManager.host).buildUpon()
                .appendPath("rest").appendPath("getCoverArt.view")
                .appendQueryParameter("id", t.coverArtId)
                .appendQueryParameter("u", SessionManager.username ?: "")
                .appendQueryParameter("p", SessionManager.password ?: "")
                .build().toString()
            else -> t.coverArtId
        }
        o.put("coverArtId", cover)
        return o
    }

    private fun jsonToTrack(o: JSONObject): Track? {
        val id = o.optString("id", null) ?: return null
        val title = o.optString("title", "")
        val artist = o.optString("artist", "")
        val albumId = o.optString("albumId", null)
        val coverArt = o.optString("coverArtId", null)
        return Track(id, title, artist, 0, albumId = albumId, coverArtId = coverArt)
    }

    /**
     * Update the stored coverArtId for an existing recent entry by track id.
     * This allows adapters to persist a successfully-resolved absolute cover URL
     * so future displays (e.g., Recently Played) can use it directly.
     */
    fun setCoverForTrack(trackId: String, coverUrl: String) {
        try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == trackId) {
                    val existing = o.optString("coverArtId", null)
                    if (existing != coverUrl) {
                        o.put("coverArtId", coverUrl)
                        changed = true
                    }
                    break
                }
            }
            if (changed) prefs.edit().putString(key, arr.toString()).apply()
        } catch (_: Exception) {}
    }
}
