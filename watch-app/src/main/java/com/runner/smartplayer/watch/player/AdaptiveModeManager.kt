package com.runner.smartplayer.watch.player

import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.TrackLabel
import com.runner.smartplayer.watch.sensor.HeartRateController
import com.runner.smartplayer.watch.state.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AdaptiveModeMessages(
    val adaptiveEnabled: String,
    val adaptiveDisabled: String,
    val debugEnabledHint: String,
    val debugDisabled: String,
    val debugDisabledRestore: String,
    val enableDebugFirst: String,
    val debugHeartRate: String,
    val settledMode: (PlaybackMode) -> String,
    val debugSwitchedMode: (PlaybackMode) -> String,
    val debugSwitchedModeNoAdaptive: (PlaybackMode) -> String,
    val noTracksForLabel: (TrackLabel) -> String,
)

class AdaptiveModeManager(
    private val serviceScope: CoroutineScope,
    private val stateStore: StateStore,
    private val sensorController: HeartRateController,
    private val onRefreshPlaylist: () -> Unit,
    private val onResetPlaybackVolume: () -> Unit,
    private val onCurrentTrackLabel: () -> TrackLabel?,
    private val onHasTracksForLabel: (TrackLabel) -> Boolean,
    private val onNextTrackForLabel: (TrackLabel) -> LocalTrack?,
    private val onSwitchTrackWithFade: suspend (LocalTrack?, String) -> Unit,
    private val onUpdateMessage: (String) -> Unit,
    private val onPublishDebugHeartRateSnapshot: (PlaybackMode, String) -> Unit,
    private val isModeSwitchFeedbackEnabled: () -> Boolean,
    private val onModeSwitchFeedback: (PlaybackMode, PlaybackMode) -> Unit,
) {
    var adaptiveEnabled: Boolean = true
        private set

    var debugModeEnabled: Boolean = false
        private set

    var currentMode: PlaybackMode = PlaybackMode.CALM
        private set

    private var fadeJob: Job? = null

    fun onStableModeChanged(mode: PlaybackMode, messages: AdaptiveModeMessages) {
        if (debugModeEnabled) return
        applyPlaybackMode(mode, messages.settledMode(mode), messages)
    }

    fun setAdaptiveEnabled(enabled: Boolean, messages: AdaptiveModeMessages) {
        adaptiveEnabled = enabled
        stateStore.setAdaptiveEnabled(enabled)
        onRefreshPlaylist()
        onUpdateMessage(
            if (enabled) {
                messages.adaptiveEnabled
            } else {
                messages.adaptiveDisabled
            }
        )
    }

    fun setDebugModeEnabled(enabled: Boolean, messages: AdaptiveModeMessages) {
        if (debugModeEnabled == enabled) {
            onUpdateMessage(
                if (enabled) {
                    messages.debugEnabledHint
                } else {
                    messages.debugDisabled
                }
            )
            return
        }

        debugModeEnabled = enabled
        stateStore.setDebugModeEnabled(enabled)
        if (enabled) {
            fadeJob?.cancel()
            fadeJob = null
            onResetPlaybackVolume()
            sensorController.stop()
            onPublishDebugHeartRateSnapshot(currentMode, messages.debugHeartRate)
            onUpdateMessage(messages.debugEnabledHint)
        } else {
            currentMode = PlaybackMode.CALM
            stateStore.setPlaybackMode(PlaybackMode.CALM)
            onRefreshPlaylist()
            sensorController.resetClassifier()
            sensorController.start()
            onUpdateMessage(messages.debugDisabledRestore)
        }
    }

    fun toggleDebugPlaybackMode(messages: AdaptiveModeMessages) {
        if (!debugModeEnabled) {
            onUpdateMessage(messages.enableDebugFirst)
            return
        }

        val nextMode = when (currentMode) {
            PlaybackMode.CALM -> PlaybackMode.EXCITED
            PlaybackMode.EXCITED -> PlaybackMode.CALM
        }
        applyPlaybackMode(
            nextMode,
            if (adaptiveEnabled) {
                messages.debugSwitchedMode(nextMode)
            } else {
                messages.debugSwitchedModeNoAdaptive(nextMode)
            },
            messages,
        )
    }

    fun cancel() {
        fadeJob?.cancel()
        fadeJob = null
    }

    private fun applyPlaybackMode(
        mode: PlaybackMode,
        settledMessage: String,
        messages: AdaptiveModeMessages,
    ) {
        val previousMode = currentMode
        currentMode = mode
        stateStore.setPlaybackMode(mode)
        if (debugModeEnabled) {
            onPublishDebugHeartRateSnapshot(mode, messages.debugHeartRate)
        }
        if (
            previousMode != mode &&
            (adaptiveEnabled || debugModeEnabled) &&
            isModeSwitchFeedbackEnabled()
        ) {
            onModeSwitchFeedback(previousMode, mode)
        }
        if (!adaptiveEnabled) {
            onUpdateMessage(settledMessage)
            return
        }

        val targetLabel = mode.toLabel()
        onRefreshPlaylist()
        if (onCurrentTrackLabel() == targetLabel) {
            onUpdateMessage(settledMessage)
            return
        }

        if (!onHasTracksForLabel(targetLabel)) {
            onUpdateMessage(messages.noTracksForLabel(targetLabel))
            return
        }

        val targetTrack = onNextTrackForLabel(targetLabel)
        fadeJob?.cancel()
        fadeJob = serviceScope.launch {
            onSwitchTrackWithFade(targetTrack, settledMessage)
        }
    }
}
