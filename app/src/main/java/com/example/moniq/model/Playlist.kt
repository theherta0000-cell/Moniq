package com.example.moniq.model

data class Playlist(
    val id: String,
    var name: String,
    var description: String? = null,
    var coverArtId: String? = null,
    val tracks: MutableList<Track> = mutableListOf()
)
