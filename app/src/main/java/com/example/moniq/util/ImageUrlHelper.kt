package com.example.moniq.util

import kotlinx.coroutines.delay

object ImageUrlHelper {

    /** Get best available server based on reliability */
    private fun getNextServer(): String {
        return ServerManager.getOrderedServers().firstOrNull()
                ?: ServerManager.getAllServers().first()
    }

    /**
     * Converts a cover art ID to a full image URL Handles: full URLs, Tidal UUID format, and proxy
     * IDs
     */
    fun getCoverArtUrl(coverArtId: String?, fallbackServer: String? = null): String? {
        android.util.Log.d("ImageUrlHelper", "getCoverArtUrl: input coverArtId='$coverArtId'")
        if (coverArtId.isNullOrBlank()) {
            android.util.Log.d(
                    "ImageUrlHelper",
                    "getCoverArtUrl: coverArtId is null or blank, returning null"
            )
            return null
        }

        // If already a full URL, return as-is
        if (coverArtId.startsWith("http://") || coverArtId.startsWith("https://")) {
            android.util.Log.d(
                    "ImageUrlHelper",
                    "getCoverArtUrl: Already a full URL, returning as-is: $coverArtId"
            )
            return coverArtId
        }

        // Check if it's a Tidal UUID format
        if (coverArtId.matches(
                        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                )
        ) {
            val path = coverArtId.replace("-", "/")
            val tidalUrl = "https://resources.tidal.com/images/$path/750x750.jpg"
            android.util.Log.d(
                    "ImageUrlHelper",
                    "getCoverArtUrl: Detected UUID, converted to Tidal URL: $tidalUrl"
            )
            return tidalUrl
        }

        // Rest of the function...

        // If already in proxy format (/image/?id=...), use with server
        if (coverArtId.startsWith("/image/")) {
            val server = fallbackServer ?: getNextServer()
            return "$server$coverArtId"
        }

        // Otherwise, construct the proxy URL
        val server = fallbackServer ?: getNextServer()
        return "$server/image/?id=$coverArtId"
    }

    /** Get streaming URL for a track */
    fun getStreamUrl(trackId: String, server: String? = null): String {
        val useServer = server ?: getNextServer()
        return "$useServer/stream/?id=$trackId"
    }

    /**
     * Try to get the best quality cover art by checking servers ONE AT A TIME with delays to avoid
     * rate limits
     */
    suspend fun getBestCoverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null

        // If already a URL, return it
        if (coverArtId.startsWith("http")) return coverArtId

        // Check if it's a Tidal path
        if (coverArtId.contains("/")) {
            return "https://resources.tidal.com/images/${coverArtId.replace("-", "/")}"
        }

        // Try each server ONE AT A TIME with delays, prioritizing reliable ones
        for ((index, server) in ServerManager.getOrderedServers().withIndex()) {
            val url = "$server/image/?id=$coverArtId"
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
conn.connectTimeout = 3000
conn.readTimeout = 3000
conn.requestMethod = "GET"  // Use HEAD instead of GET for faster checks
conn.setRequestProperty("Accept", "*/*")
conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br, zstd")
conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
conn.setRequestProperty("x-client", "BiniLossless/v3.3")  // 🔥 CRITICAL
conn.connect()
                // Record success and return
                ServerManager.recordSuccess(server)
                return url
            } catch (e: Exception) {
                // Record failure
                ServerManager.recordFailure(server)
                // Wait before trying next server to avoid rate limits
                if (index < ServerManager.getOrderedServers().size - 1) {
                    delay(2000L) // 2 second delay between servers
                }
                continue
            }
        }

        // Fallback to current server
        return "${getNextServer()}/image/?id=$coverArtId"
    }
}
