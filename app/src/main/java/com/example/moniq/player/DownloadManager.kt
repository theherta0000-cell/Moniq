package com.example.moniq.player

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import com.mpatric.mp3agic.Mp3File
import com.mpatric.mp3agic.ID3v24Tag

object DownloadManager {
    private val client = OkHttpClient()

    private fun sanitizeFilename(s: String): String = s.replace(Regex("[\\/:*?\"<>|]"), "_").trim()

    suspend fun downloadTrack(
        context: Context,
        trackId: String,
        title: String?,
        artist: String?,
        album: String? = null,
        albumArtUrl: String? = null,
        trackNumber: Int? = null,
        year: String? = null,
        genre: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val host = com.example.moniq.SessionManager.host ?: return@withContext false
            val username = com.example.moniq.SessionManager.username ?: ""
            val passwordRaw = com.example.moniq.SessionManager.password ?: ""
            val legacy = com.example.moniq.SessionManager.legacy
            val pwParam = if (legacy) passwordRaw else com.example.moniq.util.Crypto.md5(passwordRaw)

            var base = host.trim()
            if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
            if (!base.endsWith("/")) base += "/"

            val qU = java.net.URLEncoder.encode(username, "UTF-8")
            val qP = java.net.URLEncoder.encode(pwParam, "UTF-8")
            val qId = java.net.URLEncoder.encode(trackId, "UTF-8")
            val streamUrl = "${base}rest/stream.view?u=$qU&p=$qP&id=$qId&v=1.16.1&c=Moniq"

            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            if (!musicDir.exists()) musicDir.mkdirs()

            val baseName = sanitizeFilename((artist ?: "unknown") + " - " + (title ?: trackId))
            val tempFile = File(musicDir, "$baseName.tmp")
            var finalExt = ".mp3"

            val req = Request.Builder().url(streamUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("DownloadManager", "Stream request failed: ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false

                val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                finalExt = when {
                    contentType.contains("mpeg") || contentType.contains("mp3") -> ".mp3"
                    contentType.contains("flac") -> ".flac"
                    contentType.contains("ogg") || contentType.contains("vorbis") || contentType.contains("opus") -> ".ogg"
                    contentType.contains("wav") || contentType.contains("wave") -> ".wav"
                    else -> {
                        val path = resp.request.url.encodedPath
                        val idx = path.lastIndexOf('.')
                        if (idx >= 0 && idx < path.length - 1) path.substring(idx) else ".bin"
                    }
                }

                val inputStream: InputStream = body.byteStream()
                FileOutputStream(tempFile).use { fos ->
                    val buf = ByteArray(8 * 1024)
                    var r: Int
                    while (inputStream.read(buf).also { r = it } != -1) {
                        fos.write(buf, 0, r)
                    }
                    fos.flush()
                }
            }

            var artBytes: ByteArray? = null
            if (!albumArtUrl.isNullOrEmpty()) {
                try {
                    val u = URL(albumArtUrl)
                    u.openStream().use { artBytes = it.readBytes() }
                } catch (e: Exception) {
                    Log.w("DownloadManager", "Failed to download album art: ${e.message}")
                    artBytes = null
                }
            }

            val outFile = File(musicDir, "$baseName$finalExt")

            if (finalExt == ".mp3") {
                try {
                    val mp3 = Mp3File(tempFile.absolutePath)
                    val tag = ID3v24Tag()
                    if (!title.isNullOrEmpty()) tag.title = title
                    if (!artist.isNullOrEmpty()) tag.artist = artist
                    if (!album.isNullOrEmpty()) tag.album = album
                    if (trackNumber != null) tag.track = trackNumber.toString()
                    if (!year.isNullOrEmpty()) tag.year = year
                    if (!genre.isNullOrEmpty()) tag.genreDescription = genre
                    if (artBytes != null) tag.setAlbumImage(artBytes, "image/jpeg")
                    mp3.id3v2Tag = tag
                    val tmpOut = File(musicDir, "$baseName.out.mp3")
                    mp3.save(tmpOut.absolutePath)
                    if (outFile.exists()) outFile.delete()
                    tmpOut.renameTo(outFile)
                    tempFile.delete()
                    return@withContext true
                } catch (e: Exception) {
                    Log.w("DownloadManager", "MP3 tagging failed: ${e.message}")
                    try {
                        tempFile.copyTo(outFile, overwrite = true)
                        tempFile.delete()
                        return@withContext true
                    } catch (e2: Exception) {
                        Log.e("DownloadManager", "Fallback copy failed: ${e2.message}")
                        return@withContext false
                    }
                }
            } else {
                try {
                    if (outFile.exists()) outFile.delete()
                    tempFile.renameTo(outFile)
                    return@withContext true
                } catch (e: Exception) {
                    Log.e("DownloadManager", "Saving file failed: ${e.message}")
                    try {
                        tempFile.copyTo(outFile, overwrite = true)
                        tempFile.delete()
                        return@withContext true
                    } catch (e2: Exception) {
                        Log.e("DownloadManager", "Fallback copy failed: ${e2.message}")
                        return@withContext false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadManager", "download failed: ${e.message}")
            return@withContext false
        }
    }

    suspend fun downloadTrackWithProgress(
        context: Context,
        trackId: String,
        title: String?,
        artist: String?,
        album: String? = null,
        albumArtUrl: String? = null,
        progressCb: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val host = com.example.moniq.SessionManager.host ?: return@withContext false
            val username = com.example.moniq.SessionManager.username ?: ""
            val passwordRaw = com.example.moniq.SessionManager.password ?: ""
            val legacy = com.example.moniq.SessionManager.legacy
            val pwParam = if (legacy) passwordRaw else com.example.moniq.util.Crypto.md5(passwordRaw)

            var base = host.trim()
            if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
            if (!base.endsWith("/")) base += "/"

            val qU = java.net.URLEncoder.encode(username, "UTF-8")
            val qP = java.net.URLEncoder.encode(pwParam, "UTF-8")
            val qId = java.net.URLEncoder.encode(trackId, "UTF-8")
            val streamUrl = "${base}rest/stream.view?u=$qU&p=$qP&id=$qId&v=1.16.1&c=Moniq"

            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            if (!musicDir.exists()) musicDir.mkdirs()

            val baseName = sanitizeFilename((artist ?: "unknown") + " - " + (title ?: trackId))
            val tempFile = File(musicDir, "$baseName.tmp")
            var finalExt = ".mp3"

            val req = Request.Builder().url(streamUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("DownloadManager", "Stream request failed: ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val contentLength = body.contentLength()
                val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                finalExt = when {
                    contentType.contains("mpeg") || contentType.contains("mp3") -> ".mp3"
                    contentType.contains("flac") -> ".flac"
                    contentType.contains("ogg") || contentType.contains("vorbis") || contentType.contains("opus") -> ".ogg"
                    contentType.contains("wav") || contentType.contains("wave") -> ".wav"
                    else -> {
                        val path = resp.request.url.encodedPath
                        val idx = path.lastIndexOf('.')
                        if (idx >= 0 && idx < path.length - 1) path.substring(idx) else ".bin"
                    }
                }

                val inputStream = body.byteStream()
                FileOutputStream(tempFile).use { fos ->
                    val buf = ByteArray(8 * 1024)
                    var totalRead = 0L
                    var r: Int
                    while (inputStream.read(buf).also { r = it } != -1) {
                        fos.write(buf, 0, r)
                        totalRead += r
                        if (contentLength > 0) {
                            val pct = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                            try { progressCb(pct) } catch (_: Exception) {}
                        } else {
                            try { progressCb(-1) } catch (_: Exception) {}
                        }
                    }
                    fos.flush()
                }
            }

            var artBytes: ByteArray? = null
            if (!albumArtUrl.isNullOrEmpty()) {
                try {
                    val u = URL(albumArtUrl)
                    u.openStream().use { artBytes = it.readBytes() }
                } catch (e: Exception) {
                    Log.w("DownloadManager", "Failed to download album art: ${e.message}")
                    artBytes = null
                }
            }

            val outFile = File(musicDir, "$baseName$finalExt")

            if (finalExt == ".mp3") {
                try {
                    val mp3 = Mp3File(tempFile.absolutePath)
                    val tag = ID3v24Tag()
                    if (!title.isNullOrEmpty()) tag.title = title
                    if (!artist.isNullOrEmpty()) tag.artist = artist
                    if (!album.isNullOrEmpty()) tag.album = album
                    if (artBytes != null) tag.setAlbumImage(artBytes, "image/jpeg")
                    mp3.id3v2Tag = tag
                    val tmpOut = File(musicDir, "$baseName.out.mp3")
                    mp3.save(tmpOut.absolutePath)
                    if (outFile.exists()) outFile.delete()
                    tmpOut.renameTo(outFile)
                    tempFile.delete()
                    progressCb(100)
                    return@withContext true
                } catch (e: Exception) {
                    Log.w("DownloadManager", "MP3 tagging failed: ${e.message}")
                    try {
                        tempFile.copyTo(outFile, overwrite = true)
                        tempFile.delete()
                        progressCb(100)
                        return@withContext true
                    } catch (e2: Exception) {
                        Log.e("DownloadManager", "Fallback copy failed: ${e2.message}")
                        return@withContext false
                    }
                }
            } else {
                try {
                    if (outFile.exists()) outFile.delete()
                    tempFile.renameTo(outFile)
                    progressCb(100)
                    return@withContext true
                } catch (e: Exception) {
                    Log.e("DownloadManager", "Saving file failed: ${e.message}")
                    try {
                        tempFile.copyTo(outFile, overwrite = true)
                        tempFile.delete()
                        progressCb(100)
                        return@withContext true
                    } catch (e2: Exception) {
                        Log.e("DownloadManager", "Fallback copy failed: ${e2.message}")
                        return@withContext false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadManager", "download failed: ${e.message}")
            return@withContext false
        }
    }

    suspend fun downloadAlbum(context: Context, tracks: List<TrackInfo>): List<Pair<TrackInfo, Boolean>> {
        val results = mutableListOf<Pair<TrackInfo, Boolean>>()
        for ((idx, t) in tracks.withIndex()) {
            val ok = downloadTrack(context, t.id, t.title, t.artist, t.album, t.albumArtUrl, trackNumber = idx + 1)
            results.add(t to ok)
        }
        return results
    }

    data class TrackInfo(
        val id: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtUrl: String?
    )
}
