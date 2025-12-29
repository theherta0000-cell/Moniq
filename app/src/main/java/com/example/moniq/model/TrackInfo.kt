package com.example.moniq.model

data class TrackInfo(
    val id: Long,
    val title: String,
    val duration: Int,
    val replayGain: Double?,
    val peak: Double?,
    val trackNumber: Int?,
    val volumeNumber: Int?,
    val popularity: Int?,
    val copyright: String?,
    val bpm: Int?,
    val key: String?,
    val keyScale: String?,
    val isrc: String?,
    val explicit: Boolean,
    val audioQuality: String,
    val audioModes: List<String>,
    val artist: ArtistInfo?,
    val artists: List<ArtistInfo>,
    val album: AlbumInfo?,
    val bitDepth: Int? = null,  // ✅ ADD THIS
    val sampleRate: Int? = null  // ✅ ADD THIS
)
data class ArtistInfo(
    val id: Long,
    val name: String,
    val type: String?,
    val picture: String?
)

data class AlbumInfo(
    val id: Long,
    val title: String,
    val cover: String?,
    val vibrantColor: String?
)