package com.example.moniq.search

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

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
        // Allow empty query per OpenSubsonic spec (servers should return all data)
        val q = query
        loading.value = true
        error.value = null
        rawResponse.value = null
        responseCode.value = null
        viewModelScope.launch {
            try {
                val res = repo.search(q)
                songs.postValue(res.songs)
                albums.postValue(res.albums)
                artists.postValue(res.artists)
                rawResponse.postValue(res.rawBody)
                responseCode.postValue(res.code)
            } catch (t: Throwable) {
                error.postValue(t.message ?: "Search failed")
                rawResponse.postValue(t.message)
            } finally {
                loading.postValue(false)
            }
        }
    }
}
