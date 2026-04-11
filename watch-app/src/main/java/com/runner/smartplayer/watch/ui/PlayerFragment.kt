package com.runner.smartplayer.watch.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.QueueMode
import com.runner.smartplayer.watch.player.ServiceIntents
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PlayerFragment : Fragment(R.layout.fragment_player) {
    private val playlistAdapter = PlaylistTrackAdapter { track ->
        ServiceIntents.sendPlayTrackById(requireContext(), track.id)
        view?.findViewById<View>(R.id.playlistOverlay)?.isVisible = false
    }
    private var lastDisplayTitle: String? = null
    private var lastPlaylistTracks: List<LocalTrack> = emptyList()
    private var lastPlayingTrackId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val displayTitleView = view.findViewById<AlwaysMarqueeTextView>(R.id.displayTitle)
        val currentModeView = view.findViewById<TextView>(R.id.currentModeValue)
        val currentBpmView = view.findViewById<TextView>(R.id.currentBpmValue)
        val bpmProgressBar = view.findViewById<ProgressBar>(R.id.bpmProgressBar)
        val playProgressRing = view.findViewById<CircularProgressIndicator>(R.id.playProgressRing)
        val playPauseButton = view.findViewById<ImageButton>(R.id.playPauseButton)
        val nextButton = view.findViewById<ImageButton>(R.id.nextButton)
        val previousButton = view.findViewById<ImageButton>(R.id.previousButton)
        val volumeDownButton = view.findViewById<MaterialButton>(R.id.volumeDownButton)
        val volumeUpButton = view.findViewById<MaterialButton>(R.id.volumeUpButton)
        val volumeProgressBar = view.findViewById<ProgressBar>(R.id.volumeProgressBar)
        val queueModeButton = view.findViewById<MaterialButton>(R.id.queueModeButton)
        val playlistOverlay = view.findViewById<View>(R.id.playlistOverlay)
        val playlistCard = view.findViewById<MaterialCardView>(R.id.playlistCard)
        val closePlaylistButton = view.findViewById<ImageButton>(R.id.closePlaylistButton)
        val playlistRecyclerView = view.findViewById<RecyclerView>(R.id.playlistRecyclerView)
        val playlistEmptyView = view.findViewById<TextView>(R.id.playlistEmptyView)

        playlistRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        playlistRecyclerView.adapter = playlistAdapter

        playPauseButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_TOGGLE_PLAY)
        }
        playPauseButton.setOnLongClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_TOGGLE_DEBUG_PLAYBACK_MODE)
            true
        }
        nextButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_NEXT)
        }
        previousButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_PREVIOUS)
        }
        volumeDownButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_VOLUME_DOWN)
        }
        volumeUpButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_VOLUME_UP)
        }
        queueModeButton.setOnClickListener {
            ServiceIntents.send(requireContext(), ServiceIntents.ACTION_CYCLE_QUEUE_MODE)
        }
        queueModeButton.setOnLongClickListener {
            playlistOverlay.isVisible = true
            true
        }
        playlistOverlay.setOnClickListener {
            playlistOverlay.isVisible = false
        }
        closePlaylistButton.setOnClickListener {
            playlistOverlay.isVisible = false
        }
        playlistCard.setOnClickListener {
            // Consume clicks inside the playlist card.
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.uiStateStore.state.collect { state ->
                    if (lastDisplayTitle != state.playback.displayTitle) {
                        lastDisplayTitle = state.playback.displayTitle
                        displayTitleView.text = state.playback.displayTitle
                        displayTitleView.isSelected = false
                        displayTitleView.post {
                            displayTitleView.isSelected = true
                        }
                    }
                    currentModeView.text = when (state.playback.playbackMode) {
                        PlaybackMode.CALM -> getString(R.string.label_calm)
                        PlaybackMode.EXCITED -> getString(R.string.label_excited)
                    }

                    val bpmValue = state.heartRate.bpm ?: 0
                    currentBpmView.text = getString(R.string.current_bpm_value, bpmValue)
                    bpmProgressBar.progress = bpmProgressPercent(bpmValue)
                    playProgressRing.progress = (state.playback.progressPercent * 100f).roundToInt()
                    volumeProgressBar.progress = state.playback.volumePercent

                    queueModeButton.text = queueModeLabel(state.playback.queueMode)
                    playPauseButton.setImageResource(
                        if (state.playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )

                    val currentPlayingTrackId = state.playback.currentTrack?.id
                    if (lastPlaylistTracks != state.playback.playlistTracks ||
                        lastPlayingTrackId != currentPlayingTrackId
                    ) {
                        lastPlaylistTracks = state.playback.playlistTracks
                        lastPlayingTrackId = currentPlayingTrackId
                        playlistAdapter.submitTracks(
                            nextTracks = state.playback.playlistTracks,
                            playingTrackId = currentPlayingTrackId,
                        )
                    }
                    playlistEmptyView.isVisible = state.playback.playlistTracks.isEmpty()
                }
            }
        }
    }

    private fun bpmProgressPercent(bpm: Int): Int {
        if (bpm <= 50) return 0
        if (bpm >= 200) return 100
        return (((bpm - 50) / 150f) * 100f).toInt().coerceIn(0, 100)
    }

    private fun queueModeLabel(queueMode: QueueMode): String {
        return when (queueMode) {
            QueueMode.SHUFFLE -> getString(R.string.queue_mode_shuffle)
            QueueMode.LIST_LOOP -> getString(R.string.queue_mode_list_loop)
            QueueMode.SINGLE_REPEAT -> getString(R.string.queue_mode_single_repeat)
        }
    }
}
