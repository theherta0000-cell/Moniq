package com.example.moniq.model

data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val year: Int?,
    val coverArtId: String? = null
)
