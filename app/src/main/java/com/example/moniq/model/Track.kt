package com.example.moniq.model


data class Track(
    val id: String,
    val title: String,
    val artist: String? = null,
    val duration: Int = 0,
    val albumId: String? = null,
    val albumName: String? = null,
    val coverArtId: String? = null,
    val streamUrl: String? = null,
    val audioQuality: String? = null,  
    val audioModes: List<String>? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val mediaMetadataTags: List<String>? = null
)