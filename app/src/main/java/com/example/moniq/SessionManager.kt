package com.example.moniq

object SessionManager {
    var host: String? = null
    var username: String? = null
    var password: String? = null
    var legacy: Boolean = false

    /**
     * Ensure the in-memory session is initialized from persistent storage if possible.
     * This should be a cheap no-op if already loaded.
     */
    fun ensureLoaded(context: android.content.Context) {
        if (host != null && username != null && password != null) return
        try { com.example.moniq.SessionStore.load(context) } catch (_: Exception) {}
    }

    fun clear() {
        host = null
        username = null
        password = null
        legacy = false
    }
}
