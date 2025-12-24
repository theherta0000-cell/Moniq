package com.example.moniq.music

import com.example.moniq.SessionManager
import com.example.moniq.model.Track
import com.example.moniq.network.OpenSubsonicApi
import com.example.moniq.network.RetrofitClient
import com.example.moniq.util.Crypto
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class MusicRepository {
    suspend fun getAlbumTracks(albumId: String): List<Track> =
            withContext(Dispatchers.IO) {
                val host = SessionManager.host ?: throw IllegalStateException("No session host")
                val username =
                        SessionManager.username
                                ?: throw IllegalStateException("No session username")
                val passwordRaw =
                        SessionManager.password
                                ?: throw IllegalStateException("No session password")
                val legacy = SessionManager.legacy

                val base = normalizeBaseUrl(host)
                val retrofit = RetrofitClient.create(base)
                val api = retrofit.create(OpenSubsonicApi::class.java)

                val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)

                val resp = api.getAlbum(username, pwParam, albumId)
                val body = resp.body() ?: ""

                val tracks = mutableListOf<Track>()
                try {
                    val trimmed = body.trimStart()
                    if (trimmed.startsWith("{")) {
                        val root = JSONObject(body)
                        val sr =
                                if (root.has("subsonic-response"))
                                        root.getJSONObject("subsonic-response")
                                else root
                        // album may be present
                        val albumObj =
        when {
            sr.has("album") -> sr.get("album")
            else -> sr.optJSONObject("album") ?: sr
        }
val albumCover =
        if (albumObj is JSONObject)
                albumObj.optString("coverArt", null)
                        ?: albumObj.optString("coverArtId", null)
        else null
val albumName =
        if (albumObj is JSONObject)
                albumObj.optString("name", null) 
                        ?: albumObj.optString("title", null)
        else null
                        val trackArr =
                                when (albumObj) {
                                    is JSONObject -> {
                                        when {
                                            albumObj.has("song") -> albumObj.get("song")
                                            albumObj.has("track") -> albumObj.get("track")
                                            else -> null
                                        }
                                    }
                                    else -> null
                                }

                        if (trackArr is JSONArray) {
                            for (i in 0 until trackArr.length()) {
                                val it = trackArr.optJSONObject(i) ?: continue
                                val id = it.optString("id", "")
                                val title = it.optString("title", it.optString("name", ""))
                                val artist = it.optString("artist", "")
                                val duration = it.optInt("duration", it.optInt("time", 0))
                                val trackCover =
                                        it.optString("coverArt", null)
                                                ?: it.optString("coverArtId", null) ?: albumCover
                                val albumId =
                                        it.optString("albumId", null) ?: it.optString("album", null)
                                tracks.add(
        Track(
                id,
                title,
                artist,
                duration,
                albumId = albumId,
                albumName = albumName,
                coverArtId = trackCover
        )
)
                            }
                        } else if (trackArr is JSONObject) {
                            val it = trackArr
                            val id = it.optString("id", "")
                            val title = it.optString("title", it.optString("name", ""))
                            val artist = it.optString("artist", "")
                            val duration = it.optInt("duration", it.optInt("time", 0))
                            val trackCover =
                                    it.optString("coverArt", null)
                                            ?: it.optString("coverArtId", null) ?: albumCover
                            val albumId =
                                    it.optString("albumId", null) ?: it.optString("album", null)
                            tracks.add(
        Track(
                id,
                title,
                artist,
                duration,
                albumId = albumId,
                albumName = albumName,
                coverArtId = trackCover
        )
)
                        }
                    } else {
                        // XML fallback
                        val factory = XmlPullParserFactory.newInstance()
                        val parser = factory.newPullParser()
                        parser.setInput(StringReader(body))
                        var event = parser.eventType
                        var albumCoverXml: String? = null
                        while (event != XmlPullParser.END_DOCUMENT) {
                            if (event == XmlPullParser.START_TAG && parser.name == "album") {
                                albumCoverXml =
                                        parser.getAttributeValue(null, "coverArt")
                                                ?: parser.getAttributeValue(null, "coverArtId")
                            }
                            if (event == XmlPullParser.START_TAG && parser.name == "track") {
                                val id = parser.getAttributeValue(null, "id") ?: ""
                                val title =
                                        parser.getAttributeValue(null, "title")
                                                ?: parser.getAttributeValue(null, "name") ?: ""
                                val artist = parser.getAttributeValue(null, "artist") ?: ""
                                val durationStr =
                                        parser.getAttributeValue(null, "duration")
                                                ?: parser.getAttributeValue(null, "time") ?: "0"
                                val duration = durationStr.toIntOrNull() ?: 0
                                val trackCoverXml =
                                        parser.getAttributeValue(null, "coverArt")
                                                ?: parser.getAttributeValue(null, "coverArtId")
                                                        ?: albumCoverXml
                                val trackAlbumId =
                                        parser.getAttributeValue(null, "albumId")
                                                ?: parser.getAttributeValue(null, "album")
                                val albumNameXml = parser.getAttributeValue(null, "album")
        ?: parser.getAttributeValue(null, "albumName")
tracks.add(
        Track(
                id,
                title,
                artist,
                duration,
                albumId = trackAlbumId,
                albumName = albumNameXml,
                coverArtId = trackCoverXml
        )
)
                            }
                            event = parser.next()
                        }
                    }
                } catch (t: Throwable) {
                    // Parsing failed: return what we have
                }

                tracks
            }

    private fun normalizeBaseUrl(host: String): String {
        var h = host.trim()
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "https://$h"
        }
        if (!h.endsWith("/")) h += "/"
        return h
    }

        suspend fun getAlbumList2(type: String, size: Int = 20, artistId: String? = null): List<com.example.moniq.model.Album> =
                withContext(Dispatchers.IO) {
                        val host = SessionManager.host ?: throw IllegalStateException("No session host")
                        val username = SessionManager.username ?: throw IllegalStateException("No session username")
                        val passwordRaw = SessionManager.password ?: throw IllegalStateException("No session password")
                        val legacy = SessionManager.legacy

                        val base = normalizeBaseUrl(host)
                        val retrofit = RetrofitClient.create(base)
                        val api = retrofit.create(OpenSubsonicApi::class.java)

                        val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)

                        val resp = api.getAlbumList2(username, pwParam, type, artistId)
                        val body = resp.body() ?: ""
                        val albums = mutableListOf<com.example.moniq.model.Album>()

                        try {
                                val trimmed = body.trimStart()
                                if (trimmed.startsWith("{")) {
                                        val root = org.json.JSONObject(body)
                                        val sr = if (root.has("subsonic-response")) root.getJSONObject("subsonic-response") else root
                                        val lists = when {
                                                sr.has("albumList2") -> sr.get("albumList2")
                                                sr.has("albumList") -> sr.get("albumList")
                                                else -> null
                                        }
                                        if (lists is org.json.JSONObject) {
                                                val arr = when {
                                                        lists.has("album") -> lists.get("album")
                                                        else -> null
                                                }
                                                if (arr is org.json.JSONArray) {
                                                        for (i in 0 until arr.length()) {
                                                                val it = arr.optJSONObject(i) ?: continue
                                                                val id = it.optString("id", "")
                                                                val name = it.optString("name", it.optString("title", ""))
                                                                val artist = it.optString("artist", "")
                                                                val year = it.optInt("year", -1).let { if (it <= 0) null else it }
                                                                val cover = it.optString("coverArt", null) ?: it.optString("coverArtId", null)
                                                                albums.add(com.example.moniq.model.Album(id, name, artist, year, cover))
                                                        }
                                                }
                                        }
                                } else {
                                        // XML fallback
                                        val factory = XmlPullParserFactory.newInstance()
                                        val parser = factory.newPullParser()
                                        parser.setInput(StringReader(body))
                                        var event = parser.eventType
                                        while (event != XmlPullParser.END_DOCUMENT) {
                                                if (event == XmlPullParser.START_TAG && parser.name == "album") {
                                                        val id = parser.getAttributeValue(null, "id") ?: ""
                                                        val name = parser.getAttributeValue(null, "name") ?: parser.getAttributeValue(null, "title") ?: ""
                                                        val artist = parser.getAttributeValue(null, "artist") ?: ""
                                                        val yearStr = parser.getAttributeValue(null, "year")
                                                        val year = yearStr?.toIntOrNull()
                                                        val cover = parser.getAttributeValue(null, "coverArt") ?: parser.getAttributeValue(null, "coverArtId")
                                                        albums.add(com.example.moniq.model.Album(id, name, artist, year, cover))
                                                }
                                                event = parser.next()
                                        }
                                }
                        } catch (_: Throwable) {}

                        albums
                }
}
