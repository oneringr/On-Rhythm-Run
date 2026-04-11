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

class UiStateStore {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    fun updateLibrary(summary: LibrarySummary) {
        mutableState.update { it.copy(librarySummary = summary) }
    }

    fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        mutableState.update { it.copy(playback = transform(it.playback)) }
    }

    fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot) {
        mutableState.update { current ->
            val updatedHeartRate = transform(current.heartRate)
            current.copy(
                heartRate = updatedHeartRate,
                playback = current.playback.copy(playbackMode = updatedHeartRate.mode),
            )
        }
    }

    fun setMessage(message: String) {
        updatePlayback { it.copy(lastMessage = message) }
    }

    fun setAdaptiveEnabled(enabled: Boolean) {
        updatePlayback { it.copy(isAdaptiveEnabled = enabled) }
    }

    fun setDebugModeEnabled(enabled: Boolean) {
        updatePlayback { it.copy(isDebugModeEnabled = enabled) }
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        mutableState.update { current ->
            current.copy(
                playback = current.playback.copy(playbackMode = mode),
                heartRate = current.heartRate.copy(mode = mode),
            )
        }
    }
}
