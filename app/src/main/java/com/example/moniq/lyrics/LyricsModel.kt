package com.example.moniq.lyrics

data class Syllable(
    val startMs: Long, 
    val durationMs: Long, 
    val text: String, 
    val transliteration: String?,
    val isBackground: Boolean = false  // ADD THIS
)

data class SyllableLine(
    val startMs: Long, 
    val syllables: List<Syllable>,
    val translation: String? = null
)