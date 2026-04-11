package com.runner.smartplayer.watch.state

import com.runner.smartplayer.watch.model.PlaybackMode
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
}
