package com.runner.smartplayer.watch.state

import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.QueueMode
import org.junit.Assert.assertEquals
import org.junit.Test

class UiStateStoreTest {
    @Test
    fun updateHeartRateDoesNotImplicitlyOverwritePlaybackMode() {
        val store = UiStateStore()
        store.setPlaybackMode(PlaybackMode.EXCITED)

        store.updateHeartRate {
            it.copy(mode = PlaybackMode.CALM, bpm = 120)
        }

        val state = store.state.value
        assertEquals(PlaybackMode.EXCITED, state.playback.playbackMode)
        assertEquals(PlaybackMode.CALM, state.heartRate.mode)
        assertEquals(120, state.heartRate.bpm)
    }

    @Test
    fun setPlaybackModeKeepsPlaybackAndHeartRateModeAligned() {
        val store = UiStateStore()

        store.setPlaybackMode(PlaybackMode.EXCITED)

        val state = store.state.value
        assertEquals(PlaybackMode.EXCITED, state.playback.playbackMode)
        assertEquals(PlaybackMode.EXCITED, state.heartRate.mode)
    }

    @Test
    fun constructorSeedsQueueModeAndHeartRateThreshold() {
        val store = UiStateStore(
            initialQueueMode = QueueMode.LIST_LOOP,
            initialHeartRateThreshold = 170,
        )

        val state = store.state.value
        assertEquals(QueueMode.LIST_LOOP, state.playback.queueMode)
        assertEquals(170, state.heartRate.thresholdBpm)
    }

    @Test
    fun settersUpdateRememberedQueueModeAndHeartRateThreshold() {
        val store = UiStateStore()

        store.setQueueMode(QueueMode.SINGLE_REPEAT)
        store.setHeartRateThreshold(165)

        val state = store.state.value
        assertEquals(QueueMode.SINGLE_REPEAT, state.playback.queueMode)
        assertEquals(165, state.heartRate.thresholdBpm)
    }
}
