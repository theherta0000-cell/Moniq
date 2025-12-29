package com.example.moniq.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class LyricsResult(
        val lines: List<com.example.moniq.lyrics.SyllableLine>,
        val successfulUrl: String?
)

object LyricsFetcher {
    private val client = OkHttpClient()

    fun buildUrl(
            title: String?,
            artist: String?,
            album: String?,
            durationSec: Int?,
            baseUrl: String = "https://lyricsplus.prjktla.workers.dev/v2/lyrics/get"
    ): String {
        val params = mutableListOf<String>()
        title?.let { params.add("title=" + java.net.URLEncoder.encode(it, "utf-8")) }
        artist?.let { params.add("artist=" + java.net.URLEncoder.encode(it, "utf-8")) }
        album?.let { params.add("album=" + java.net.URLEncoder.encode(it, "utf-8")) }
        durationSec?.let { params.add("duration=${it}") }
        params.add("source=apple,lyricsplus,musixmatch,spotify,musixmatch-word")
        return baseUrl + "?" + params.joinToString("&")
    }

    suspend fun fetchLyrics(
            title: String?,
            artist: String?,
            album: String?,
            durationSec: Int?
    ): LyricsResult {
        return withContext(Dispatchers.IO) {
            try {
                // Try workers.dev with first referer
                val url1 = buildUrl(title, artist, album, durationSec)
                val req1 =
                        Request.Builder()
                                .url(url1)
                                .header("User-Agent", "Moniq/lyrics-fetcher")
                                .header("Accept", "application/json")
                                .header("Referer", "https://lyricsplus.prjktla.workers.dev/")
                                .header("Origin", "https://lyricsplus.prjktla.workers.dev")
                                .addHeader("x-client", "BiniLossless/v3.3")
                                .build()
                var resp = client.newCall(req1).execute()

                // Try workers.dev with alternate referer
                if (!resp.isSuccessful) {
                    try {
                        resp.close()
                    } catch (_: Exception) {}
                    val req2 =
                            Request.Builder()
                                    .url(url1)
                                    .header("User-Agent", "Moniq/lyrics-fetcher")
                                    .header("Accept", "application/json")
                                    .header("Referer", "https://lyricsplus.app/")
                                    .header("Origin", "https://lyricsplus.app")
                                    .addHeader("x-client", "BiniLossless/v3.3")
                                    .build()
                    resp = client.newCall(req2).execute()
                }

                // Try Vercel backend
                if (!resp.isSuccessful) {
                    try {
                        resp.close()
                    } catch (_: Exception) {}
                    val url2 =
                            buildUrl(
                                    title,
                                    artist,
                                    album,
                                    durationSec,
                                    "https://lyrics-plus-backend.vercel.app/v2/lyrics/get"
                            )
                    val req3 =
                            Request.Builder()
                                    .url(url2)
                                    .header("User-Agent", "Moniq/lyrics-fetcher")
                                    .header("Accept", "application/json")
                                    .header("Referer", "https://lyrics-plus-backend.vercel.app/")
                                    .header("Origin", "https://lyrics-plus-backend.vercel.app")
                                    .addHeader("x-client", "BiniLossless/v3.3")
                                    .build()
                    resp = client.newCall(req3).execute()
                }

                resp.use { r ->
                    if (!r.isSuccessful) return@withContext LyricsResult(emptyList(), null)
                    val body =
                            resp.body?.string()
                                    ?: return@withContext LyricsResult(emptyList(), null)
                    val obj = JSONObject(body)
                    val arr =
                            obj.optJSONArray("lyrics")
                                    ?: return@withContext LyricsResult(emptyList(), null)
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
                        val syllArr = item.optJSONArray("syllabus")
                        val sylls = mutableListOf<com.example.moniq.lyrics.Syllable>()

                        // If syllabus is empty or missing, use the line's text as a single syllable
                        if (syllArr == null || syllArr.length() == 0) {
                            val lineText = item.optString("text", "")
                            val lineTime = item.optLong("time", -1L)
                            val lineDuration = item.optLong("duration", 0L)

                            if (lineText.isNotEmpty()) {
                                // Get transliteration text if available
                                val translitText = translitObj?.optString("text")
                                sylls.add(
                                        com.example.moniq.lyrics.Syllable(
                                                lineTime,
                                                lineDuration,
                                                lineText,
                                                translitText,
                                                false  // Line-level items are never background
                                        )
                                )
                            }
                        } else {
                            // Process syllable-by-syllable timing
                            for (j in 0 until syllArr.length()) {
                                val s = syllArr.getJSONObject(j)
                                val time = s.optLong("time", -1L)
                                val dur = s.optLong("duration", 0L)
                                val text = s.optString("text", "")
                                val isBackground = s.optBoolean("isBackground", false)

                                // Get corresponding transliteration syllable
                                val translit = if (translitSyllArr != null && j < translitSyllArr.length()) {
                                    translitSyllArr.getJSONObject(j).optString("text", null)
                                } else {
                                    null
                                }

                                sylls.add(
                                    com.example.moniq.lyrics.Syllable(time, dur, text, translit, isBackground)
                                )
                            }
                        }

                        val lineStart = item.optLong("time", -1L)
                        if (sylls.isNotEmpty()) {
                            lines.add(
                                com.example.moniq.lyrics.SyllableLine(
                                    lineStart,
                                    sylls,
                                    translation
                                )
                            )
                        }
                    }
                    // Determine which URL was successful
                    val successUrl =
                            when {
                                resp.request.url.host.contains("workers.dev") ->
                                        resp.request.url.toString()
                                resp.request.url.host.contains("vercel.app") ->
                                        resp.request.url.toString()
                                else -> resp.request.url.toString()
                            }
                    return@withContext LyricsResult(lines, successUrl)
                }
            } catch (e: Exception) {
                LyricsResult(emptyList(), null)
            }
        }
    }
}