package com.runner.smartplayer.watch.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.player.ServiceIntents
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.launch

class RunModeFragment : Fragment(R.layout.fragment_run_mode) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adaptiveSwitch = view.findViewById<SwitchCompat>(R.id.adaptiveSwitch)
        val heartRateValue = view.findViewById<TextView>(R.id.runModeHeartRateValue)
        val heartRateStatus = view.findViewById<TextView>(R.id.runModeSensorStatus)
        val currentMode = view.findViewById<TextView>(R.id.currentModeValue)

        fun bindAdaptiveListener() {
            adaptiveSwitch.setOnCheckedChangeListener { _, isChecked ->
                ServiceIntents.send(
                    requireContext(),
                    ServiceIntents.ACTION_TOGGLE_ADAPTIVE,
                    adaptiveEnabled = isChecked,
                )
            }
        }
        bindAdaptiveListener()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.uiStateStore.state.collect { state ->
                    if (adaptiveSwitch.isChecked != state.playback.isAdaptiveEnabled) {
                        adaptiveSwitch.setOnCheckedChangeListener(null)
                        adaptiveSwitch.isChecked = state.playback.isAdaptiveEnabled
                        bindAdaptiveListener()
                    }
                    heartRateValue.text = state.heartRate.bpm?.let { "$it BPM" } ?: "-- BPM"
                    heartRateStatus.text = state.heartRate.message
                    currentMode.text = state.heartRate.mode.value()
                }
            }
        }
    }
}
