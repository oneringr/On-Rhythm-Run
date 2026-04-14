package com.runner.smartplayer.watch.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.runner.smartplayer.watch.model.PlaybackMode

class ModeSwitchHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun playModeSwitchFeedback(previousMode: PlaybackMode, nextMode: PlaybackMode) {
        val targetVibrator = vibrator ?: return
        if (!targetVibrator.hasVibrator()) {
            return
        }

        val effect = when {
            previousMode == PlaybackMode.CALM && nextMode == PlaybackMode.EXCITED -> {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 40, 60, 40, 60),
                    intArrayOf(0, 255, 0, 255, 0, 255),
                    -1,
                )
            }

            previousMode == PlaybackMode.EXCITED && nextMode == PlaybackMode.CALM -> {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 140, 120, 140),
                    intArrayOf(0, 180, 0, 180),
                    -1,
                )
            }

            else -> null
        } ?: return

        targetVibrator.cancel()
        targetVibrator.vibrate(
            effect,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build(),
        )
    }
}
