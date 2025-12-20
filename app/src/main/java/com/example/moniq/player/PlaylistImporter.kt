package com.example.moniq.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.example.moniq.model.Track
import com.example.moniq.search.SearchRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistImporter(private val ctx: Context) {
    val importing = MutableLiveData(false)
    val progress = MutableLiveData(0) // 0..100
    data class ImportResult(val originalQuery: String, val matched: com.example.moniq.model.Track?)

    fun importInto(playlistId: String, uri: Uri, onComplete: (imported: Int, details: List<ImportResult>) -> Unit = {_,_->}) {
        val pm = PlaylistManager(ctx)
        importing.postValue(true)
        progress.postValue(0)
        CoroutineScope(Dispatchers.IO).launch {
            var imported = 0
            val report = mutableListOf<ImportResult>()
            try {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    val reader = BufferedReader(InputStreamReader(ins))
                    val lines = reader.readLines()
                    if (lines.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            importing.value = false
                            progress.value = 100
                            onComplete(0, emptyList())
                        }
                        return@launch
                    }
                    // Try to detect header
                    val header =
                            parseCsvLine(lines[0]).map {
                                it.trim().lowercase().replace(Regex("\\s+"), " ")
                            }
                    val hasHeader =
                            header.any {
                                it.equals("id", true) ||
                                        it.equals("track_id", true) ||
                                        it.equals("title", true)
                            }
                    val dataLines = if (hasHeader) lines.subList(1, lines.size) else lines
                    val total = dataLines.size.coerceAtLeast(1)
                    var idx = 0
                    val searchRepo = SearchRepository()
                        for (ln in dataLines) {
                            val cols = parseCsvLine(ln)
                            var added = false
                            val map = if (header.isNotEmpty()) header.mapIndexed { i, k -> k.lowercase() to (cols.getOrNull(i) ?: "") }.toMap() else emptyMap()
                            val parsedTitle = when {
                                cols.size == 1 -> cols[0]
                                map.isNotEmpty() -> map["track name"] ?: map["title"] ?: map["name"] ?: cols.getOrNull(1) ?: cols.getOrNull(0) ?: ""
                                else -> cols.getOrNull(1) ?: cols.getOrNull(0) ?: ""
                            }
                            val parsedAlbum = if (map.isNotEmpty()) map["album name"] ?: map["album"] ?: map["album_name"] ?: "" else cols.getOrNull(2) ?: ""
                            val parsedArtist = if (map.isNotEmpty()) map["artist name(s)"] ?: map["artist"] ?: map["artist name"] ?: "" else cols.getOrNull(3) ?: ""

                            fun parseDur(s: String?): Int? {
                                if (s.isNullOrBlank()) return null
                                val t = s.trim()
                                t.toIntOrNull()?.let { return it }
                                val parts = t.split(":")
                                if (parts.size == 2) {
                                    val m = parts[0].toIntOrNull() ?: return null
                                    val sec = parts[1].toIntOrNull() ?: return null
                                    return m * 60 + sec
                                }
                                return null
                            }

                            val parsedDuration = parseDur(map["duration"] ?: map["seconds"] ?: map["durationsec"] ?: map["length"] ?: cols.getOrNull(4))

                            // build candidate duration in seconds (from ms fields)
                            fun parseDurationMsToSec(s: String?): Int? {
                                if (s.isNullOrBlank()) return null
                                val t = s.trim()
                                val n = t.toLongOrNull() ?: return null
                                return if (n >= 10000L) (n / 1000L).toInt() else n.toInt()
                            }
                            val durMsCandidates = listOf(map["duration (ms)"], map["duration"], map["milliseconds"], map["length"], cols.getOrNull(5))
                            val parsedDurationSec = durMsCandidates.mapNotNull { parseDurationMsToSec(it) }.firstOrNull()

                            val queryParts = listOf(parsedTitle, parsedAlbum, parsedArtist).map { it.trim() }.filter { it.isNotBlank() }

                            try {
                                if (queryParts.isNotEmpty()) {
                                    val qCombined = queryParts.joinToString(" ")
                                    var res = try { searchRepo.search(qCombined) } catch (_: Exception) { null }
                                    var candidates = res?.songs ?: emptyList()
                                    if (candidates.isEmpty()) {
                                        val qTitleArtist = listOf(parsedTitle, parsedArtist).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
                                        if (qTitleArtist.isNotBlank()) { res = try { searchRepo.search(qTitleArtist) } catch (_: Exception) { null }; candidates = res?.songs ?: emptyList() }
                                    }
                                    if (candidates.isEmpty()) {
                                        val qTitleAlbum = listOf(parsedTitle, parsedAlbum).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
                                        if (qTitleAlbum.isNotBlank()) { res = try { searchRepo.search(qTitleAlbum) } catch (_: Exception) { null }; candidates = res?.songs ?: emptyList() }
                                    }
                                    if (candidates.isEmpty()) {
                                        val qArtistAlbum = listOf(parsedArtist, parsedAlbum).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
                                        if (qArtistAlbum.isNotBlank()) { res = try { searchRepo.search(qArtistAlbum) } catch (_: Exception) { null }; candidates = res?.songs ?: emptyList() }
                                    }
                                    if (candidates.isEmpty()) {
                                        val qTitle = parsedTitle.trim()
                                        if (qTitle.isNotBlank()) { res = try { searchRepo.search(qTitle) } catch (_: Exception) { null }; candidates = res?.songs ?: emptyList() }
                                    }

                                    if (candidates.isNotEmpty()) {
                                        fun norm(s: String?): String {
                                            if (s.isNullOrBlank()) return ""
                                            var t = s.lowercase().trim()
                                            t = t.replace(Regex("[\\p{Punct}]"), " ")
                                            t = t.replace(Regex("\\s+"), " ")
                                            return t
                                        }
                                        val nParsedArtist = norm(parsedArtist)
                                        val nParsedAlbum = norm(parsedAlbum)
                                        val strongFiltered = if (nParsedArtist.isNotBlank() || nParsedAlbum.isNotBlank()) {
                                            val list = candidates.filter { cand ->
                                                val cArtist = norm(cand.artist)
                                                val cAlbum = norm(cand.albumName)
                                                val artistMatch = nParsedArtist.isNotBlank() && (cArtist.contains(nParsedArtist) || nParsedArtist.contains(cArtist))
                                                val albumMatch = nParsedAlbum.isNotBlank() && (cAlbum.contains(nParsedAlbum) || nParsedAlbum.contains(cAlbum))
                                                artistMatch || albumMatch
                                            }
                                            if (list.isNotEmpty()) list else candidates
                                        } else candidates

                                        val tol = 4
                                        var chosen: com.example.moniq.model.Track? = null
                                        if (parsedDurationSec != null) {
                                            for (cand in strongFiltered) {
                                                try { if (kotlin.math.abs(cand.durationSec - parsedDurationSec) <= tol) { chosen = cand; break } } catch (_: Exception) {}
                                            }
                                        }
                                        if (chosen == null) {
                                            var bestScore = Int.MIN_VALUE
                                            var best: com.example.moniq.model.Track? = null
                                            val pTitle = norm(parsedTitle)
                                            val pTitleWords = pTitle.split(" ").filter { it.isNotBlank() }
                                            for (cand in strongFiltered) {
                                                try {
                                                    var score = 0
                                                    val cTitle = norm(cand.title)
                                                    val cAlbum = norm(cand.albumName)
                                                    val cArtist = norm(cand.artist)
                                                    if (pTitle.isNotBlank() && cTitle == pTitle) score += 200
                                                    if (pTitle.isNotBlank() && cTitle.contains(pTitle)) score += 100
                                                    val wordMatches = pTitleWords.count { w -> w.isNotBlank() && cTitle.contains(w) }
                                                    score += (wordMatches * 20)
                                                    if (nParsedArtist.isNotBlank() && (cArtist.contains(nParsedArtist) || nParsedArtist.contains(cArtist))) score += 500
                                                    if (nParsedAlbum.isNotBlank() && (cAlbum.contains(nParsedAlbum) || nParsedAlbum.contains(cAlbum))) score += 400
                                                    if (parsedDurationSec != null) {
                                                        val cd = cand.durationSec
                                                        val diff = kotlin.math.abs(cd - parsedDurationSec)
                                                        if (diff <= tol) score += 150 else if (diff <= 10) score += 30
                                                    }
                                                    if (score > bestScore) { bestScore = score; best = cand }
                                                } catch (_: Exception) {}
                                            }
                                            chosen = best ?: strongFiltered.firstOrNull()
                                        }
                                        if (chosen != null) {
                                            pm.addTrack(playlistId, com.example.moniq.model.Track(chosen.id, chosen.title, chosen.artist, chosen.durationSec, albumId = chosen.albumId, albumName = chosen.albumName, coverArtId = chosen.coverArtId))
                                            report.add(ImportResult(qCombined, com.example.moniq.model.Track(chosen.id, chosen.title, chosen.artist, chosen.durationSec, albumId = chosen.albumId, albumName = chosen.albumName, coverArtId = chosen.coverArtId)))
                                            added = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {}

                            if (!added) {
                                val track = when {
                                    cols.size == 1 -> com.example.moniq.model.Track(cols[0], cols[0], "", 0)
                                    header.isNotEmpty() -> {
                                        val map2 = header.mapIndexed { i, k -> k.lowercase() to (cols.getOrNull(i) ?: "") }.toMap()
                                        val id = map2["id"] ?: map2["track_id"] ?: map2["trackid"] ?: map2["guid"] ?: map2["songid"] ?: cols.getOrNull(0) ?: ""
                                        val title = map2["title"] ?: id
                                        val artist = map2["artist"] ?: ""
                                        com.example.moniq.model.Track(id, title, artist, 0)
                                    }
                                    else -> com.example.moniq.model.Track(cols[0], cols.getOrNull(1) ?: cols[0], cols.getOrNull(2) ?: "", 0)
                                }
                                pm.addTrack(playlistId, track)
                                report.add(ImportResult(parsedTitle.ifBlank { cols.getOrNull(0) ?: "" }, null))
                            }
                            imported++
                            idx++
                            progress.postValue((idx * 100) / total)
                        }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                importing.value = false
                progress.value = 100
                onComplete(imported, report)
            }
        }
    }

    // Prepare a list of queries (query string, parsedDurationSec) from the CSV file for manual
    // interactive import
    fun prepareSearchList(uri: Uri): List<Pair<String, Int?>> {
        val out = mutableListOf<Pair<String, Int?>>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val reader = BufferedReader(InputStreamReader(ins))
                val lines = reader.readLines()
                if (lines.isEmpty()) return out
                val header =
                        parseCsvLine(lines[0]).map {
                            it.trim().lowercase().replace(Regex("\\s+"), " ")
                        }
                val hasHeader =
                        header.any {
                            it.equals("id", true) ||
                                    it.equals("track_id", true) ||
                                    it.equals("title", true)
                        }
                val dataLines = if (hasHeader) lines.subList(1, lines.size) else lines
                for (ln in dataLines) {
                    val cols = parseCsvLine(ln)
                    val map =
                            if (header.isNotEmpty())
                                    header
                                            .mapIndexed { i, k ->
                                                k.lowercase() to (cols.getOrNull(i) ?: "")
                                            }
                                            .toMap()
                            else emptyMap()
                    val parsedTitle =
                            when {
                                cols.size == 1 -> cols[0]
                                map.isNotEmpty() -> map["track name"]
                                                ?: map["title"] ?: map["name"] ?: cols.getOrNull(1)
                                                        ?: cols.getOrNull(0) ?: ""
                                else -> cols.getOrNull(1) ?: cols.getOrNull(0) ?: ""
                            }
                    val parsedAlbum =
                            if (map.isNotEmpty())
                                    map["album name"] ?: map["album"] ?: map["album_name"] ?: ""
                            else cols.getOrNull(2) ?: ""
                    val parsedArtist =
                            if (map.isNotEmpty())
                                    map["artist name(s)"]
                                            ?: map["artist"] ?: map["artist name"] ?: ""
                            else cols.getOrNull(3) ?: ""

                    fun parseDurationMsToSec(s: String?): Int? {
                        if (s.isNullOrBlank()) return null
                        val t = s.trim()
                        val n = t.toLongOrNull() ?: return null
                        return if (n >= 10000L) (n / 1000L).toInt() else n.toInt()
                    }
                    val durMsCandidates =
        listOf(
                map["duration (ms)"],
                map["duration"],
                map["milliseconds"],
                map["length"],
                cols.getOrNull(5)
        )
                    val parsedDurationSec =
                            durMsCandidates.mapNotNull { parseDurationMsToSec(it) }.firstOrNull()

                    // Prefer explicit title+album+artist combined query
                    val combined =
                            listOf(parsedTitle, parsedAlbum, parsedArtist)
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                    if (combined.isNotBlank()) {
                        out.add(Pair(combined, parsedDurationSec))
                    } else {
                        // fallback: join all non-empty columns into a query
                        val fallback =
                                cols.map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
                        if (fallback.isNotBlank()) out.add(Pair(fallback, parsedDurationSec))
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                '"' -> {
                    inQuotes = !inQuotes
                }
                ',' -> {
                    if (inQuotes) cur.append(c)
                    else {
                        out.add(cur.toString().trim())
                        cur = StringBuilder()
                    }
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }
}
