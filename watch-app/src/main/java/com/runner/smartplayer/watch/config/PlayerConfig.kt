package com.runner.smartplayer.watch.config

data class PlayerConfig(
    val heartRateThreshold: Int = 180,
    val stableSampleCount: Int = 10,
    val fadeSteps: Int = 12,
    val fadeStepDelayMs: Long = 50L,
    val tickerIntervalPlayingMs: Long = 300L,
    val tickerIntervalIdleMs: Long = 1000L,
    val notificationChannelId: String = "runner_player_playback",
    val notificationId: Int = 1001,
)

val DEFAULT_PLAYER_CONFIG = PlayerConfig()
