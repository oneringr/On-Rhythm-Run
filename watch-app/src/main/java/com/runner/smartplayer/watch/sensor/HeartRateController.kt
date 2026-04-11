package com.runner.smartplayer.watch.sensor

import com.runner.smartplayer.watch.model.PlaybackMode

interface HeartRateController {
    fun start()
    fun stop()
    fun resetClassifier(mode: PlaybackMode = PlaybackMode.CALM)
}
