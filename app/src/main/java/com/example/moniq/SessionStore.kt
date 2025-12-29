package com.example.moniq

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SessionStore {
    private const val PREFS_NAME = "moniq_secure_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_LEGACY = "legacy"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val KEY_LAST_TRACK_ID = "last_track_id"
    private const val KEY_LAST_TRACK_POS = "last_track_pos"
    private const val KEY_LAST_TRACK_TITLE = "last_track_title"
    private const val KEY_LAST_TRACK_ARTIST = "last_track_artist"
    private const val KEY_LAST_TRACK_ART = "last_track_art"
    private const val KEY_LAST_IS_PLAYING = "last_is_playing"
    private const val KEY_LAST_TRACK_ALBUM_ID = "last_track_album_id"
    private const val KEY_LAST_TRACK_ALBUM_NAME = "last_track_album_name"
    private const val KEY_DOWNLOAD_DIR = "download_dir"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LAST_TRACK_DURATION = "last_track_duration"  

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey =
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

            EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // If encrypted prefs cannot be created on this device or API level,
            // fall back to regular SharedPreferences so credentials are still remembered.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun save(context: Context, host: String, user: String, pass: String, legacy: Boolean) {
        val p = prefs(context)
        p.edit()
                .putString(KEY_HOST, host)
                .putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .putBoolean(KEY_LEGACY, legacy)
                .apply()
    }

    fun load(context: Context): Boolean {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, null) ?: return false
        val user = p.getString(KEY_USER, null) ?: return false
        val pass = p.getString(KEY_PASS, null) ?: return false
        val legacy = p.getBoolean(KEY_LEGACY, false)

        SessionManager.host = host
        SessionManager.username = user
        SessionManager.password = pass
        SessionManager.legacy = legacy
        return true
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        SessionManager.clear()
    }

    fun savePlaybackSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply()
    }

    fun loadPlaybackSpeed(context: Context, default: Float = 1.0f): Float {
        return prefs(context).getFloat(KEY_PLAYBACK_SPEED, default)
    }

    
fun saveLastTrack(
        context: Context,
        trackId: String?,
        posMs: Long,
        title: String?,
        artist: String?,
        artUrl: String?,
        isPlaying: Boolean,
        albumId: String? = null,
        albumName: String? = null,
        duration: Long = 0L  // ADD THIS PARAMETER
) {
    prefs(context)
            .edit()
            .putString(KEY_LAST_TRACK_ID, trackId)
            .putLong(KEY_LAST_TRACK_POS, posMs)
            .putString(KEY_LAST_TRACK_TITLE, title)
            .putString(KEY_LAST_TRACK_ARTIST, artist)
            .putString(KEY_LAST_TRACK_ART, artUrl)
            .putBoolean(KEY_LAST_IS_PLAYING, isPlaying)
            .putString(KEY_LAST_TRACK_ALBUM_ID, albumId)
            .putString(KEY_LAST_TRACK_ALBUM_NAME, albumName)
            .putLong(KEY_LAST_TRACK_DURATION, duration)  // ADD THIS LINE
            .apply()
}

    fun loadLastTrack(context: Context): LastTrack? {
    val p = prefs(context)
    val id = p.getString(KEY_LAST_TRACK_ID, null) ?: return null
    val pos = p.getLong(KEY_LAST_TRACK_POS, 0L)
    val title = p.getString(KEY_LAST_TRACK_TITLE, null)
    val artist = p.getString(KEY_LAST_TRACK_ARTIST, null)
    val art = p.getString(KEY_LAST_TRACK_ART, null)
    val playing = p.getBoolean(KEY_LAST_IS_PLAYING, false)
    val albumId = p.getString(KEY_LAST_TRACK_ALBUM_ID, null)
    val albumName = p.getString(KEY_LAST_TRACK_ALBUM_NAME, null)
    val duration = p.getLong(KEY_LAST_TRACK_DURATION, 0L)  // ADD THIS LINE
    return LastTrack(id, pos, title, artist, art, playing, albumId, albumName, duration)  // ADD duration parameter
}

    fun saveDownloadDirectory(context: Context, uriString: String?) {
        prefs(context).edit().putString(KEY_DOWNLOAD_DIR, uriString).apply()
    }

    fun loadDownloadDirectory(context: Context): String? {
        return prefs(context).getString(KEY_DOWNLOAD_DIR, null)
    }

    // Theme mode: 0 = follow system, 1 = light, 2 = dark
    fun saveThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    fun loadThemeMode(context: Context, default: Int = 0): Int {
        return prefs(context).getInt(KEY_THEME_MODE, default)
    }

    data class LastTrack(
        val id: String,
        val posMs: Long,
        val title: String?,
        val artist: String?,
        val artUrl: String?,
        val wasPlaying: Boolean,
        val albumId: String? = null,
        val albumName: String? = null,
        val duration: Long = 0L  // ADD THIS PARAMETER
)

    // Add these methods to your existing SessionStore object/class

    fun saveShowRomanization(context: Context, show: Boolean) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_romanization", show).apply()
    }

    fun loadShowRomanization(context: Context, default: Boolean = true): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("show_romanization", default)
    }

    fun saveShowTranslation(context: Context, show: Boolean) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_translation", show).apply()
    }

    fun loadShowTranslation(context: Context, default: Boolean = true): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("show_translation", default)
    }

    // Last.fm session storage
    fun saveLastFmSession(context: Context, username: String, sessionKey: String) {
        val prefs = context.getSharedPreferences("moniq_prefs", Context.MODE_PRIVATE)
        prefs.edit()
                .putString("lastfm_username", username)
                .putString("lastfm_session_key", sessionKey)
                .apply()
    }

    fun loadLastFmSession(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences("moniq_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("lastfm_username", null)
        val sessionKey = prefs.getString("lastfm_session_key", null)
        return if (username != null && sessionKey != null) {
            Pair(username, sessionKey)
        } else {
            null
        }
    }

    fun clearLastFmSession(context: Context) {
        val prefs = context.getSharedPreferences("moniq_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("lastfm_username").remove("lastfm_session_key").apply()
    }
}
