package com.runner.smartplayer.watch.data

import android.content.Context
import android.content.SharedPreferences
import com.runner.smartplayer.watch.config.DEFAULT_HEART_RATE_THRESHOLD
import com.runner.smartplayer.watch.config.MAX_HEART_RATE_THRESHOLD
import com.runner.smartplayer.watch.config.MIN_HEART_RATE_THRESHOLD
import com.runner.smartplayer.watch.model.QueueMode

data class PlayerPreferences(
    val queueMode: QueueMode = QueueMode.SHUFFLE,
    val heartRateThreshold: Int = DEFAULT_HEART_RATE_THRESHOLD,
)

class PlayerPreferencesRepository(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ),
) {
    fun load(): PlayerPreferences {
        val queueMode = preferences.getString(KEY_QUEUE_MODE, null)
            ?.let(QueueMode::fromValue)
            ?: QueueMode.SHUFFLE
        val heartRateThreshold = sanitizeThreshold(
            preferences.getInt(KEY_HEART_RATE_THRESHOLD, DEFAULT_HEART_RATE_THRESHOLD)
        )
        return PlayerPreferences(
            queueMode = queueMode,
            heartRateThreshold = heartRateThreshold,
        )
    }

    fun saveQueueMode(queueMode: QueueMode) {
        preferences.edit()
            .putString(KEY_QUEUE_MODE, queueMode.value())
            .apply()
    }

    fun saveHeartRateThreshold(threshold: Int): Int {
        val sanitized = sanitizeThreshold(threshold)
        preferences.edit()
            .putInt(KEY_HEART_RATE_THRESHOLD, sanitized)
            .apply()
        return sanitized
    }

    private fun sanitizeThreshold(threshold: Int): Int {
        return threshold.coerceIn(MIN_HEART_RATE_THRESHOLD, MAX_HEART_RATE_THRESHOLD)
    }

    private companion object {
        const val PREFERENCES_NAME = "runner_player_preferences"
        const val KEY_QUEUE_MODE = "queue_mode"
        const val KEY_HEART_RATE_THRESHOLD = "heart_rate_threshold"
    }
}
