package com.example.moniq.player

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.AndroidArtwork
import org.json.JSONObject
import com.example.moniq.util.ServerManager
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
import android.media.MediaCodec
import java.nio.ByteBuffer
import com.example.moniq.util.ImageUrlHelper
import com.example.moniq.SessionStore

object DownloadManager {
    private fun getDownloadDirectory(context: Context): File {
    // Try to get user's custom download directory
    val customUri = SessionStore.loadDownloadDirectory(context)
    
    if (customUri != null) {
        try {
            val uri = android.net.Uri.parse(customUri)
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            
            if (docFile != null && docFile.exists() && docFile.canWrite()) {
                // Create a Music subfolder in the user's chosen directory
                val musicFolder = docFile.findFile("Music") ?: docFile.createDirectory("Music")
                
                if (musicFolder != null) {
                    // Convert to File path if possible
                    val path = musicFolder.uri.path
                    if (path != null && path.startsWith("/tree/primary:")) {
                        val actualPath = path.replace("/tree/primary:", "/storage/emulated/0/")
                        val file = File(actualPath)
                        if (file.exists() && file.canWrite()) {
                            Log.d("DownloadManager", "Using custom download directory: ${file.absolutePath}")
                            return file
                        }
                    }
                    
                    // If we can't convert to File, we'll need to use SAF later
                    Log.d("DownloadManager", "Custom directory requires SAF: ${musicFolder.uri}")
                }
            }
        } catch (e: Exception) {
            Log.w("DownloadManager", "Failed to use custom directory: ${e.message}")
        }
    }
    
    // Fallback to default app directory
    val defaultDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir
    Log.d("DownloadManager", "Using default download directory: ${defaultDir.absolutePath}")
    return defaultDir
}
    
    private val client = OkHttpClient()

    private fun sanitizeFilename(s: String): String = s.replace(Regex("[\\/:*?\"<>|]"), "_").trim()

    private suspend fun findWorkingStreamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
    // Use ServerManager to get ordered servers (most reliable first)
    val orderedServers = ServerManager.getOrderedServers()
    
    for (server in orderedServers) {
        val testUrl = "$server/track/?id=$trackId"
        try {
            Log.d("DownloadManager", "Trying server: $server")
            val conn = java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                val streamUrl = "$server/stream/?id=$trackId"
                Log.d("DownloadManager", "Found working server: $server")
                // Record success in ServerManager
                ServerManager.recordSuccess(server)
                return@withContext streamUrl
            } else {
                // Record failure for non-200 responses
                ServerManager.recordFailure(server)
            }
        } catch (e: Exception) {
            Log.d("DownloadManager", "Server $server failed: ${e.message}")
            // Record failure in ServerManager
            ServerManager.recordFailure(server)
            continue
        }
    }
    
    Log.e("DownloadManager", "No working servers found for track $trackId")
    return@withContext null
}

    private fun embedFlacArtworkManually(flacFile: File, artBytes: ByteArray): Boolean {
    try {
        Log.d("DownloadManager", "Manually embedding artwork in FLAC file...")
        
        // Read the entire FLAC file
        val fileBytes = flacFile.readBytes()
        
        // Verify it's a FLAC file (starts with "fLaC")
        if (fileBytes.size < 4 || 
            fileBytes[0] != 'f'.code.toByte() || 
            fileBytes[1] != 'L'.code.toByte() || 
            fileBytes[2] != 'a'.code.toByte() || 
            fileBytes[3] != 'C'.code.toByte()) {
            Log.e("DownloadManager", "Not a valid FLAC file")
            return false
        }
        
        // Find where to insert the PICTURE block (after the last metadata block before audio)
        var pos = 4 // Skip "fLaC" marker
        var lastMetadataBlockEnd = pos
        var foundLastBlock = false
        
        while (pos < fileBytes.size && !foundLastBlock) {
            val blockHeader = fileBytes[pos].toInt() and 0xFF
            val isLastBlock = (blockHeader and 0x80) != 0
            val blockType = blockHeader and 0x7F
            
            // Read block size (24-bit big-endian)
            val blockSize = ((fileBytes[pos + 1].toInt() and 0xFF) shl 16) or
                           ((fileBytes[pos + 2].toInt() and 0xFF) shl 8) or
                           (fileBytes[pos + 3].toInt() and 0xFF)
            
            pos += 4 // Skip header
            
            // If this is a PICTURE block (type 6), remove it
            if (blockType == 6) {
                Log.d("DownloadManager", "Found existing PICTURE block at position ${pos - 4}, will replace it")
                // We'll skip this block and not include it in our output
            } else {
                lastMetadataBlockEnd = pos + blockSize
            }
            
            pos += blockSize
            
            if (isLastBlock) {
                foundLastBlock = true
                // Update the previous block to not be the last block anymore
                if (blockType != 6) {
                    lastMetadataBlockEnd = pos
                }
            }
        }
        
        // Create PICTURE metadata block
        val pictureBlock = createFlacPictureBlock(artBytes)
        
        // Build new FLAC file
        val output = java.io.ByteArrayOutputStream()
        
        // Write original data up to insertion point
        output.write(fileBytes, 0, lastMetadataBlockEnd)
        
        // If the previous block was marked as last, unmark it
        if (lastMetadataBlockEnd >= 4) {
            val prevBlockHeaderPos = lastMetadataBlockEnd - (
                ((fileBytes[lastMetadataBlockEnd - 3].toInt() and 0xFF) shl 16) or
                ((fileBytes[lastMetadataBlockEnd - 2].toInt() and 0xFF) shl 8) or
                (fileBytes[lastMetadataBlockEnd - 1].toInt() and 0xFF)
            ) - 4
            
            if (prevBlockHeaderPos >= 4 && prevBlockHeaderPos < lastMetadataBlockEnd) {
                val headerByte = fileBytes[prevBlockHeaderPos].toInt() and 0xFF
                if ((headerByte and 0x80) != 0) {
                    // Clear the "last block" bit
                    output.toByteArray()[prevBlockHeaderPos] = (headerByte and 0x7F).toByte()
                }
            }
        }
        
        // Write PICTURE block (marked as last block)
        output.write(pictureBlock)
        
        // Write remaining audio data
        output.write(fileBytes, lastMetadataBlockEnd, fileBytes.size - lastMetadataBlockEnd)
        
        // Write back to file
        flacFile.writeBytes(output.toByteArray())
        
        Log.d("DownloadManager", "✓ Successfully embedded artwork manually (${artBytes.size} bytes)")
        return true
        
    } catch (e: Exception) {
        Log.e("DownloadManager", "Failed to manually embed artwork: ${e.message}", e)
        return false
    }
}

