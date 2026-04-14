package com.runner.smartplayer.watch.state

import com.runner.smartplayer.watch.model.AppUiState
import com.runner.smartplayer.watch.model.HeartRateSnapshot
import com.runner.smartplayer.watch.model.LibrarySummary
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.PlaybackSnapshot
import com.runner.smartplayer.watch.model.QueueMode
import kotlinx.coroutines.flow.StateFlow

interface StateStore {
    val state: StateFlow<AppUiState>

    fun updateLibrary(summary: LibrarySummary)
    fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot)
    fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot)
    fun setMessage(message: String)
    fun setAdaptiveEnabled(enabled: Boolean)
    fun setDebugModeEnabled(enabled: Boolean)
    fun setPlaybackMode(mode: PlaybackMode)
    fun setQueueMode(mode: QueueMode)
    fun setHeartRateThreshold(threshold: Int)
}
