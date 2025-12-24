package com.example.moniq.util

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AudioMetadataExtractor {
    data class AudioMetadata(
        val bitrate: Int? = null,
        val format: String? = null,
        val sampleRate: Int? = null,
        val duration: Long? = null
    )
    
    suspend fun extractMetadata(url: String): AudioMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, HashMap<String, String>())
            
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000) // Convert to kbps
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            
            // Extract format from mime type (e.g., "audio/mpeg" -> "MP3")
            val format = when {
                mimeType?.contains("mpeg") == true -> "MP3"
                mimeType?.contains("flac") == true -> "FLAC"
                mimeType?.contains("ogg") == true -> "OGG"
                mimeType?.contains("aac") == true -> "AAC"
                mimeType?.contains("opus") == true -> "OPUS"
                else -> mimeType?.substringAfter("/")?.uppercase()
            }
            
            AudioMetadata(bitrate, format, sampleRate, duration)
        } catch (e: Exception) {
            android.util.Log.e("AudioMetadata", "Failed to extract metadata: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }
    
    fun formatQuality(metadata: AudioMetadata?): String? {
        if (metadata == null) return null
        
        val parts = mutableListOf<String>()
        
        // Add format
        metadata.format?.let { parts.add(it) }
        
        // Add bitrate with quality indicator
        metadata.bitrate?.let { bitrate ->
            val quality = when {
                bitrate >= 320 -> "High Quality"
                bitrate >= 192 -> "Good Quality"
                bitrate >= 128 -> "Standard"
                else -> "Low Quality"
            }
            parts.add("$bitrate kbps • $quality")
        }
        
        return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
    }
}