private fun createFlacPictureBlock(imageData: ByteArray): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    
    // Picture type (3 = Cover (front))
    output.write(byteArrayOf(0x00, 0x00, 0x00, 0x03))
    
    // MIME type
    val mimeType = "image/jpeg"
    val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
    output.write(byteArrayOf(
        0x00,
        0x00,
        0x00,
        mimeBytes.size.toByte()
    ))
    output.write(mimeBytes)
    
    // Description (empty)
    output.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
    
    // Width (0 = unknown)
    output.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
    
    // Height (0 = unknown)
    output.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
    
    // Color depth (0 = unknown)
    output.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
    
    // Number of colors (0 = not indexed)
    output.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
    
    // Picture data length (32-bit big-endian)
    val dataLength = imageData.size
    output.write(byteArrayOf(
        ((dataLength shr 24) and 0xFF).toByte(),
        ((dataLength shr 16) and 0xFF).toByte(),
        ((dataLength shr 8) and 0xFF).toByte(),
        (dataLength and 0xFF).toByte()
    ))
    
    // Picture data
    output.write(imageData)
    
    val pictureData = output.toByteArray()
    
    // Create block header
    val blockHeader = java.io.ByteArrayOutputStream()
    
    // Block type 6 (PICTURE) with "last block" flag (0x86)
    blockHeader.write(0x86)
    
    // Block length (24-bit big-endian)
    val blockLength = pictureData.size
    blockHeader.write(((blockLength shr 16) and 0xFF).toByte().toInt())
    blockHeader.write(((blockLength shr 8) and 0xFF).toByte().toInt())
    blockHeader.write((blockLength and 0xFF).toByte().toInt())
    
    // Combine header and data
    blockHeader.write(pictureData)
    
    return blockHeader.toByteArray()
}
    
    private suspend fun resolveDownloadUrl(trackId: String, streamUrl: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    val extractedId =
                            streamUrl.substringAfter("id=").substringBefore("&").takeIf {
                                it.isNotBlank()
                            }
                                    ?: trackId

                    val server =
                            if (streamUrl.contains("://")) {
                                val base = streamUrl.substringBefore("/stream/")
                                Log.d(
                                        "DownloadManager",
                                        "Extracted server: $base from streamUrl: $streamUrl"
                                )
                                base
                            } else {
                                Log.d("DownloadManager", "Using default server")
                                "https://hund.qqdl.site"
                            }

                    val trackInfoUrl = "$server/track/?id=$extractedId"
                    Log.d("DownloadManager", "Fetching track info from: $trackInfoUrl")

                    val conn =
                            java.net.URL(trackInfoUrl).openConnection() as
                                    java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.requestMethod = "GET"

                    val responseCode = conn.responseCode
Log.d("DownloadManager", "Track info response code: $responseCode")

if (responseCode == 200) {
    // Record success for this server
    ServerManager.recordSuccess(server)
    val response = conn.inputStream.bufferedReader().use { it.readText() }

                        val jsonObj = JSONObject(response)
                        val data = jsonObj.getJSONObject("data")
                        val manifestMimeType = data.getString("manifestMimeType")
                        val manifest = data.getString("manifest")

                        Log.d("DownloadManager", "Manifest type: $manifestMimeType")

                        when (manifestMimeType) {
                            "application/dash+xml" -> {
                                val manifestXml = String(Base64.decode(manifest, Base64.DEFAULT))
                                Log.d("DownloadManager", "DASH manifest decoded")

                                // Look for BaseURL (single complete file)
                                val baseUrlPattern = """<BaseURL>([^<]+)</BaseURL>""".toRegex()
                                val baseUrlMatch = baseUrlPattern.find(manifestXml)

                                if (baseUrlMatch != null) {
                                    val url = baseUrlMatch.groupValues[1].replace("&amp;", "&")
                                    Log.d("DownloadManager", "Found BaseURL (complete file): $url")
                                    return@withContext url
                                }

                                // Check for segmented DASH with $Number$
                                if (manifestXml.contains("\$Number\$")) {
                                    Log.w("DownloadManager", "This is a segmented HI-RES track")

                                    // Extract initialization URL and media template
                                    val initPattern = """initialization="([^"]+)"""".toRegex()
                                    val mediaPattern = """media="([^"]+)"""".toRegex()
                                    val startNumPattern = """startNumber="(\d+)"""".toRegex()

                                    val initMatch = initPattern.find(manifestXml)
                                    val mediaMatch = mediaPattern.find(manifestXml)
                                    val startNum =
                                            startNumPattern
                                                    .find(manifestXml)
                                                    ?.groupValues
                                                    ?.get(1)
                                                    ?.toIntOrNull()
                                                    ?: 1

                                    if (initMatch != null && mediaMatch != null) {
                                        val initUrl = initMatch.groupValues[1].replace("&amp;", "&")
                                        val mediaTemplate =
                                                mediaMatch.groupValues[1].replace("&amp;", "&")

                                        // Count segments from SegmentTimeline properly (handle r
                                        // repeat and r = -1)
                                        var segmentCount = 0
                                        var hasOpenEndedRepeat = false
                                        val sPattern = """<S\s+([^/>]+)\/?>""".toRegex()

                                        for (m in sPattern.findAll(manifestXml)) {
                                            val attrs = m.groupValues[1]
                                            val r =
                                                    """r="(-?\d+)""""
                                                            .toRegex()
                                                            .find(attrs)
                                                            ?.groupValues
                                                            ?.get(1)
                                                            ?.toIntOrNull()
                                            if (r != null) {
                                                if (r == -1) {
                                                    // open-ended repeat: we don't know total count
                                                    // here
                                                    hasOpenEndedRepeat = true
                                                } else {
                                                    // r means "repeat count of additional S
                                                    // elements", so total occurrences = r + 1
                                                    segmentCount += (r + 1)
                                                }
                                            } else {
                                                // single occurrence with no repeat
                                                segmentCount += 1
                                            }
                                        }

                                        // If nothing found, fallback to the old crude count
                                        if (segmentCount == 0 && !hasOpenEndedRepeat) {
                                            segmentCount = manifestXml.split("<S ").size - 1
                                        }

                                        // If we detected an open-ended repeat, mark with -1 so
                                        // downloader will probe until failure
                                        if (hasOpenEndedRepeat) segmentCount = -1

                                        Log.d("DownloadManager", "Found $segmentCount segments")
                                        Log.d("DownloadManager", "Init URL: $initUrl")
                                        Log.d("DownloadManager", "Media template: $mediaTemplate")

                                        // Return special marker for segmented download
                                        return@withContext "SEGMENTED:$initUrl|$mediaTemplate|$startNum|$segmentCount"
                                    }
                                }

                                Log.e("DownloadManager", "Could not parse DASH manifest")
                            }
                            "application/vnd.tidal.bts" -> {
                                val decoded = String(Base64.decode(manifest, Base64.DEFAULT))
                                val btsJson = JSONObject(decoded)
                                val urls = btsJson.optJSONArray("urls")
                                if (urls != null && urls.length() > 0) {
                                    val url = urls.getString(0)
                                    Log.d("DownloadManager", "Found BTS URL: $url")
                                    return@withContext url
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
    Log.e("DownloadManager", "Error resolving download URL", e)
    // Try to extract server from streamUrl and record failure
    try {
        val server = streamUrl.substringBefore("/stream/")
        if (server.startsWith("http")) {
            ServerManager.recordFailure(server)
        }
    } catch (_: Exception) {}
}

                return@withContext null
            }

    private suspend fun downloadSegmentedTrack(
            context: Context,
            segmentInfo: String,
            baseName: String,
            progressCb: ((Int) -> Unit)? = null
    ): File? =
            withContext(Dispatchers.IO) {
                try {
                    val parts = segmentInfo.substringAfter("SEGMENTED:").split("|")
                    val initUrl = parts[0]
                    val mediaTemplate = parts[1]
                    val startNum = parts[2].toInt()
                    val segmentCountRaw = parts[3].toIntOrNull() ?: -1
                    val hasUnknownCount = segmentCountRaw <= 0
                    val segmentCount = if (hasUnknownCount) Int.MAX_VALUE else segmentCountRaw

                    Log.d(
                            "DownloadManager",
                            "Downloading segmented track (start=$startNum, count=$segmentCountRaw)..."
                    )

                    val musicDir = getDownloadDirectory(context)
                    val tempFile = File(musicDir, "$baseName.tmp")

                    val MAX_PROBE_SEGMENTS = 2000 // safety cap to avoid infinite loops
                    var downloadedSegments = 0

                    FileOutputStream(tempFile).use { output ->
                        // Download initialization segment (if present)
                        Log.d("DownloadManager", "Downloading init segment...")
                        val initReq = Request.Builder().url(initUrl).build()
                        client.newCall(initReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.byteStream()?.copyTo(output)
                                progressCb?.invoke(5)
                            } else {
                                Log.w("DownloadManager", "Init segment failed: ${resp.code}")
                                // continue - some manifests might not require init but usually they
                                // do
                            }
                        }

                        // Download segments: either a fixed count or probe until non-200 (with
                        // cap).
                        var i = startNum
                        var lastProgress = 5
                        while (i < startNum + segmentCount &&
                                downloadedSegments < MAX_PROBE_SEGMENTS) {
                            val segmentUrl = mediaTemplate.replace("\$Number\$", i.toString())
                            Log.d("DownloadManager", "Downloading segment $i ...")
                            val req = Request.Builder().url(segmentUrl).build()

                            client.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) {
                                    Log.w(
                                            "DownloadManager",
                                            "Segment $i failed with code ${resp.code}; stopping probe."
                                    )
                                    // if we were probing unknown count, break on first non-200
                                    if (hasUnknownCount) {
                                        // stop probing further segments
                                        i = startNum + segmentCount // break outer loop
                                    } else {
                                        // for fixed-count manifests, just log (we'll still try
                                        // remaining ones)
                                    }
                                } else {
                                    resp.body?.byteStream()?.copyTo(output)
                                    downloadedSegments++
                                    // update progress roughly: init=5%, segments cover 95%
                                    val divisor = if (hasUnknownCount) MAX_PROBE_SEGMENTS else segmentCountRaw
val progress = (5 + (downloadedSegments * 95 / divisor)).coerceIn(5, 100)
                                    if (progress != lastProgress) {
                                        progressCb?.invoke(progress)
                                        lastProgress = progress
                                    }
                                }
                            }

                            // advance index
                            i++
                        }
                    }

                    Log.d(
                            "DownloadManager",
                            "All segments downloaded successfully (count=$downloadedSegments)"
                    )
                    return@withContext tempFile
                } catch (e: Exception) {
                    Log.e("DownloadManager", "Segmented download failed", e)
                    return@withContext null
                }
            }

    suspend fun downloadTrack(
            context: Context,
            trackId: String,
            title: String?,
            artist: String?,
            album: String? = null,
            albumArtUrl: String? = null,
            trackNumber: Int? = null,
            year: String? = null,
            genre: String? = null,
            streamUrl: String? = null
    ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    Log.d("DownloadManager", "=== DOWNLOAD START ===")
                    Log.d("DownloadManager", "Track ID: $trackId")
                    Log.d("DownloadManager", "Title: $title")
                    Log.d("DownloadManager", "Artist: $artist")
                    Log.d("DownloadManager", "StreamURL: $streamUrl")

                    // If no streamUrl provided, find a working server
                    val effectiveStreamUrl =
                            if (streamUrl.isNullOrBlank() || !streamUrl.contains("/stream/?id=")) {
                                Log.d(
                                        "DownloadManager",
                                        "No valid streamUrl provided, searching for working server..."
                                )
                                findWorkingStreamUrl(trackId)
                                        ?: run {
                                            Log.e(
                                                    "DownloadManager",
                                                    "Could not find working server for track $trackId"
                                            )
                                            return@withContext false
                                        }
                            } else {
                                streamUrl
                            }

                    Log.d("DownloadManager", "Using streamUrl: $effectiveStreamUrl")

                    val downloadUrl =
                            resolveDownloadUrl(trackId, effectiveStreamUrl)
                                    ?: run {
                                        Log.e("DownloadManager", "Failed to resolve download URL")
                                        return@withContext false
                                    }

                    val musicDir = getDownloadDirectory(context)
                    if (!musicDir.exists()) musicDir.mkdirs()

                    val baseName =
                            sanitizeFilename((artist ?: "unknown") + " - " + (title ?: trackId))
                    val tempFile = File(musicDir, "$baseName.tmp")
                    var finalExt = ".mp3"

                    val req = Request.Builder().url(downloadUrl).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.e("DownloadManager", "Download request failed: ${resp.code}")
                            return@withContext false
                        }
                        val body = resp.body ?: return@withContext false

                        val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                        finalExt =
                                when {
                                    contentType.contains("mpeg") || contentType.contains("mp3") ->
                                            ".mp3"
                                    contentType.contains("flac") -> ".flac"
                                    contentType.contains("ogg") ||
                                            contentType.contains("vorbis") ||
                                            contentType.contains("opus") -> ".ogg"
                                    contentType.contains("wav") || contentType.contains("wave") ->
                                            ".wav"
                                    contentType.contains("mp4") || contentType.contains("m4a") ->
                                            ".m4a"
                                    else -> {
                                        val path = resp.request.url.encodedPath
                                        when {
                                            path.endsWith(".flac", ignoreCase = true) -> ".flac"
                                            path.endsWith(".mp3", ignoreCase = true) -> ".mp3"
                                            path.endsWith(".m4a", ignoreCase = true) -> ".m4a"
                                            path.endsWith(".mp4", ignoreCase = true) -> ".m4a"
                                            else -> ".mp3"
                                        }
                                    }
                                }

                        Log.d("DownloadManager", "Downloading as: $finalExt")

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
        // Convert cover ID to full URL using ImageUrlHelper
        val coverUrl = ImageUrlHelper.getCoverArtUrl(albumArtUrl)
        val u = URL(coverUrl)
        u.openStream().use { artBytes = it.readBytes() }
        Log.d("DownloadManager", "✓ Album art downloaded successfully (${artBytes?.size} bytes)")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to download album art: ${e.message}")
        artBytes = null
    }
}

                    val outFile = File(musicDir, "$baseName$finalExt")

                    return@withContext when (finalExt) {
                        ".mp3" ->
                                tagMp3File(
                                        tempFile,
                                        outFile,
                                        title,
                                        artist,
                                        album,
                                        trackNumber,
                                        year,
                                        genre,
                                        artBytes
                                )
                        ".flac", ".m4a", ".ogg" ->
                                tagFlacOrM4aFile(
                                        tempFile,
                                        outFile,
                                        title,
                                        artist,
                                        album,
                                        trackNumber,
                                        year,
                                        genre,
                                        artBytes
                                )
                        else -> {
                            try {
                                if (outFile.exists()) outFile.delete()
                                tempFile.renameTo(outFile) ||
                                        tempFile.copyTo(outFile, overwrite = true).let { true }
                            } catch (e: Exception) {
                                Log.e("DownloadManager", "Saving file failed: ${e.message}")
                                false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DownloadManager", "download failed: ${e.message}", e)
                    return@withContext false
                }
            }

    private fun tagMp3File(
            tempFile: File,
            outFile: File,
            title: String?,
            artist: String?,
            album: String?,
            trackNumber: Int?,
            year: String?,
            genre: String?,
            artBytes: ByteArray?
    ): Boolean {
        return try {
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
            val tmpOut = File(tempFile.parent, tempFile.nameWithoutExtension + ".out.mp3")
            mp3.save(tmpOut.absolutePath)
            if (outFile.exists()) outFile.delete()
            tmpOut.renameTo(outFile)
            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.w("DownloadManager", "MP3 tagging failed: ${e.message}")
            try {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
                true
            } catch (e2: Exception) {
                Log.e("DownloadManager", "Fallback copy failed: ${e2.message}")
                false
            }
        }
    }

    private fun tagFlacOrM4aFile(
    tempFile: File,
    outFile: File,
    title: String?,
    artist: String?,
    album: String?,
    trackNumber: Int?,
    year: String?,
    genre: String?,
    artBytes: ByteArray?,
    bpm: Int? = null,          // NEW
    isrc: String? = null,      // NEW
    copyright: String? = null, // NEW
    replayGain: Double? = null // NEW
): Boolean {
    try {
        // extension we want for the tagger (e.g. "m4a" or "flac")
        val ext = outFile.extension.ifEmpty { "m4a" }
        val tempWithExt = File(tempFile.parent, "${tempFile.nameWithoutExtension}.tagging.$ext")

        Log.d("DownloadManager", "Starting tag process for: $ext file")
        
        // Create a copy with the right extension so we don't lose the original .tmp
        try {
            tempFile.copyTo(tempWithExt, overwrite = true)
            Log.d("DownloadManager", "Copied to tempWithExt: ${tempWithExt.name} (${tempWithExt.length()} bytes)")
        } catch (e: Exception) {
            Log.w("DownloadManager", "Failed to copy temp -> tempWithExt: ${e.message}")
        }

        // If copy failed, try to work with original tempFile
        if (!tempWithExt.exists() && !tempFile.exists()) {
            Log.e("DownloadManager", "No source file to tag: ${tempWithExt.absolutePath} or ${tempFile.absolutePath}")
            return false
        }

        val fileToTag = if (tempWithExt.exists()) tempWithExt else tempFile

        Log.d("DownloadManager", "Tagging file: ${fileToTag.absolutePath} (${fileToTag.length()} bytes)")

        // First verify the file is readable before attempting full parse
        if (fileToTag.length() == 0L) {
            Log.e("DownloadManager", "File is empty, cannot tag")
            return false
        }
        
        // Try to read and tag the file - but catch NullPointerException specifically
        Log.d("DownloadManager", "Attempting to read audio file with jaudiotagger...")
        val audioFile = try {
            AudioFileIO.read(fileToTag)
        } catch (npe: NullPointerException) {
            Log.w("DownloadManager", "NPE when reading file (incomplete headers): ${npe.message}")
            Log.w("DownloadManager", "Stack trace:", npe)
            // File downloaded successfully but can't be tagged - save it anyway
            try {
                if (outFile.exists()) outFile.delete()
                
                // Copy the file that exists
                if (fileToTag.exists()) {
                    fileToTag.copyTo(outFile, overwrite = true)
                } else if (tempFile.exists()) {
                    tempFile.copyTo(outFile, overwrite = true)
                } else {
                    Log.e("DownloadManager", "No file exists to save!")
                    return false
                }
                
                // Clean up temp files
                try {
                    if (tempWithExt.exists() && tempWithExt.absolutePath != outFile.absolutePath) tempWithExt.delete()
                    if (tempFile.exists() && tempFile.absolutePath != outFile.absolutePath) tempFile.delete()
                } catch (e: Exception) {
                    Log.w("DownloadManager", "Cleanup failed: ${e.message}")
                }
                
                Log.d("DownloadManager", "File saved without tags: ${outFile.absolutePath}")
                return true
            } catch (copyEx: Exception) {
                Log.e("DownloadManager", "Failed to save file without tags: ${copyEx.message}")
                return false
            }
        } catch (e: Exception) {
            Log.e("DownloadManager", "Unexpected exception when reading audio file: ${e.javaClass.simpleName} - ${e.message}")
            Log.e("DownloadManager", "Stack trace:", e)
            // Try to save without tags
            try {
                if (outFile.exists()) outFile.delete()
                fileToTag.copyTo(outFile, overwrite = true)
                if (tempWithExt.exists() && tempWithExt.absolutePath != outFile.absolutePath) tempWithExt.delete()
                if (tempFile.exists() && tempFile.absolutePath != outFile.absolutePath) tempFile.delete()
                Log.d("DownloadManager", "File saved without tags after error: ${outFile.absolutePath}")
                return true
            } catch (copyEx: Exception) {
                Log.e("DownloadManager", "Failed to save file: ${copyEx.message}")
                return false
            }
        }
        
        Log.d("DownloadManager", "Audio file read successfully, now tagging...")

        try {
            val tag = audioFile.tagOrCreateAndSetDefault
            Log.d("DownloadManager", "Got tag object")

            if (!title.isNullOrEmpty()) {
    tag.setField(FieldKey.TITLE, title)
    Log.d("DownloadManager", "Set title")
}
if (!artist.isNullOrEmpty()) {
    tag.setField(FieldKey.ARTIST, artist)
    Log.d("DownloadManager", "Set artist")
}
if (!album.isNullOrEmpty()) {
    tag.setField(FieldKey.ALBUM, album)
    Log.d("DownloadManager", "Set album")
}
if (trackNumber != null) tag.setField(FieldKey.TRACK, trackNumber.toString())
if (!year.isNullOrEmpty()) tag.setField(FieldKey.YEAR, year)
if (!genre.isNullOrEmpty()) tag.setField(FieldKey.GENRE, genre)

// Extended metadata
if (bpm != null && bpm > 0) {
    try {
        tag.setField(FieldKey.BPM, bpm.toString())
        Log.d("DownloadManager", "Set BPM: $bpm")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to set BPM: ${e.message}")
    }
}

if (!isrc.isNullOrEmpty()) {
    try {
        tag.setField(FieldKey.ISRC, isrc)
        Log.d("DownloadManager", "Set ISRC: $isrc")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to set ISRC: ${e.message}")
    }
}

if (!copyright.isNullOrEmpty()) {
    try {
        tag.setField(FieldKey.COMMENT, "Copyright: $copyright")
        Log.d("DownloadManager", "Set Copyright")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to set Copyright: ${e.message}")
    }
}

if (replayGain != null) {
    try {
        // ReplayGain stored as custom field
        tag.setField(FieldKey.CUSTOM1, "REPLAYGAIN_TRACK_GAIN=${String.format("%.2f dB", replayGain)}")
        Log.d("DownloadManager", "Set ReplayGain: $replayGain dB")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to set ReplayGain: ${e.message}")
    }
}

Log.d("DownloadManager", "All text fields set")

// STEP 1: Commit text tags first (without artwork)
Log.d("DownloadManager", "About to commit text tags...")
try {
    audioFile.commit()
    Log.d("DownloadManager", "Text tags committed successfully")
} catch (oom: OutOfMemoryError) {
    Log.e("DownloadManager", "OutOfMemoryError during commit - file too large for tagging")
    // Save without tags
    if (outFile.exists()) outFile.delete()
    fileToTag.copyTo(outFile, overwrite = true)
    if (tempWithExt.exists() && tempWithExt.absolutePath != outFile.absolutePath) tempWithExt.delete()
    if (tempFile.exists() && tempFile.absolutePath != outFile.absolutePath) tempFile.delete()
    Log.d("DownloadManager", "File saved without tags due to memory constraints")
    return true
}

// STEP 2: Now add artwork AFTER commit (for FLAC only - M4A/OGG use jaudiotagger)
if (artBytes != null && artBytes.size >= 100) {
    Log.d("DownloadManager", "Adding artwork (${artBytes.size} bytes) to ${fileToTag.extension} file...")
    
    if (fileToTag.extension.equals("flac", ignoreCase = true)) {
        // For FLAC files, use manual binary embedding AFTER jaudiotagger is done
        try {
            val artworkSuccess = embedFlacArtworkManually(fileToTag, artBytes)
            if (artworkSuccess) {
                Log.d("DownloadManager", "✓ FLAC artwork embedded successfully using manual method")
            } else {
                Log.w("DownloadManager", "Manual FLAC artwork embedding failed")
            }
        } catch (e: Exception) {
            Log.e("DownloadManager", "Failed to embed FLAC artwork: ${e.message}", e)
        }
    }
}

            Log.d("DownloadManager", "About to commit...")
            try {
                audioFile.commit()
                Log.d("DownloadManager", "Tags committed successfully")
            } catch (oom: OutOfMemoryError) {
                Log.e("DownloadManager", "OutOfMemoryError during commit - file too large for tagging")
                // Save without tags
                if (outFile.exists()) outFile.delete()
                fileToTag.copyTo(outFile, overwrite = true)
                if (tempWithExt.exists() && tempWithExt.absolutePath != outFile.absolutePath) tempWithExt.delete()
                if (tempFile.exists() && tempFile.absolutePath != outFile.absolutePath) tempFile.delete()
                Log.d("DownloadManager", "File saved without tags due to memory constraints")
                return true
            }

            // Move the tagged file to the final location
            if (outFile.exists()) outFile.delete()
            val moved = fileToTag.renameTo(outFile)
            if (!moved) {
                fileToTag.copyTo(outFile, overwrite = true)
                fileToTag.delete()
            }

            Log.d("DownloadManager", "✓ Tagged file saved successfully: ${outFile.absolutePath}")
            
            // Clean up remaining temp files
            try {
                if (tempFile.exists() && tempFile.absolutePath != outFile.absolutePath) tempFile.delete()
                if (tempWithExt.exists() && tempWithExt.absolutePath != outFile.absolutePath) tempWithExt.delete()
            } catch (e: Exception) {
                Log.w("DownloadManager", "Cleanup failed: ${e.message}")
            }
            
            return true
            
        } catch (tagEx: Exception) {
            Log.w("DownloadManager", "FLAC/M4A/OGG tagging failed: ${tagEx.message}")
            Log.w("DownloadManager", "Stack trace:", tagEx)
            // Fall back: copy whatever file we have to the outFile so we don't lose the download
            try {
                if (outFile.exists()) outFile.delete()
                
                // Check which file still exists and copy it
                val sourceFile = when {
                    fileToTag.exists() -> fileToTag
                    tempWithExt.exists() -> tempWithExt
                    tempFile.exists() -> tempFile
                    else -> {
                        Log.e("DownloadManager", "No file to copy to outFile in fallback")
                        return false
                    }
                }
                
                Log.d("DownloadManager", "Copying ${sourceFile.name} to ${outFile.name}")
                sourceFile.copyTo(outFile, overwrite = true)
                
                // Clean up source temp files
                try {
                    if (tempWithExt.exists() && tempWithExt.absolutePath != outFile.absolutePath) tempWithExt.delete()
                    if (tempFile.exists() && tempFile.absolutePath != outFile.absolutePath) tempFile.delete()
                    if (fileToTag.exists() && fileToTag.absolutePath != outFile.absolutePath && fileToTag != sourceFile) fileToTag.delete()
                } catch (e: Exception) {
                    Log.w("DownloadManager", "Cleanup failed: ${e.message}")
                }
                
                Log.d("DownloadManager", "File saved without tags: ${outFile.absolutePath}")
                return true
            } catch (e2: Exception) {
                Log.e("DownloadManager", "Fallback copy failed: ${e2.message}", e2)
                return false
            }
        }
    } catch (e: Exception) {
        Log.e("DownloadManager", "Unexpected error in tagFlacOrM4aFile: ${e.message}", e)
        return false
    }
}

    private suspend fun remuxM4aFile(context: Context, inputFile: File, outputFile: File): Boolean = 
    withContext(Dispatchers.IO) {
        try {
            Log.d("DownloadManager", "Remuxing ${inputFile.name} to create proper M4A...")
            
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)
            
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val trackCount = extractor.trackCount
            val trackIndexMap = HashMap<Int, Int>()
            
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                Log.d("DownloadManager", "Track $i: $mime")
                val muxerTrackIndex = muxer.addTrack(format)
                trackIndexMap[i] = muxerTrackIndex
            }
            
            muxer.start()
            
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            
            for (i in 0 until trackCount) {
                extractor.selectTrack(i)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                
                val muxerTrackIndex = trackIndexMap[i] ?: continue
                
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                
                extractor.unselectTrack(i)
            }
            
            muxer.stop()
            muxer.release()
            extractor.release()
            
            Log.d("DownloadManager", "Remuxing complete: ${outputFile.length()} bytes")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e("DownloadManager", "Remuxing failed: ${e.message}", e)
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
    trackNumber: Int? = null,
    year: String? = null,
    genre: String? = null,
    streamUrl: String? = null,
    bpm: Int? = null,          // NEW
    isrc: String? = null,      // NEW
    copyright: String? = null, // NEW
    replayGain: Double? = null, // NEW
    progressCb: (Int) -> Unit
): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    if (streamUrl == null || !streamUrl.contains("/stream/?id=")) {
                        Log.e("DownloadManager", "Stream URL required for download")
                        return@withContext false
                    }

                    val downloadUrl =
                            resolveDownloadUrl(trackId, streamUrl)
                                    ?: run {
                                        Log.e("DownloadManager", "Failed to resolve download URL")
                                        return@withContext false
                                    }

                    val musicDir = getDownloadDirectory(context)
                    if (!musicDir.exists()) musicDir.mkdirs()

                    val baseName =
                            sanitizeFilename((artist ?: "unknown") + " - " + (title ?: trackId))

                    // Handle segmented downloads
