package com.example.moniq.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object LyricsFetcher {
    private val client = OkHttpClient()

    fun buildUrl(title: String?, artist: String?, album: String?, durationSec: Int?): String {
        val base = "https://lyricsplus.prjktla.workers.dev/v2/lyrics/get"
        val params = mutableListOf<String>()
        title?.let { params.add("title=" + java.net.URLEncoder.encode(it, "utf-8")) }
        artist?.let { params.add("artist=" + java.net.URLEncoder.encode(it, "utf-8")) }
        album?.let { params.add("album=" + java.net.URLEncoder.encode(it, "utf-8")) }
        durationSec?.let { params.add("duration=${it}") }
        params.add("source=apple,lyricsplus,musixmatch,spotify,musixmatch-word")
        return base + "?" + params.joinToString("&")
    }

    suspend fun fetchLyrics(title: String?, artist: String?, album: String?, durationSec: Int?): List<com.example.moniq.lyrics.SyllableLine> {
        return withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(title, artist, album, durationSec)
                val baseReq = Request.Builder().url(url)
                    .header("User-Agent", "Moniq/lyrics-fetcher")
                    .header("Accept", "application/json")
                    .header("Referer", "https://lyricsplus.prjktla.workers.dev/")
                    .header("Origin", "https://lyricsplus.prjktla.workers.dev")
                    .build()
                var resp = client.newCall(baseReq).execute()
                if (!resp.isSuccessful) {
                    try { resp.close() } catch (_: Exception) {}
                    val altReq = Request.Builder().url(url)
                        .header("User-Agent", "Moniq/lyrics-fetcher")
                        .header("Accept", "application/json")
                        .header("Referer", "https://lyricsplus.app/")
                        .header("Origin", "https://lyricsplus.app")
                        .build()
                    resp = client.newCall(altReq).execute()
                }
                resp.use { r ->
                    if (!r.isSuccessful) return@withContext emptyList<com.example.moniq.lyrics.SyllableLine>()
                    val body = resp.body?.string() ?: return@withContext emptyList<com.example.moniq.lyrics.SyllableLine>()
                    val obj = JSONObject(body)
                    val arr = obj.optJSONArray("lyrics") ?: return@withContext emptyList<com.example.moniq.lyrics.SyllableLine>()
                    val lines = mutableListOf<com.example.moniq.lyrics.SyllableLine>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        
                        // Extract translation
                        val translationObj = item.optJSONObject("translation")
                        val translation = translationObj?.optString("text")
                        
                        // Extract transliteration syllables array
                        val translitObj = item.optJSONObject("transliteration")
                        val translitSyllArr = translitObj?.optJSONArray("syllabus")
                        
                        // Extract main syllables
                        val syllArr = item.optJSONArray("syllabus") ?: continue
                        val sylls = mutableListOf<com.example.moniq.lyrics.Syllable>()
                        for (j in 0 until syllArr.length()) {
                            val s = syllArr.getJSONObject(j)
                            val time = s.optLong("time", -1L)
                            val dur = s.optLong("duration", 0L)
                            val text = s.optString("text", "")
                            
                            // Get corresponding transliteration syllable
                            val translit = if (translitSyllArr != null && j < translitSyllArr.length()) {
                                translitSyllArr.getJSONObject(j).optString("text", null)
                            } else {
                                null
                            }
                            
                            sylls.add(com.example.moniq.lyrics.Syllable(time, dur, text, translit))
                        }
                        val lineStart = item.optLong("time", -1L)
                        lines.add(com.example.moniq.lyrics.SyllableLine(lineStart, sylls, translation))
                    }
                    return@withContext lines
                }
            } catch (e: Exception) {
                emptyList<com.example.moniq.lyrics.SyllableLine>()
            }
        }
    }
}