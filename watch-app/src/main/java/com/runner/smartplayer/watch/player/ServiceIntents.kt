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
    const val EXTRA_ADAPTIVE_ENABLED = "adaptive_enabled"

    fun send(context: Context, action: String, adaptiveEnabled: Boolean? = null) {
        val intent = Intent(context, AdaptivePlaybackService::class.java).apply {
            this.action = action
            adaptiveEnabled?.let { putExtra(EXTRA_ADAPTIVE_ENABLED, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
