package com.runner.smartplayer.watch.config

const val DEFAULT_HEART_RATE_THRESHOLD = 160
const val MIN_HEART_RATE_THRESHOLD = 100
const val MAX_HEART_RATE_THRESHOLD = 220
const val HEART_RATE_THRESHOLD_STEP = 5

data class PlayerConfig(
    val heartRateThreshold: Int = DEFAULT_HEART_RATE_THRESHOLD,
    val stableSampleCount: Int = 10,
    val fadeSteps: Int = 12,
    val fadeStepDelayMs: Long = 50L,
    val tickerIntervalPlayingMs: Long = 300L,
    val tickerIntervalIdleMs: Long = 1000L,
    val notificationChannelId: String = "runner_player_playback",
    val notificationId: Int = 1001,
)

val DEFAULT_PLAYER_CONFIG = PlayerConfig()
