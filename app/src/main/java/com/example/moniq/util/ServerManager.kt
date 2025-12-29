package com.example.moniq.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ServerManager {
    private val servers = listOf(
        "https://wolf.qqdl.site",
        "https://maus.qqdl.site",
        "https://vogel.qqdl.site",
        "https://katze.qqdl.site",
        "https://hund.qqdl.site",
        "https://triton.squid.wtf",
        "https://tidal-api-2.binimum.org",
        "https://tidal.kinoplus.online"
    )
    
    // Track success count and last success time for each server
    private val successCounts = ConcurrentHashMap<String, Int>()
    private val lastSuccessTime = ConcurrentHashMap<String, Long>()
    private val failureCounts = ConcurrentHashMap<String, Int>()
    
    private var prefs: SharedPreferences? = null
    private var isInitialized = false
    
    fun initialize(context: Context) {
        if (isInitialized) return
        
        prefs = context.getSharedPreferences("server_stats", Context.MODE_PRIVATE)
        loadStats()
        isInitialized = true
        Log.d("ServerManager", "Initialized with saved stats")
    }
    
    /**
     * Load stats from SharedPreferences
     */
    private fun loadStats() {
        prefs?.let { p ->
            try {
                // Load success counts
                val successJson = p.getString("success_counts", "{}")
                val successObj = JSONObject(successJson ?: "{}")
                successObj.keys().forEach { server ->
                    successCounts[server] = successObj.getInt(server)
                }
                
                // Load last success times
                val timeJson = p.getString("last_success_time", "{}")
                val timeObj = JSONObject(timeJson ?: "{}")
                timeObj.keys().forEach { server ->
                    lastSuccessTime[server] = timeObj.getLong(server)
                }
                
                // Load failure counts
                val failureJson = p.getString("failure_counts", "{}")
                val failureObj = JSONObject(failureJson ?: "{}")
                failureObj.keys().forEach { server ->
                    failureCounts[server] = failureObj.getInt(server)
                }
                
                Log.d("ServerManager", "Loaded stats: ${successCounts.size} servers tracked")
            } catch (e: Exception) {
                Log.e("ServerManager", "Failed to load stats: ${e.message}")
            }
        }
    }
    
    /**
     * Save stats to SharedPreferences
     */
    private fun saveStats() {
        prefs?.let { p ->
            try {
                val editor = p.edit()
                
                // Save success counts
                val successObj = JSONObject()
                successCounts.forEach { (server, count) ->
                    successObj.put(server, count)
                }
                editor.putString("success_counts", successObj.toString())
                
                // Save last success times
                val timeObj = JSONObject()
                lastSuccessTime.forEach { (server, time) ->
                    timeObj.put(server, time)
                }
                editor.putString("last_success_time", timeObj.toString())
                
                // Save failure counts
                val failureObj = JSONObject()
                failureCounts.forEach { (server, count) ->
                    failureObj.put(server, count)
                }
                editor.putString("failure_counts", failureObj.toString())
                
                editor.apply()
            } catch (e: Exception) {
                Log.e("ServerManager", "Failed to save stats: ${e.message}")
            }
        }
    }
    
    // Get servers sorted by reliability (most successful first)
    fun getOrderedServers(): List<String> {
        val now = System.currentTimeMillis()
        
        return servers.sortedByDescending { server ->
            val successes = successCounts[server] ?: 0
            val failures = failureCounts[server] ?: 0
            val lastSuccess = lastSuccessTime[server] ?: 0L
            
            // Prioritize servers with:
            // 1. Recent success (within last 5 minutes)
            // 2. High success count
            // 3. Low failure count
            val recencyBonus = if (now - lastSuccess < 300_000) 100 else 0
            val score = (successes * 10) - failures + recencyBonus
            
            score
        }
    }
    
    // Call this when a server succeeds
    fun recordSuccess(server: String) {
        successCounts[server] = (successCounts[server] ?: 0) + 1
        lastSuccessTime[server] = System.currentTimeMillis()
        // Reset failures on success
        failureCounts[server] = 0
        Log.d("ServerManager", "✓ $server - successes: ${successCounts[server]}")
        saveStats()
    }
    
    // Call this when a server fails
    fun recordFailure(server: String) {
        failureCounts[server] = (failureCounts[server] ?: 0) + 1
        Log.d("ServerManager", "✗ $server - failures: ${failureCounts[server]}")
        saveStats()
    }
    
    // Get all servers (for fallback)
    fun getAllServers(): List<String> = servers
    
    // Reset stats (for testing or user action)
    fun reset() {
        successCounts.clear()
        lastSuccessTime.clear()
        failureCounts.clear()
        saveStats()
        Log.i("ServerManager", "Server stats reset")
    }
}