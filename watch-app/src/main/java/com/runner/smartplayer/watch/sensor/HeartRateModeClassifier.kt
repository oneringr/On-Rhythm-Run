package com.runner.smartplayer.watch.sensor

import com.runner.smartplayer.watch.model.PlaybackMode

class HeartRateModeClassifier(
    private val threshold: Int = 180,
    private val stableSamples: Int = 10,
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
