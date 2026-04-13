package com.example.telegramcacheplayer

import android.content.Context

/**
 * Persists lightweight playback preferences shared across sessions.
 */
class PlaybackSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("playback_settings", Context.MODE_PRIVATE)

    fun getHoldBoostSpeed(): Float {
        val saved = prefs.getFloat(KEY_HOLD_BOOST_SPEED, DEFAULT_HOLD_BOOST_SPEED)
        return if (saved > 1f) saved else DEFAULT_HOLD_BOOST_SPEED
    }

    fun setHoldBoostSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_HOLD_BOOST_SPEED, speed).apply()
    }

    companion object {
        private const val KEY_HOLD_BOOST_SPEED = "hold_boost_speed"
        private const val DEFAULT_HOLD_BOOST_SPEED = 2f
    }
}
