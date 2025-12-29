package com.example.moniq.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.util.Base64
import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

object StreamUrlResolver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private lateinit var appContext: Context
    
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
    
    // ✅ ADD forceFresh parameter to bypass cache
    suspend fun resolveStreamUrl(
        baseUrl: String, 
        trackId: String, 
        quality: String = "LOSSLESS",
        forceFresh: Boolean = false  // ✅ NEW PARAMETER
    ): String? = withContext(Dispatchers.IO) {
        try {
            val site = baseUrl.substringBefore("/stream/")
            val url = "$site/track/?id=$trackId"
            
            Log.d("StreamUrlResolver", "Fetching track info from: $url (forceFresh=$forceFresh)")
            
            val request = Request.Builder()
    .url(url)
    .addHeader("Accept", "*/*")
    // REMOVED Accept-Encoding - OkHttp handles compression automatically
    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
    .addHeader("x-client", "BiniLossless/v3.3")
    .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("StreamUrlResolver", "Failed to fetch track info: ${response.code}")
                return@withContext null
            }
            
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return@withContext null
            
            val manifestMimeType = data.optString("manifestMimeType", "")
            val manifest = data.optString("manifest", "")
            
            Log.d("StreamUrlResolver", "Got manifest type: $manifestMimeType")
            
            when {
                manifestMimeType == "application/dash+xml" -> {
                    val cacheDir = appContext.externalCacheDir ?: appContext.cacheDir
                    val manifestFile = File(cacheDir, "dash_$trackId.mpd")
                    
                    // ✅ FIX: Delete cached file if forcing fresh resolve
                    if (forceFresh && manifestFile.exists()) {
                        val deleted = manifestFile.delete()
                        Log.d("StreamUrlResolver", "🗑️ Deleted stale cache file: $deleted")
                    }
                    
                    // ✅ Only use cache if NOT forcing fresh AND file exists
                    if (!forceFresh && manifestFile.exists() && manifestFile.length() > 0) {
                        val fileUrl = manifestFile.toURI().toString()
                        Log.d("StreamUrlResolver", "Using cached DASH manifest: $fileUrl")
                        return@withContext fileUrl
                    }
                    
                    // Decode the base64 DASH manifest
                    val manifestXml = String(Base64.decode(manifest, Base64.DEFAULT))
                    Log.d("StreamUrlResolver", "Decoded DASH manifest length: ${manifestXml.length}")
                    
                    // Write the manifest
                    manifestFile.writeText(manifestXml)
                    manifestFile.setReadable(true, false)
                    
                    val fileUrl = manifestFile.toURI().toString()
                    Log.d("StreamUrlResolver", "Wrote fresh DASH manifest to: $fileUrl")
                    
                    fileUrl
                }
                manifestMimeType == "application/vnd.tidal.bts" -> {
                    // Decode base64 BTS manifest and extract direct URL
                    parseBtsManifest(manifest)
                }
                else -> {
                    Log.e("StreamUrlResolver", "Unknown manifest type: $manifestMimeType")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("StreamUrlResolver", "Error resolving stream URL", e)
            null
        }
    }
    
    private fun parseBtsManifest(manifestBase64: String): String? {
        try {
            val decoded = String(Base64.decode(manifestBase64, Base64.DEFAULT))
            Log.d("StreamUrlResolver", "Decoded BTS manifest: $decoded")
            
            val json = JSONObject(decoded)
            val urls = json.optJSONArray("urls")
            if (urls != null && urls.length() > 0) {
                val url = urls.getString(0)
                Log.d("StreamUrlResolver", "Extracted BTS URL: $url")
                return url
            }
        } catch (e: Exception) {
            Log.e("StreamUrlResolver", "Error parsing BTS manifest", e)
        }
        return null
    }
}