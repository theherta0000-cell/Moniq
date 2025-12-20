package com.example.moniq.player

import android.content.Context
import com.example.moniq.model.Playlist
import com.example.moniq.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaylistManager(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val key = "playlists_json"

    fun list(): List<Playlist> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        val out = mutableListOf<Playlist>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            jsonToPlaylist(o)?.let { out.add(it) }
        }
        return out
    }

    fun get(id: String): Playlist? {
        return list().firstOrNull { it.id == id }
    }

    fun create(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name)
        val cur = JSONArray(prefs.getString(key, "[]"))
        // prepend new playlist
        val newArr = JSONArray()
        newArr.put(playlistToJson(p))
        for (i in 0 until cur.length()) newArr.put(cur.optJSONObject(i))
        prefs.edit().putString(key, newArr.toString()).apply()
        return p
    }

    fun delete(id: String) {
        val arr = JSONArray(prefs.getString(key, "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) continue
            out.put(o)
        }
        prefs.edit().putString(key, out.toString()).apply()
    }

    fun addTrack(playlistId: String, track: Track) {
        val arr = JSONArray(prefs.getString(key, "[]"))
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == playlistId) {
                val tracks = o.optJSONArray("tracks") ?: JSONArray()
                // avoid duplicates by id
                var exists = false
                for (j in 0 until tracks.length()) if (tracks.optJSONObject(j)?.optString("id") == track.id) exists = true
                if (!exists) {
                    tracks.put(trackToJson(track))
                    o.put("tracks", tracks)
                    changed = true
                }
                break
            }
        }
        if (changed) prefs.edit().putString(key, arr.toString()).apply()
    }

    fun update(p: Playlist) {
        val arr = JSONArray(prefs.getString(key, "[]"))
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == p.id) {
                arr.put(i, playlistToJson(p))
                changed = true
                break
            }
        }
        if (changed) prefs.edit().putString(key, arr.toString()).apply()
    }

    fun removeTrack(playlistId: String, trackId: String) {
        val arr = JSONArray(prefs.getString(key, "[]"))
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == playlistId) {
                val tracks = o.optJSONArray("tracks") ?: JSONArray()
                val newTracks = JSONArray()
                for (j in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(j) ?: continue
                    if (t.optString("id") == trackId) { changed = true; continue }
                    newTracks.put(t)
                }
                o.put("tracks", newTracks)
                break
            }
        }
        if (changed) prefs.edit().putString(key, arr.toString()).apply()
    }

    fun replaceTrack(playlistId: String, oldTrackId: String, newTrack: Track) {
        val arr = JSONArray(prefs.getString(key, "[]"))
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == playlistId) {
                val tracks = o.optJSONArray("tracks") ?: JSONArray()
                val newTracks = JSONArray()
                for (j in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(j) ?: continue
                    if (t.optString("id") == oldTrackId) {
                        newTracks.put(trackToJson(newTrack))
                        changed = true
                    } else {
                        newTracks.put(t)
                    }
                }
                o.put("tracks", newTracks)
                break
            }
        }
        if (changed) prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun playlistToJson(p: Playlist): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("description", p.description ?: "")
        o.put("coverArtId", p.coverArtId ?: JSONObject.NULL)
        val arr = JSONArray()
        for (t in p.tracks) arr.put(trackToJson(t))
        o.put("tracks", arr)
        return o
    }

    private fun jsonToPlaylist(o: JSONObject): Playlist? {
        val id = o.optString("id", null) ?: return null
        val name = o.optString("name", "")
        val desc = o.optString("description", null)
        val cover = if (o.has("coverArtId") && !o.isNull("coverArtId")) o.optString("coverArtId", null) else null
        val p = Playlist(id, name, desc, cover)
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            jsonToTrack(t)?.let { p.tracks.add(it) }
        }
        return p
    }

    private fun trackToJson(t: Track): JSONObject {
        val o = JSONObject()
        o.put("id", t.id)
        o.put("title", t.title)
        o.put("artist", t.artist)
        o.put("duration", t.durationSec)
        o.put("albumId", t.albumId)
        o.put("albumName", t.albumName)
        o.put("coverArtId", t.coverArtId)
        return o
    }

    private fun jsonToTrack(o: JSONObject): Track? {
        val id = o.optString("id", null) ?: return null
        val title = o.optString("title", "")
        val artist = o.optString("artist", "")
        val duration = o.optInt("duration", 0)
        val albumId = o.optString("albumId", null)
        val albumName = o.optString("albumName", null)
        val cover = o.optString("coverArtId", null)
        return Track(id, title, artist, duration, albumId = albumId, albumName = albumName, coverArtId = cover)
    }
}
