package com.example.moniq.artist

import com.example.moniq.SessionManager
import com.example.moniq.model.Album
import com.example.moniq.network.OpenSubsonicApi
import com.example.moniq.network.RetrofitClient
import com.example.moniq.util.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.json.JSONObject
import org.json.JSONArray
import java.io.StringReader

class ArtistRepository {
    suspend fun getArtistAlbums(artistId: String): List<Album> = withContext(Dispatchers.IO) {
        val host = SessionManager.host ?: throw IllegalStateException("No host in session")
        val username = SessionManager.username ?: throw IllegalStateException("No username in session")
        val passwordRaw = SessionManager.password ?: throw IllegalStateException("No password in session")
        val legacy = SessionManager.legacy

        val base = normalizeBaseUrl(host)
        val retrofit = RetrofitClient.create(base)
        val api = retrofit.create(OpenSubsonicApi::class.java)
        val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)

        val resp = api.getArtist(username, pwParam, artistId)
        val body = resp.body() ?: ""

        val albums = mutableListOf<Album>()
        try {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{")) {
                val root = JSONObject(body)
                val sr = if (root.has("subsonic-response")) root.getJSONObject("subsonic-response") else root

                // artist may contain album array
                val artistObj = if (sr.has("artist")) sr.get("artist") else sr.opt("artist")
                val albumContainer = when (artistObj) {
                    is JSONObject -> artistObj
                    else -> sr
                }

                val a = if (albumContainer.has("album")) albumContainer.get("album") else albumContainer.opt("album")
                if (a is JSONArray) {
                    for (i in 0 until a.length()) {
                        val it = a.optJSONObject(i) ?: continue
                        val id = it.optString("id", "")
                        val name = it.optString("title", it.optString("name", ""))
                        val artist = it.optString("artist", "")
                        val year = it.optInt("year", -1).let { if (it <= 0) null else it }
                        val cover = it.optString("coverArt", null) ?: it.optString("coverArtId", null)
                        albums.add(Album(id, name, artist, year, cover))
                    }
                } else if (a is JSONObject) {
                    val it = a
                    val id = it.optString("id", "")
                    val name = it.optString("title", it.optString("name", ""))
                    val artist = it.optString("artist", "")
                    val year = it.optInt("year", -1).let { if (it <= 0) null else it }
                    val cover = it.optString("coverArt", null) ?: it.optString("coverArtId", null)
                    albums.add(Album(id, name, artist, year, cover))
                }
            } else {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(body))

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "album") {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val name = parser.getAttributeValue(null, "name") ?: ""
                            val artist = parser.getAttributeValue(null, "artist") ?: ""
                            val yearStr = parser.getAttributeValue(null, "year")
                            val year = yearStr?.toIntOrNull()
                            val cover = parser.getAttributeValue(null, "coverArt") ?: parser.getAttributeValue(null, "coverArtId")
                            albums.add(Album(id, name, artist, year, cover))
                        }
                    event = parser.next()
                }
            }
        } catch (_: Throwable) {
        }

        albums
        // Fallback: if no albums found, try albumList2 with artistId
            .let { result ->
                if (result.isEmpty()) {
                    try {
                        val musicRepo = com.example.moniq.music.MusicRepository()
                        // ask for albums by artist via albumList2
                        val alt = musicRepo.getAlbumList2("byArtist", 50, artistId)
                        if (alt.isNotEmpty()) return@withContext alt
                    } catch (_: Throwable) {}
                }
                result
            }
    }

    suspend fun getArtistInfo(artistId: String): Triple<String, String?, String?> = withContext(Dispatchers.IO) {
        val host = SessionManager.host ?: throw IllegalStateException("No host in session")
        val username = SessionManager.username ?: throw IllegalStateException("No username in session")
        val passwordRaw = SessionManager.password ?: throw IllegalStateException("No password in session")
        val legacy = SessionManager.legacy

        val base = normalizeBaseUrl(host)
        val retrofit = RetrofitClient.create(base)
        val api = retrofit.create(OpenSubsonicApi::class.java)
        val pwParam = if (legacy) passwordRaw else Crypto.md5(passwordRaw)

        val resp = api.getArtistInfo(username, pwParam, artistId)
        val body = resp.body() ?: ""

        var name = ""
        var biography: String? = null
        var coverId: String? = null
        try {
    val trimmed = body.trimStart()
    if (trimmed.startsWith("{")) {
        // JSON parsing
        val root = JSONObject(body)
        val sr = if (root.has("subsonic-response")) root.getJSONObject("subsonic-response") else root
        
        val artistInfo = if (sr.has("artistInfo") || sr.has("artistInfo2")) {
            sr.optJSONObject("artistInfo2") ?: sr.optJSONObject("artistInfo")
        } else null
        
        if (artistInfo != null) {
            name = artistInfo.optString("name", name)
            biography = artistInfo.optString("biography", null)
            coverId = artistInfo.optString("coverArt", null) ?: artistInfo.optString("coverArtId", null)
        }
    } else {
        // XML parsing (fallback)
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(body))

        var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "artist") {
                name = parser.getAttributeValue(null, "name") ?: name
                coverId = parser.getAttributeValue(null, "coverArt") ?: parser.getAttributeValue(null, "coverArtId") ?: coverId
            }
            if (event == XmlPullParser.START_TAG && parser.name == "biography") {
                biography = parser.nextText()
            }
            event = parser.next()
        }
    }
} catch (_: Throwable) {
}

        Triple(name, biography, coverId)
    }

    private fun normalizeBaseUrl(host: String): String {
        var h = host.trim()
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "https://$h"
        }
        if (!h.endsWith("/")) h += "/"
        return h
    }
}
