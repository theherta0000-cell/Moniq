package com.example.moniq.model

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val albumId: String? = null,
    val albumName: String? = null,
    val coverArtId: String? = null
) 
