package com.example.moniq.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object ImageFetcher {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchUrlBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko)")
                    .header("Accept", "image/*,*/*;q=0.8")

                // Attempt to set a sensible referer if the URL has an origin
                try {
                    val uri = java.net.URI(url)
                    val origin = uri.scheme + "://" + uri.host
                    reqBuilder.header("Referer", origin)
                } catch (e: Exception) {
                    // ignore
                }

                val req = reqBuilder.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    return@withContext body.bytes()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
