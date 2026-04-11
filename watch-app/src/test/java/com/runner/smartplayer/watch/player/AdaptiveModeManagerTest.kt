package com.runner.smartplayer.watch.player

import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.TrackLabel
import com.runner.smartplayer.watch.sensor.HeartRateController
import com.runner.smartplayer.watch.state.UiStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class AdaptiveModeManagerTest {
    @Test
    fun setAdaptiveEnabledUpdatesStateAndPublishesMessage() {
        val store = UiStateStore()
        val sensor = FakeHeartRateController()
        val messages = testMessages()
        var refreshed = 0
        var lastMessage = ""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val manager = AdaptiveModeManager(
            serviceScope = scope,
            stateStore = store,
            sensorController = sensor,
            onRefreshPlaylist = { refreshed += 1 },
            onResetPlaybackVolume = {},
            onCurrentTrackLabel = { null },
            onHasTracksForLabel = { false },
            onNextTrackForLabel = { null },
            onSwitchTrackWithFade = { _, _ -> },
            onUpdateMessage = { lastMessage = it },
            onPublishDebugHeartRateSnapshot = { _, _ -> },
        )

        manager.setAdaptiveEnabled(false, messages)

        assertFalse(manager.adaptiveEnabled)
        assertFalse(store.state.value.playback.isAdaptiveEnabled)
        assertEquals(1, refreshed)
        assertEquals(messages.adaptiveDisabled, lastMessage)
        scope.cancel()
    }

    @Test
    fun enablingDebugStopsSensorAndPublishesSnapshot() {
        val store = UiStateStore()
        val sensor = FakeHeartRateController()
        val messages = testMessages()
        var resetPlaybackVolume = 0
        var publishedSnapshot: Pair<PlaybackMode, String>? = null
        var lastMessage = ""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val manager = AdaptiveModeManager(
            serviceScope = scope,
            stateStore = store,
            sensorController = sensor,
            onRefreshPlaylist = {},
            onResetPlaybackVolume = { resetPlaybackVolume += 1 },
            onCurrentTrackLabel = { null },
            onHasTracksForLabel = { false },
            onNextTrackForLabel = { null },
            onSwitchTrackWithFade = { _, _ -> },
            onUpdateMessage = { lastMessage = it },
            onPublishDebugHeartRateSnapshot = { mode, message ->
                publishedSnapshot = mode to message
            },
        )

        manager.setDebugModeEnabled(true, messages)

        assertTrue(manager.debugModeEnabled)
        assertTrue(store.state.value.playback.isDebugModeEnabled)
        assertEquals(1, sensor.stopCalls)
        assertEquals(1, resetPlaybackVolume)
        assertEquals(PlaybackMode.CALM to messages.debugHeartRate, publishedSnapshot)
        assertEquals(messages.debugEnabledHint, lastMessage)
        scope.cancel()
    }

    @Test
    fun debugPlaybackToggleWithoutAdaptiveSkipsFadeSwitch() {
        val store = UiStateStore()
        val sensor = FakeHeartRateController()
        val messages = testMessages()
        var lastMessage = ""
        var fadeSwitchCalls = 0
        var publishedSnapshot: Pair<PlaybackMode, String>? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val manager = AdaptiveModeManager(
            serviceScope = scope,
            stateStore = store,
            sensorController = sensor,
            onRefreshPlaylist = {},
            onResetPlaybackVolume = {},
            onCurrentTrackLabel = { null },
            onHasTracksForLabel = { false },
            onNextTrackForLabel = { null },
            onSwitchTrackWithFade = { _, _ -> fadeSwitchCalls += 1 },
            onUpdateMessage = { lastMessage = it },
            onPublishDebugHeartRateSnapshot = { mode, message ->
                publishedSnapshot = mode to message
            },
        )

        manager.setDebugModeEnabled(true, messages)
        manager.setAdaptiveEnabled(false, messages)
        manager.toggleDebugPlaybackMode(messages)

        assertEquals(0, fadeSwitchCalls)
        assertEquals(PlaybackMode.EXCITED, manager.currentMode)
        assertEquals(PlaybackMode.EXCITED, store.state.value.playback.playbackMode)
        assertEquals(
            PlaybackMode.EXCITED to messages.debugHeartRate,
            publishedSnapshot,
        )
        assertEquals(messages.debugSwitchedModeNoAdaptive(PlaybackMode.EXCITED), lastMessage)
        scope.cancel()
    }

    @Test
    fun stableModeChangeRequestsFadeSwitchWhenTargetTrackExists() {
        val store = UiStateStore()
        val sensor = FakeHeartRateController()
        val messages = testMessages()
        val excitedTrack = buildTrack("excited", TrackLabel.EXCITED)
        var switchedToTrack: LocalTrack? = null
        var switchedMessage = ""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val manager = AdaptiveModeManager(
            serviceScope = scope,
            stateStore = store,
            sensorController = sensor,
            onRefreshPlaylist = {},
            onResetPlaybackVolume = {},
            onCurrentTrackLabel = { TrackLabel.CALM },
            onHasTracksForLabel = { label -> label == TrackLabel.EXCITED },
            onNextTrackForLabel = { excitedTrack },
            onSwitchTrackWithFade = { track, message ->
                switchedToTrack = track
                switchedMessage = message
            },
            onUpdateMessage = {},
            onPublishDebugHeartRateSnapshot = { _, _ -> },
        )

        manager.onStableModeChanged(PlaybackMode.EXCITED, messages)

        assertEquals(PlaybackMode.EXCITED, manager.currentMode)
        assertEquals(excitedTrack.id, switchedToTrack?.id)
        assertEquals(messages.settledMode(PlaybackMode.EXCITED), switchedMessage)
        scope.cancel()
    }

    private fun testMessages(): AdaptiveModeMessages {
        return AdaptiveModeMessages(
            adaptiveEnabled = "adaptive on",
            adaptiveDisabled = "adaptive off",
            debugEnabledHint = "debug on",
            debugDisabled = "debug off",
            debugDisabledRestore = "debug restored",
            enableDebugFirst = "enable debug first",
            debugHeartRate = "debug heart rate",
            settledMode = { mode -> "settled ${mode.name}" },
            debugSwitchedMode = { mode -> "debug ${mode.name}" },
            debugSwitchedModeNoAdaptive = { mode -> "debug no adaptive ${mode.name}" },
            noTracksForLabel = { label -> "no ${label.name}" },
        )
    }

    private fun buildTrack(id: String, label: TrackLabel): LocalTrack {
        return LocalTrack(
            id = id,
            relativePath = "tracks/$id.mp3",
            sourceFileName = "$id.mp3",
            title = id,
            artist = "artist",
            durationMs = 1000L,
            bpm = 120.0,
            energyScore = 0.5,
            suggestedLabel = label,
            finalLabel = label,
            sizeBytes = 10L,
            modifiedAt = Instant.parse("2026-04-10T12:00:00Z"),
            file = File(id),
        )
    }

    private class FakeHeartRateController : HeartRateController {
        var startCalls = 0
        var stopCalls = 0
        var resetCalls = 0

        override fun start() {
            startCalls += 1
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun resetClassifier(mode: PlaybackMode) {
            resetCalls += 1
        }
    }
}
