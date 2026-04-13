package com.example.telegramcacheplayer

import android.content.Context

/**
 * Persists the user's favorite videos by absolute file path.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("favorite_videos", Context.MODE_PRIVATE)

    fun allFavorites(): Set<String> =
        prefs.all.mapNotNullTo(mutableSetOf()) { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value == true) {
                key.removePrefix(KEY_PREFIX)
            } else {
                null
            }
        }

    fun isFavorite(path: String): Boolean = prefs.getBoolean(key(path), false)

    fun toggle(path: String): Boolean {
        val favorite = !isFavorite(path)
        setFavorite(path, favorite)
        return favorite
    }

    fun remove(path: String) {
        prefs.edit().remove(key(path)).apply()
    }

    private fun setFavorite(path: String, favorite: Boolean) {
        if (favorite) {
            prefs.edit().putBoolean(key(path), true).apply()
        } else {
            prefs.edit().remove(key(path)).apply()
        }
    }

    private fun key(path: String) = KEY_PREFIX + path

    companion object {
        private const val KEY_PREFIX = "fav:"
    }
}