if (downloadUrl.startsWith("SEGMENTED:")) {
    Log.d("DownloadManager", "Starting segmented download...")
    val segmentedFile = downloadSegmentedTrack(context, downloadUrl, baseName, progressCb)

    if (segmentedFile != null && segmentedFile.exists()) {
        Log.d("DownloadManager", "Segmented file downloaded: ${segmentedFile.length()} bytes")
        
        // Check the audio codec before attempting remux
        var fileToTag = segmentedFile
        var needsRemux = true
        
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(segmentedFile.absolutePath)
            
            if (extractor.trackCount > 0) {
                val format = extractor.getTrackFormat(0)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                Log.d("DownloadManager", "Detected codec: $mime")
                
                // FLAC can't be remuxed with MediaMuxer - skip remuxing
                if (mime.contains("flac", ignoreCase = true)) {
                    Log.d("DownloadManager", "FLAC codec detected - skipping remux, file is already playable")
                    needsRemux = false
                }
            }
            extractor.release()
        } catch (e: Exception) {
            Log.w("DownloadManager", "Could not detect codec: ${e.message}")
        }
        
        // Only remux if needed (non-FLAC codecs)
        if (needsRemux) {
            val remuxedFile = File(segmentedFile.parent, "${baseName}.remuxed.m4a")
            val remuxSuccess = remuxM4aFile(context, segmentedFile, remuxedFile)
            
            if (remuxSuccess) {
                Log.d("DownloadManager", "Using remuxed file for tagging")
                fileToTag = remuxedFile
                segmentedFile.delete()
            } else {
                Log.w("DownloadManager", "Remuxing failed, using original file")
                remuxedFile.delete()
            }
        }
        
        val outFile = File(musicDir, "$baseName.m4a")

        // Download album art if available
        var artBytes: ByteArray? = null
if (!albumArtUrl.isNullOrEmpty()) {
    try {
        // Convert cover ID to full URL using ImageUrlHelper
        val coverUrl = ImageUrlHelper.getCoverArtUrl(albumArtUrl)
        val u = URL(coverUrl)
        u.openStream().use { artBytes = it.readBytes() }
        Log.d("DownloadManager", "✓ Album art downloaded successfully (${artBytes?.size} bytes)")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to download album art: ${e.message}")
        artBytes = null
    }
}

        // For FLAC in fMP4, jaudiotagger will fail - just save the file directly
        val success = if (needsRemux) {
            tagFlacOrM4aFile(fileToTag, outFile, title, artist, album, trackNumber, year, genre, artBytes)
        } else {
            // FLAC files - save without tagging (jaudiotagger can't handle fMP4 FLAC)
            Log.d("DownloadManager", "Saving FLAC file without tags (not supported)")
            try {
                if (outFile.exists()) outFile.delete()
                fileToTag.copyTo(outFile, overwrite = true)
                fileToTag.delete()
                true
            } catch (e: Exception) {
                Log.e("DownloadManager", "Failed to save file: ${e.message}")
                false
            }
        }
        
        progressCb(100)
        return@withContext success
    } else {
        Log.e("DownloadManager", "Segmented download failed or file doesn't exist")
        return@withContext false
    }
}

                    val tempFile = File(musicDir, "$baseName.tmp")
                    var finalExt = ".mp3"

                    val req = Request.Builder().url(downloadUrl).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.e("DownloadManager", "Download request failed: ${resp.code}")
                            return@withContext false
                        }
                        val body = resp.body ?: return@withContext false
                        val contentLength = body.contentLength()

                        val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                        finalExt =
                                when {
                                    contentType.contains("mpeg") || contentType.contains("mp3") ->
                                            ".mp3"
                                    contentType.contains("flac") -> ".flac"
                                    contentType.contains("ogg") ||
                                            contentType.contains("vorbis") ||
                                            contentType.contains("opus") -> ".ogg"
                                    contentType.contains("wav") || contentType.contains("wave") ->
                                            ".wav"
                                    contentType.contains("mp4") || contentType.contains("m4a") ->
                                            ".m4a"
                                    else -> {
                                        val path = resp.request.url.encodedPath
                                        when {
                                            path.endsWith(".flac", ignoreCase = true) -> ".flac"
                                            path.endsWith(".mp3", ignoreCase = true) -> ".mp3"
                                            path.endsWith(".m4a", ignoreCase = true) -> ".m4a"
                                            path.endsWith(".mp4", ignoreCase = true) -> ".m4a"
                                            else -> ".mp3"
                                        }
                                    }
                                }

                        val inputStream = body.byteStream()
                        FileOutputStream(tempFile).use { fos ->
                            val buf = ByteArray(8 * 1024)
                            var totalRead = 0L
                            var r: Int
                            var lastPct = -1
while (inputStream.read(buf).also { r = it } != -1) {
    fos.write(buf, 0, r)
    totalRead += r
    if (contentLength > 0) {
        val pct = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
        if (pct != lastPct) {
            lastPct = pct
            try { progressCb(pct) } catch (_: Exception) {}
        }
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
        // Convert cover ID to full URL using ImageUrlHelper
        val coverUrl = ImageUrlHelper.getCoverArtUrl(albumArtUrl)
        val u = URL(coverUrl)
        u.openStream().use { artBytes = it.readBytes() }
        Log.d("DownloadManager", "✓ Album art downloaded successfully (${artBytes?.size} bytes)")
    } catch (e: Exception) {
        Log.w("DownloadManager", "Failed to download album art: ${e.message}")
        artBytes = null
    }
}

                    val outFile = File(musicDir, "$baseName$finalExt")

                    val success =
                            when (finalExt) {
                                ".mp3" ->
                                        tagMp3File(
                                                tempFile,
                                                outFile,
                                                title,
                                                artist,
                                                album,
                                                trackNumber,
                                                year,
                                                genre,
                                                artBytes
                                        )
                                ".flac", ".m4a", ".ogg" ->
    tagFlacOrM4aFile(
        tempFile,
        outFile,
        title,
        artist,
        album,
        trackNumber,
        year,
        genre,
        artBytes,
        bpm,
        isrc,
        copyright,
        replayGain
    )
                                else -> {
                                    try {
                                        if (outFile.exists()) outFile.delete()
                                        tempFile.renameTo(outFile) ||
                                                tempFile.copyTo(outFile, overwrite = true).let {
                                                    true
                                                }
                                    } catch (e: Exception) {
                                        Log.e("DownloadManager", "Saving file failed: ${e.message}")
                                        false
                                    }
                                }
                            }

                    if (success) progressCb(100)
                    return@withContext success
                } catch (e: Exception) {
                    Log.e("DownloadManager", "download failed: ${e.message}", e)
                    return@withContext false
                }
            }

    suspend fun downloadAlbum(
            context: Context,
            tracks: List<TrackInfo>
    ): List<Pair<TrackInfo, Boolean>> {
        val results = mutableListOf<Pair<TrackInfo, Boolean>>()
        for ((idx, t) in tracks.withIndex()) {
            val ok =
                    downloadTrack(
                            context,
                            t.id,
                            t.title,
                            t.artist,
                            t.album,
                            t.albumArtUrl,
                            trackNumber = idx + 1,
                            streamUrl = t.streamUrl
                    )
            results.add(t to ok)
        }
        return results
    }

    data class TrackInfo(
            val id: String,
            val title: String?,
            val artist: String?,
            val album: String?,
            val albumArtUrl: String?,
            val streamUrl: String? = null
    )
}

