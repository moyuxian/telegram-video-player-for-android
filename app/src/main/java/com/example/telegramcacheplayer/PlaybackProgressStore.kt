package com.example.telegramcacheplayer

import android.content.Context

/**
 * Persists per-file playback position in SharedPreferences. A video is
 * considered "finished" when it was within 5 seconds of the end; for finished
 * videos we return 0 so reopening starts from the beginning.
 */
class PlaybackProgressStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    fun getPosition(path: String, durationMs: Long): Long {
        val saved = prefs.getLong(key(path), 0L)
        if (saved <= 0) return 0L
        if (durationMs > 0 && saved >= durationMs - FINISHED_TAIL_MS) return 0L
        return saved
    }

    fun savePosition(path: String, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) {
            prefs.edit().remove(key(path)).apply()
            return
        }
        if (durationMs > 0 && positionMs >= durationMs - FINISHED_TAIL_MS) {
            prefs.edit().remove(key(path)).apply()
            return
        }
        prefs.edit().putLong(key(path), positionMs).apply()
    }

    fun allPositions(): Map<String, Long> =
        prefs.all.mapNotNull { (k, v) ->
            if (k.startsWith(KEY_PREFIX) && v is Long) {
                k.removePrefix(KEY_PREFIX) to v
            } else null
        }.toMap()

    private fun key(path: String) = KEY_PREFIX + path

    companion object {
        private const val KEY_PREFIX = "pos:"
        private const val FINISHED_TAIL_MS = 5_000L
    }
}
