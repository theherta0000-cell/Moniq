package com.example.moniq.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object LyricsFetcher {
    private val client = OkHttpClient()

    suspend fun fetchLyrics(title: String?, artist: String?, album: String?, durationSec: Int?): List<com.example.moniq.lyrics.SyllableLine> {
        return withContext(Dispatchers.IO) {
            try {
                val base = "https://lyricsplus.prjktla.workers.dev/v2/lyrics/get"
                val params = mutableListOf<String>()
                title?.let { params.add("title=" + java.net.URLEncoder.encode(it, "utf-8")) }
                artist?.let { params.add("artist=" + java.net.URLEncoder.encode(it, "utf-8")) }
                album?.let { params.add("album=" + java.net.URLEncoder.encode(it, "utf-8")) }
                durationSec?.let { params.add("duration=${it}") }
                // prefer available sources
                params.add("source=apple,lyricsplus,musixmatch,spotify,musixmatch-word")
                val url = base + "?" + params.joinToString("&")

                val req = Request.Builder().url(url).header("User-Agent", "Moniq/lyrics-fetcher").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList<com.example.moniq.lyrics.SyllableLine>()
                    val body = resp.body?.string() ?: return@withContext emptyList<com.example.moniq.lyrics.SyllableLine>()
                    val obj = JSONObject(body)
                    val arr = obj.optJSONArray("lyrics") ?: return@withContext emptyList<com.example.moniq.lyrics.SyllableLine>()
                    val lines = mutableListOf<com.example.moniq.lyrics.SyllableLine>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val syllArr = item.optJSONArray("syllabus") ?: continue
                        val sylls = mutableListOf<com.example.moniq.lyrics.Syllable>()
                        for (j in 0 until syllArr.length()) {
                            val s = syllArr.getJSONObject(j)
                            val time = s.optLong("time", -1L)
                            val dur = s.optLong("duration", 0L)
                            val text = s.optString("text", "")
                            val translit = s.optString("transliteration", "")
                            // transliteration may be nested; try to read text from transliteration field
                            val transText = if (translit.isNotEmpty()) translit else s.optString("transliterationText", "")
                            sylls.add(com.example.moniq.lyrics.Syllable(time, dur, text, transText))
                        }
                        val lineStart = item.optLong("time", -1L)
                        lines.add(com.example.moniq.lyrics.SyllableLine(lineStart, sylls))
                    }
                    return@withContext lines
                }
            } catch (e: Exception) {
                emptyList<com.example.moniq.lyrics.SyllableLine>()
            }
        }
    }
}
