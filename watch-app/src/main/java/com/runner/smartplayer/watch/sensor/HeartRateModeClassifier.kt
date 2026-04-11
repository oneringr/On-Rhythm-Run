package com.runner.smartplayer.watch.sensor

import com.runner.smartplayer.watch.config.DEFAULT_PLAYER_CONFIG
import com.runner.smartplayer.watch.model.PlaybackMode

class HeartRateModeClassifier(
    private val threshold: Int = DEFAULT_PLAYER_CONFIG.heartRateThreshold,
    private val stableSamples: Int = DEFAULT_PLAYER_CONFIG.stableSampleCount,
) {
    private var aboveThresholdCount = 0
    private var belowThresholdCount = 0
    private var currentMode: PlaybackMode = PlaybackMode.CALM

    fun currentMode(): PlaybackMode = currentMode

    fun submitSample(bpm: Int?, sensorReady: Boolean): PlaybackMode? {
        if (!sensorReady || bpm == null) {
            return null
        }

        if (bpm >= threshold) {
            aboveThresholdCount += 1
            belowThresholdCount = 0
        } else {
            belowThresholdCount += 1
            aboveThresholdCount = 0
        }

        val nextMode = when {
            aboveThresholdCount >= stableSamples -> PlaybackMode.EXCITED
            belowThresholdCount >= stableSamples -> PlaybackMode.CALM
            else -> currentMode
        }

        if (nextMode != currentMode) {
            currentMode = nextMode
            return nextMode
        }
        return null
    }

    fun reset(mode: PlaybackMode = PlaybackMode.CALM) {
        currentMode = mode
        aboveThresholdCount = 0
        belowThresholdCount = 0
    }
}
