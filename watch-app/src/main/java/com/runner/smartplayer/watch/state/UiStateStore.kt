package com.runner.smartplayer.watch.state

import com.runner.smartplayer.watch.model.AppUiState
import com.runner.smartplayer.watch.model.HeartRateSnapshot
import com.runner.smartplayer.watch.model.LibrarySummary
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.PlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class UiStateStore : StateStore {
    private val mutableState = MutableStateFlow(AppUiState())
    override val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    // Write ownership is explicit:
    // - librarySummary: library loading flow
    // - playbackMode / adaptive / debug flags: mode manager
    // - playback snapshot fields: playback service/controller
    // - heartRate snapshot: sensor + debug snapshot publisher

    override fun updateLibrary(summary: LibrarySummary) {
        mutableState.update { it.copy(librarySummary = summary) }
    }

    override fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        mutableState.update { it.copy(playback = transform(it.playback)) }
    }

    override fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot) {
        mutableState.update { current -> current.copy(heartRate = transform(current.heartRate)) }
    }

    override fun setMessage(message: String) {
        updatePlayback { it.copy(lastMessage = message) }
    }

    override fun setAdaptiveEnabled(enabled: Boolean) {
        updatePlayback { it.copy(isAdaptiveEnabled = enabled) }
    }

    override fun setDebugModeEnabled(enabled: Boolean) {
        updatePlayback { it.copy(isDebugModeEnabled = enabled) }
    }

    override fun setPlaybackMode(mode: PlaybackMode) {
        mutableState.update { current ->
            current.copy(
                playback = current.playback.copy(playbackMode = mode),
                heartRate = current.heartRate.copy(mode = mode),
            )
        }
    }
}
