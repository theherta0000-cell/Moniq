package com.example.moniq.search

import android.util.Log
import com.example.moniq.SessionManager
import com.example.moniq.model.Album
import com.example.moniq.model.Artist
import com.example.moniq.model.Track
import com.example.moniq.network.OpenSubsonicApi
import com.example.moniq.network.RetrofitClient
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class SearchResults(
        val songs: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val code: Int = 0,
        val rawBody: String = ""
)

class SearchRepository {
    suspend fun search(query: String): SearchResults {
        val host = SessionManager.host ?: throw IllegalStateException("No host in session")
        val username = SessionManager.username ?: ""
        val password = SessionManager.password ?: ""

        val api: OpenSubsonicApi = RetrofitClient.create(host).create(OpenSubsonicApi::class.java)
        val resp = api.search3(username, password, query)
        val code =
                try {
                    resp.code()
                } catch (_: Exception) {
                    -1
                }
        val body =
                try {
                    resp.body() ?: resp.errorBody()?.string() ?: ""
                } catch (_: Exception) {
                    ""
                }

        // Debug logging: print response code and body to help diagnose search issues
        try {
            Log.i("SearchRepository", "search3 response code=${code}, body=${body.take(800)}")
        } catch (t: Throwable) {
            // ignore logging errors
        }

        val songs = mutableListOf<Track>()
        val albums = mutableListOf<Album>()
        val artists = mutableListOf<Artist>()

        try {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{")) {
                // JSON path
                val root = JSONObject(body)
                // many servers wrap in "subsonic-response"
                val sr =
                        if (root.has("subsonic-response")) root.getJSONObject("subsonic-response")
                        else root
                val search =
                        if (sr.has("searchResult3")) sr.get("searchResult3")
                        else sr.opt("searchResult3")
                // searchResult3 may be an object with arrays
                val searchObj =
                        when (search) {
                            is JSONObject -> search as JSONObject
                            else -> sr.optJSONObject("searchResult3") ?: sr
                        }

                // artists
                if (searchObj.has("artist")) {
                    val a = searchObj.get("artist")
                    if (a is JSONArray) {
                        for (i in 0 until a.length()) {
                            val it = a.optJSONObject(i) ?: continue
                            val id = it.optString("id", "")
                            val name = it.optString("name", "")
                            val cover =
                                    it.optString("coverArt", null)
                                            ?: it.optString("coverArtId", null)
                            artists.add(Artist(id, name, cover))
                        }
                    } else if (a is JSONObject) {
                        val id = a.optString("id", "")
                        val name = a.optString("name", "")
                        val cover = a.optString("coverArt", null) ?: a.optString("coverArtId", null)
                        artists.add(Artist(id, name, cover))
                    }
                }

                // albums
if (searchObj.has("album")) {
    val a = searchObj.get("album")
    Log.d("SearchRepository", "Found 'album' key, type=${a.javaClass.simpleName}")
    if (a is JSONArray) {
        Log.d("SearchRepository", "Album is JSONArray, length=${a.length()}")
        for (i in 0 until a.length()) {
            val it = a.optJSONObject(i) ?: continue
            val id = it.optString("id", "")
            
            // Fix: properly handle null/empty title and name fields
            val titleRaw = if (it.has("title")) it.optString("title", null) else null
            val nameRaw = if (it.has("name")) it.optString("name", null) else null
            val name = when {
                !titleRaw.isNullOrBlank() -> titleRaw
                !nameRaw.isNullOrBlank() -> nameRaw
                else -> "Unknown Album"
            }
            
            val artist = it.optString("artist", "")
            val year = it.optInt("year", -1).let { if (it <= 0) null else it }
            val cover =
                    it.optString("coverArt", null)
                            ?: it.optString("coverArtId", null)
            Log.d("SearchRepository", "Album[$i]: id=$id, name=$name, artist=$artist, year=$year, cover=$cover, rawTitle=$titleRaw, rawName=$nameRaw")
            albums.add(Album(id, name, artist, year, cover))
        }
    } else if (a is JSONObject) {
        val id = a.optString("id", "")
        
        // Fix: properly handle null/empty title and name fields
        val titleRaw = if (a.has("title")) a.optString("title", null) else null
        val nameRaw = if (a.has("name")) a.optString("name", null) else null
        val name = when {
            !titleRaw.isNullOrBlank() -> titleRaw
            !nameRaw.isNullOrBlank() -> nameRaw
            else -> "Unknown Album"
        }
        
        val artist = a.optString("artist", "")
        val year = a.optInt("year", -1).let { if (it <= 0) null else it }
        val cover = a.optString("coverArt", null) ?: a.optString("coverArtId", null)
        Log.d("SearchRepository", "Album (single): id=$id, name=$name, artist=$artist, year=$year, cover=$cover, rawTitle=$titleRaw, rawName=$nameRaw")
        albums.add(Album(id, name, artist, year, cover))
    }
} else {
    Log.d("SearchRepository", "No 'album' key found in searchObj. Available keys: ${searchObj.keys().asSequence().toList()}")
}

                // songs / tracks
                if (searchObj.has("song") || searchObj.has("track")) {
                    val key = if (searchObj.has("song")) "song" else "track"
                    val a = searchObj.get(key)
                    if (a is JSONArray) {
                        for (i in 0 until a.length()) {
                            val it = a.optJSONObject(i) ?: continue
                            val id = it.optString("id", "")
                            val title = it.optString("title", it.optString("name", ""))
                            val artist = it.optString("artist", "")
                            val duration = it.optInt("duration", it.optInt("seconds", 0))
                            val albumIdStr = it.optString("albumId", null)
                            val albumNameStr =
                                    it.optString("album", null) ?: it.optString("albumName", null)
                            val albumId = albumIdStr ?: null
                            val cover =
                                    it.optString("coverArt", null)
                                            ?: it.optString("coverArtId", null)
                            songs.add(
                                    Track(
                                            id,
                                            title,
                                            artist,
                                            duration,
                                            albumId = albumId,
                                            albumName = albumNameStr,
                                            coverArtId = cover
                                    )
                            )
                        }
                    } else if (a is JSONObject) {
                        val id = a.optString("id", "")
                        val title = a.optString("title", a.optString("name", ""))
                        val artist = a.optString("artist", "")
                        val duration = a.optInt("duration", a.optInt("seconds", 0))
                        val albumIdStr = a.optString("albumId", null)
                        val albumNameStr =
                                a.optString("album", null)
                                        ?: a.optString("albumName", null) // ← FIXED
                        val albumId = albumIdStr ?: null
                        val cover = a.optString("coverArt", null) ?: a.optString("coverArtId", null)
                        songs.add(
                                Track(
                                        id,
                                        title,
                                        artist,
                                        duration,
                                        albumId = albumId,
                                        albumName = albumNameStr,
                                        coverArtId = cover
                                )
                        )
                    }
                }
            } else {
                // XML fallback
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(body.reader())

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "artist" -> {
                                val id = parser.getAttributeValue(null, "id") ?: ""
                                val name = parser.getAttributeValue(null, "name") ?: ""
                                val cover =
                                        parser.getAttributeValue(null, "coverArt")
                                                ?: parser.getAttributeValue(null, "coverArtId")
                                artists.add(Artist(id, name, cover))
                            }
                            "album" -> {
                                val id = parser.getAttributeValue(null, "id") ?: ""
                                // Check for "title" first (standard), then fall back to "name"
                                val title = parser.getAttributeValue(null, "title")
                                val name = title ?: parser.getAttributeValue(null, "name") ?: ""
                                val artist = parser.getAttributeValue(null, "artist") ?: ""
                                val yearStr = parser.getAttributeValue(null, "year")
                                val year = yearStr?.toIntOrNull()
                                val cover =
                                        parser.getAttributeValue(null, "coverArt")
                                                ?: parser.getAttributeValue(null, "coverArtId")
                                albums.add(Album(id, name, artist, year, cover))
                            }
                            "song", "track" -> {
                                val id = parser.getAttributeValue(null, "id") ?: ""
                                val title = parser.getAttributeValue(null, "title") ?: ""
                                val artist = parser.getAttributeValue(null, "artist") ?: ""
                                val albumIdRaw = parser.getAttributeValue(null, "albumId")
                                val albumNameRaw = parser.getAttributeValue(null, "album")
                                val albumId = albumIdRaw ?: null
                                val cover =
                                        parser.getAttributeValue(null, "coverArt")
                                                ?: parser.getAttributeValue(null, "coverArtId")
                                val durationStr =
                                        parser.getAttributeValue(null, "duration")
                                                ?: parser.getAttributeValue(null, "seconds") ?: "0"
                                val duration = durationStr.toIntOrNull() ?: 0
                                songs.add(
                                        Track(
                                                id,
                                                title,
                                                artist,
                                                duration,
                                                albumId = albumId,
                                                albumName = albumNameRaw,
                                                coverArtId = cover
                                        )
                                )
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.w("SearchRepository", "parse error", e)
            // swallow and return what we have
        }

        // Post-parse filtering: sometimes servers return artist-like entries in the album list.
        // Remove albums that clearly correspond to an artist (same id or very similar name)
        // unless the album contains explicit artist metadata.
        try {
            val artistIds = artists.map { it.id }.toSet()
            val artistNames = artists.map { it.name.lowercase().trim() }.filter { it.isNotBlank() }

            fun looksLikeArtistByName(albumName: String): Boolean {
                val aName = albumName.lowercase().trim()
                if (aName.isEmpty()) return false
                // Exact match
                if (artistNames.contains(aName)) return true
                // Substring match: artist name contained in album name or vice versa
                for (an in artistNames) {
                    if (an.isEmpty()) continue
                    if (aName.contains(an) || an.contains(aName)) return true
                }
                return false
            }

            val filteredAlbums =
        albums.filter { alb ->
            val nameLower = alb.name.lowercase().trim()
            val hasArtistField = !alb.artist.isNullOrBlank()
            val idLooksLikeArtist = alb.id.isNotBlank() && artistIds.contains(alb.id)
            val nameLooksLikeArtist = looksLikeArtistByName(nameLower)

            // Keep album if it has an artist, or it doesn't look like an artist entry
            val keep = hasArtistField || (!idLooksLikeArtist && !nameLooksLikeArtist)
            if (!keep) {
                Log.d("SearchRepository", "FILTERED OUT album: id=${alb.id}, name=${alb.name}, hasArtist=$hasArtistField, idLooksLikeArtist=$idLooksLikeArtist, nameLooksLikeArtist=$nameLooksLikeArtist")
            }
            keep
        }

Log.d("SearchRepository", "Albums before filter: ${albums.size}, after filter: ${filteredAlbums.size}")

            return SearchResults(
                    songs = songs,
                    albums = filteredAlbums,
                    artists = artists,
                    code = code,
                    rawBody = body
            )
        } catch (t: Throwable) {
            return SearchResults(
                    songs = songs,
                    albums = albums,
                    artists = artists,
                    code = code,
                    rawBody = body
            )
        }
    }
}
