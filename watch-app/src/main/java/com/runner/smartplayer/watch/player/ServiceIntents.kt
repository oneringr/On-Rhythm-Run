package com.runner.smartplayer.watch.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ServiceIntents {
    const val ACTION_BOOT = "com.runner.smartplayer.watch.action.BOOT"
    const val ACTION_TOGGLE_PLAY = "com.runner.smartplayer.watch.action.TOGGLE_PLAY"
    const val ACTION_NEXT = "com.runner.smartplayer.watch.action.NEXT"
    const val ACTION_PREVIOUS = "com.runner.smartplayer.watch.action.PREVIOUS"
    const val ACTION_RESCAN = "com.runner.smartplayer.watch.action.RESCAN"
    const val ACTION_TOGGLE_ADAPTIVE = "com.runner.smartplayer.watch.action.TOGGLE_ADAPTIVE"
    const val ACTION_TOGGLE_DEBUG_MODE = "com.runner.smartplayer.watch.action.TOGGLE_DEBUG_MODE"
    const val ACTION_TOGGLE_DEBUG_PLAYBACK_MODE = "com.runner.smartplayer.watch.action.TOGGLE_DEBUG_PLAYBACK_MODE"
    const val ACTION_VOLUME_UP = "com.runner.smartplayer.watch.action.VOLUME_UP"
    const val ACTION_VOLUME_DOWN = "com.runner.smartplayer.watch.action.VOLUME_DOWN"
    const val ACTION_CYCLE_QUEUE_MODE = "com.runner.smartplayer.watch.action.CYCLE_QUEUE_MODE"
    const val ACTION_SET_HEART_RATE_THRESHOLD = "com.runner.smartplayer.watch.action.SET_HEART_RATE_THRESHOLD"
    const val ACTION_PLAY_TRACK_BY_ID = "com.runner.smartplayer.watch.action.PLAY_TRACK_BY_ID"
    const val EXTRA_ADAPTIVE_ENABLED = "adaptive_enabled"
    const val EXTRA_DEBUG_ENABLED = "debug_enabled"
    const val EXTRA_TRACK_ID = "track_id"
    const val EXTRA_HEART_RATE_THRESHOLD = "heart_rate_threshold"

    fun send(
        context: Context,
        action: String,
        adaptiveEnabled: Boolean? = null,
        debugEnabled: Boolean? = null,
        heartRateThreshold: Int? = null,
    ) {
        val intent = Intent(context, AdaptivePlaybackService::class.java).apply {
            this.action = action
            adaptiveEnabled?.let { putExtra(EXTRA_ADAPTIVE_ENABLED, it) }
            debugEnabled?.let { putExtra(EXTRA_DEBUG_ENABLED, it) }
            heartRateThreshold?.let { putExtra(EXTRA_HEART_RATE_THRESHOLD, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun sendPlayTrackById(context: Context, trackId: String) {
        val intent = Intent(context, AdaptivePlaybackService::class.java).apply {
            action = ACTION_PLAY_TRACK_BY_ID
            putExtra(EXTRA_TRACK_ID, trackId)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
