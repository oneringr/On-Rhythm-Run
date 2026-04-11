package com.runner.smartplayer.watch.ui

import android.os.Bundle
import android.view.View
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.model.HeartRateSnapshot
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.player.ServiceIntents
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class RunModeFragment : Fragment(R.layout.fragment_run_mode) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adaptiveModeButton = view.findViewById<MaterialButton>(R.id.adaptiveModeButton)
        val heartRateValue = view.findViewById<TextView>(R.id.runModeHeartRateValue)
        val heartRateStatus = view.findViewById<TextView>(R.id.runModeSensorStatus)
        val currentMode = view.findViewById<TextView>(R.id.currentModeValue)
        val debugModeButton = view.findViewById<MaterialButton>(R.id.debugModeButton)

        adaptiveModeButton.setOnClickListener {
            val enabled = !(AppGraph.uiStateStore.state.value.playback.isAdaptiveEnabled)
            ServiceIntents.send(
                requireContext(),
                ServiceIntents.ACTION_TOGGLE_ADAPTIVE,
                adaptiveEnabled = enabled,
            )
        }
        debugModeButton.setOnClickListener {
            val enabled = !(AppGraph.uiStateStore.state.value.playback.isDebugModeEnabled)
            ServiceIntents.send(
                requireContext(),
                ServiceIntents.ACTION_TOGGLE_DEBUG_MODE,
                debugEnabled = enabled,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.uiStateStore.state
                    .map { state ->
                        RunModeViewState(
                            heartRate = state.heartRate,
                            isAdaptiveEnabled = state.playback.isAdaptiveEnabled,
                            isDebugModeEnabled = state.playback.isDebugModeEnabled,
                        )
                    }
                    .distinctUntilChanged()
                    .collect { viewState ->
                        heartRateValue.text = viewState.heartRate.bpm?.let { "$it BPM" } ?: "-- BPM"
                        heartRateStatus.text = viewState.heartRate.message
                        currentMode.text = when (viewState.heartRate.mode) {
                            PlaybackMode.CALM -> getString(R.string.label_calm)
                            PlaybackMode.EXCITED -> getString(R.string.label_excited)
                        }
                        adaptiveModeButton.text = if (viewState.isAdaptiveEnabled) {
                            getString(R.string.adaptive_mode_enabled)
                        } else {
                            getString(R.string.adaptive_mode_disabled)
                        }
                        debugModeButton.text = if (viewState.isDebugModeEnabled) {
                            getString(R.string.debug_mode_enabled)
                        } else {
                            getString(R.string.debug_mode_disabled)
                        }
                    }
            }
        }
    }

    private data class RunModeViewState(
        val heartRate: HeartRateSnapshot,
        val isAdaptiveEnabled: Boolean,
        val isDebugModeEnabled: Boolean,
    )
}
