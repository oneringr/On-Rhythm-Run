package com.runner.smartplayer.watch.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.player.ServiceIntents
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.launch

class PlayerFragment : Fragment(R.layout.fragment_player) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val titleView = view.findViewById<TextView>(R.id.trackTitle)
        val artistView = view.findViewById<TextView>(R.id.trackArtist)
        val modeView = view.findViewById<TextView>(R.id.modeLabel)
        val bpmView = view.findViewById<TextView>(R.id.heartRateValue)
        val statusView = view.findViewById<TextView>(R.id.statusMessage)
        val playPauseButton = view.findViewById<ImageButton>(R.id.playPauseButton)
        val nextButton = view.findViewById<ImageButton>(R.id.nextButton)
        val previousButton = view.findViewById<ImageButton>(R.id.previousButton)

        playPauseButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_TOGGLE_PLAY)
        }
        nextButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_NEXT)
        }
        previousButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_PREVIOUS)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.uiStateStore.state.collect { state ->
                    titleView.text = state.playback.currentTrack?.title ?: "尚未载入歌曲"
                    artistView.text = state.playback.currentTrack?.artist ?: "请先扫描并导入曲库"
                    modeView.text = "模式：${if (state.playback.playbackMode.value() == "calm") "舒缓" else "激动"}"
                    bpmView.text = state.heartRate.bpm?.let { "$it BPM" } ?: "-- BPM"
                    statusView.text = state.playback.lastMessage
                    playPauseButton.setImageResource(
                        if (state.playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }
            }
        }
    }
}
