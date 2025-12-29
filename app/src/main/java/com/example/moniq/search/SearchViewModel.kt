package com.example.moniq.search

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import android.util.Log

class SearchViewModel : ViewModel() {
    private val repo = SearchRepository()
    
    val loading = MutableLiveData(false)
    val error = MutableLiveData<String?>(null)
    val songs = MutableLiveData<List<com.example.moniq.model.Track>>(emptyList())
    val albums = MutableLiveData<List<com.example.moniq.model.Album>>(emptyList())
    val artists = MutableLiveData<List<com.example.moniq.model.Artist>>(emptyList())
    val rawResponse = MutableLiveData<String?>(null)
    val responseCode = MutableLiveData<Int?>(null)
    
    fun search(query: String) {
        viewModelScope.launch {
            loading.postValue(true)
            error.postValue(null) // Clear previous errors
            
            try {
                val result = repo.search(query) // Changed from 'repository' to 'repo'
                songs.postValue(result.songs)
                albums.postValue(result.albums)
                artists.postValue(result.artists)
                responseCode.postValue(result.code)
                
                // Check if search actually failed
                if (result.code == -1) {
                    error.postValue(result.rawBody) // This contains "All search sites failed"
                }
            } catch (e: Exception) {
                // Capture the full error message with details
                val errorMsg = when (e) {
                    is HttpException -> "HTTP ${e.code}: ${e.message}\nURL: ${e.url}"
                    else -> e.message ?: "Unknown error occurred"
                }
                error.postValue(errorMsg)
                Log.e("SearchViewModel", "Search error", e)
            } finally {
                loading.postValue(false)
            }
        }
    }
}