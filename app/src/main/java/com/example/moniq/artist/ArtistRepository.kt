package com.example.moniq.artist

import android.util.Log
import com.example.moniq.model.Album
import com.example.moniq.model.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.example.moniq.util.ServerManager
import java.net.URLEncoder

class ArtistRepository {
    
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    /**
     * Search for artists by query string
     */
    suspend fun searchArtists(query: String): List<Artist> = withContext(Dispatchers.IO) {
    val servers = ServerManager.getOrderedServers()

    for (server in servers) {
        try {
            val url = "$server/search/?q=${URLEncoder.encode(query, "UTF-8")}"
            Log.d("ArtistRepository", "Searching artists from: $url")

            val request = Request.Builder()
    .url(url)
    .addHeader("Accept", "*/*")
    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
    .addHeader("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
    .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                ServerManager.recordFailure(server)
                continue
            }

            val body = response.body?.string()
if (body == null) {
    ServerManager.recordFailure(server)
    continue
}

            val artists = parseArtistSearch(body)
            if (artists.isNotEmpty()) {
                ServerManager.recordSuccess(server)
                return@withContext artists
            } else {
                ServerManager.recordFailure(server)
            }
        } catch (e: Exception) {
            ServerManager.recordFailure(server)
            Log.w("ArtistRepository", "Failed on $server: ${e.message}")
        }
    }
    emptyList()
}

    
    /**
     * Get albums for a specific artist by artist ID
     */
    suspend fun getArtistAlbums(artistId: String): List<Album> = withContext(Dispatchers.IO) {
    val servers = ServerManager.getOrderedServers()

    for (server in servers) {
        try {
            val url = "$server/artist/?f=$artistId"
            val request = Request.Builder()
    .url(url)
    .addHeader("Accept", "*/*")
    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
    .addHeader("x-client", "BiniLossless/v3.3")  
    .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                ServerManager.recordFailure(server)
                continue
            }

            val body = response.body?.string()
if (body == null) {
    ServerManager.recordFailure(server)
    continue
}

            val albums = parseArtistAlbums(body)
            if (albums.isNotEmpty()) {
                ServerManager.recordSuccess(server)
                return@withContext albums
            } else {
                ServerManager.recordFailure(server)
            }
        } catch (e: Exception) {
            ServerManager.recordFailure(server)
            Log.e("ArtistRepository", "Error on $server", e)
        }
    }
    emptyList()
}

    
    /**
     * Parse artist search results from JSON
     */
    private fun parseArtistSearch(body: String): List<Artist> {
        val artists = mutableListOf<Artist>()
        
        try {
            val root = JSONObject(body)
            
            // Try with data wrapper first (some endpoints might have it)
            val data = root.optJSONObject("data") ?: root
            val artistsData = data.optJSONObject("artists") ?: return artists
            val rows = artistsData.optJSONArray("rows") ?: return artists
            
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val modules = row.optJSONArray("modules") ?: continue
                
                for (j in 0 until modules.length()) {
                    val module = modules.optJSONObject(j) ?: continue
                    val pagedList = module.optJSONObject("pagedList") ?: continue
                    val items = pagedList.optJSONArray("items") ?: continue
                    
                    for (k in 0 until items.length()) {
                        val item = items.optJSONObject(k) ?: continue
                        
                        val artistId = item.optString("id", "")
                        val artistName = item.optString("name", "Unknown Artist")
                        val picture = item.optString("picture", null)
                        
                        if (artistId.isNotEmpty()) {
                            artists.add(Artist(
                                id = artistId,
                                name = artistName,
                                coverArtId = picture
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ArtistRepository", "Error parsing artist search", e)
        }
        
        return artists
    }
    
    /**
     * Parse artist albums from JSON (for artist detail page)
     * JSON structure: { "version": "2.0", "albums": {...}, "tracks": [...] }
     */
    private fun parseArtistAlbums(body: String): List<Album> {
        val albums = mutableListOf<Album>()
        
        try {
            val root = JSONObject(body)
            
            // Albums are at root level, not under "data"
            val albumsData = root.optJSONObject("albums")
            if (albumsData == null) {
                Log.w("ArtistRepository", "No 'albums' object found in JSON")
                return albums
            }
            
            val rows = albumsData.optJSONArray("rows")
            if (rows == null) {
                Log.w("ArtistRepository", "No 'rows' array found in albums")
                return albums
            }
            
            if (rows.length() > 0) {
                val firstRow = rows.optJSONObject(0)
                if (firstRow == null) {
                    Log.w("ArtistRepository", "First row is null")
                    return albums
                }
                
                val modules = firstRow.optJSONArray("modules")
                if (modules == null) {
                    Log.w("ArtistRepository", "No 'modules' array found")
                    return albums
                }
                
                if (modules.length() > 0) {
                    val module = modules.optJSONObject(0)
                    if (module == null) {
                        Log.w("ArtistRepository", "First module is null")
                        return albums
                    }
                    
                    val pagedList = module.optJSONObject("pagedList")
                    if (pagedList == null) {
                        Log.w("ArtistRepository", "No 'pagedList' object found")
                        return albums
                    }
                    
                    val items = pagedList.optJSONArray("items")
                    if (items == null) {
                        Log.w("ArtistRepository", "No 'items' array found")
                        return albums
                    }
                    
                    Log.d("ArtistRepository", "Found ${items.length()} album items")
                    
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        
                        val albumId = item.optString("id", "")
                        val title = item.optString("title", "Unknown Album")
                        val cover = item.optString("cover", null)
                        val releaseDate = item.optString("releaseDate", null)
                        
                        // Parse artist
                        val artistsArray = item.optJSONArray("artists")
                        val artistName = if (artistsArray != null && artistsArray.length() > 0) {
                            artistsArray.optJSONObject(0)?.optString("name", "Unknown Artist") ?: "Unknown Artist"
                        } else "Unknown Artist"
                        
                        // Extract year
                        val year = releaseDate?.take(4)?.toIntOrNull()
                        
                        Log.d("ArtistRepository", "Parsed album: $title by $artistName (ID: $albumId)")
                        
                        albums.add(Album(
                            id = albumId,
                            name = title,
                            artist = artistName,
                            year = year,
                            coverArtId = cover
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ArtistRepository", "Error parsing artist albums", e)
        }
        
        return albums
    }
    
    /**
     * Fetch artist image bytes from cover art ID
     */
    suspend fun fetchArtistImage(coverArtId: String): ByteArray? = withContext(Dispatchers.IO) {
    for (server in ServerManager.getOrderedServers()) {
        try {
            val url = "$server/image/$coverArtId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.bytes()?.let {
                    ServerManager.recordSuccess(server)
                    return@withContext it
                }
            }

            ServerManager.recordFailure(server)
        } catch (e: Exception) {
            ServerManager.recordFailure(server)
        }
    }
    null
}

    
    /**
     * Get artist info (name, biography, cover)
     * Extracts from the first album's artist data
     */
    suspend fun getArtistInfo(
    artistId: String
): Triple<String, String?, String?> = withContext(Dispatchers.IO) {

    val servers = ServerManager.getOrderedServers()

    for (server in servers) {
        try {
            val url = "$server/artist/?f=$artistId"
            val request = Request.Builder()
    .url(url)
    .addHeader("Accept", "*/*")
    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
    .addHeader("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
    .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                ServerManager.recordFailure(server)
                continue
            }

            val body = response.body?.string()
if (body == null) {
    ServerManager.recordFailure(server)
    continue
}


            val root = JSONObject(body)
            val albumsData = root.optJSONObject("albums") ?: continue
            val rows = albumsData.optJSONArray("rows") ?: continue

            if (rows.length() > 0) {
                val firstRow = rows.optJSONObject(0) ?: continue
                val modules = firstRow.optJSONArray("modules") ?: continue

                if (modules.length() > 0) {
                    val module = modules.optJSONObject(0) ?: continue
                    val pagedList = module.optJSONObject("pagedList") ?: continue
                    val items = pagedList.optJSONArray("items") ?: continue

                    if (items.length() > 0) {
                        val firstAlbum = items.optJSONObject(0) ?: continue
                        val artistsArray = firstAlbum.optJSONArray("artists")

                        if (artistsArray != null && artistsArray.length() > 0) {
                            val artistObj = artistsArray.optJSONObject(0)
                            val name = artistObj?.optString("name", "Unknown Artist") ?: "Unknown Artist"
                            val picture = artistObj?.optString("picture", null)

                            ServerManager.recordSuccess(server)
                            return@withContext Triple(name, null, picture)
                        }
                    }
                }
            }

            ServerManager.recordFailure(server)

        } catch (e: Exception) {
            ServerManager.recordFailure(server)
            Log.w("ArtistRepository", "Failed on $server", e)
        }
    }

    Triple("Unknown Artist", null, null)
}

}