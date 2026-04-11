package com.runner.smartplayer.watch.state

import android.content.Context
import com.runner.smartplayer.watch.config.PlayerConfig
import com.runner.smartplayer.watch.data.LibraryScanner
import com.runner.smartplayer.watch.data.ManifestRepository
import com.runner.smartplayer.watch.model.HeartRateSnapshot
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.player.AdaptiveQueueEngine
import com.runner.smartplayer.watch.sensor.HeartRateController
import com.runner.smartplayer.watch.sensor.HeartRateModeClassifier
import com.runner.smartplayer.watch.sensor.HeartRateSensorController

object AppGraph {
    private lateinit var appContext: Context

    lateinit var manifestRepository: ManifestRepository
        private set

    val uiStateStore: StateStore by lazy { UiStateStore() }
    val playerConfig: PlayerConfig by lazy { PlayerConfig() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        manifestRepository = ManifestRepository(LibraryScanner())
    }

    fun createQueueEngine(): AdaptiveQueueEngine = AdaptiveQueueEngine()

    fun createHeartRateController(
        onHeartRateSnapshot: (HeartRateSnapshot) -> Unit,
        onStableModeChanged: (PlaybackMode) -> Unit,
    ): HeartRateController {
        return HeartRateSensorController(
            context = appContext,
            classifier = HeartRateModeClassifier(
                threshold = playerConfig.heartRateThreshold,
                stableSamples = playerConfig.stableSampleCount,
            ),
            onHeartRateSnapshot = onHeartRateSnapshot,
            onStableModeChanged = onStableModeChanged,
        )
    }

    fun requireContext(): Context = appContext
}
