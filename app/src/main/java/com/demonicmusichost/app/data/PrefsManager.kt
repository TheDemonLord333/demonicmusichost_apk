package com.demonicmusichost.app.data

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {

    private const val PREFS_NAME = "dmh_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_SPOTIFY_TOKEN = "spotify_token"
    private const val KEY_SPOTIFY_REFRESH = "spotify_refresh"
    private const val KEY_SPOTIFY_EXPIRES = "spotify_expires"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var spotifyToken: String?
        get() = prefs.getString(KEY_SPOTIFY_TOKEN, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_SPOTIFY_TOKEN, value).apply()
            else prefs.edit().remove(KEY_SPOTIFY_TOKEN).apply()
        }

    var spotifyRefresh: String?
        get() = prefs.getString(KEY_SPOTIFY_REFRESH, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_SPOTIFY_REFRESH, value).apply()
            else prefs.edit().remove(KEY_SPOTIFY_REFRESH).apply()
        }

    var spotifyExpires: Long
        get() = prefs.getLong(KEY_SPOTIFY_EXPIRES, 0L)
        set(value) = prefs.edit().putLong(KEY_SPOTIFY_EXPIRES, value).apply()

    fun isSpotifyValid(): Boolean {
        val token = spotifyToken ?: return false
        val expires = spotifyExpires
        return token.isNotEmpty() && (expires == 0L || expires > System.currentTimeMillis() + 60_000)
    }

    fun saveSpotifyTokens(token: String, refresh: String?, expires: Long) {
        spotifyToken = token
        spotifyRefresh = refresh
        spotifyExpires = expires
    }

    fun clearSpotify() {
        prefs.edit()
            .remove(KEY_SPOTIFY_TOKEN)
            .remove(KEY_SPOTIFY_REFRESH)
            .remove(KEY_SPOTIFY_EXPIRES)
            .apply()
    }

    const val DEFAULT_SERVER_URL = "http://10.0.2.2:3001"
}
