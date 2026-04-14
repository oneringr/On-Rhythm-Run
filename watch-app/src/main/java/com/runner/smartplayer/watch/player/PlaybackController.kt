package com.runner.smartplayer.watch.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.runner.smartplayer.watch.config.PlayerConfig
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.QueueMode
import com.runner.smartplayer.watch.model.TrackLabel
import kotlinx.coroutines.delay

class PlaybackController(
    private val player: ExoPlayer,
    private val queueEngine: AdaptiveQueueEngine,
    private val playerConfig: PlayerConfig,
) {
    var currentTrack: LocalTrack? = null
        private set

    var queueMode: QueueMode = QueueMode.SHUFFLE
        private set

    fun load(tracks: List<LocalTrack>) {
        queueEngine.load(tracks)
    }

    fun syncCurrentTrack(tracks: List<LocalTrack>) {
        currentTrack = currentTrack?.let { existing ->
            tracks.firstOrNull { it.id == existing.id }
        }
        if (currentTrack == null && player.currentMediaItem != null) {
            stopAndClear()
        }
    }

    fun stopAndClear() {
        currentTrack = null
        player.stop()
        player.clearMediaItems()
    }

    fun cycleQueueMode() {
        setQueueMode(queueMode.next())
    }

    fun setQueueMode(mode: QueueMode) {
        queueMode = mode
        if (queueMode == QueueMode.SHUFFLE) {
            queueEngine.reshuffleAll(currentTrack)
        }
    }

    fun activeQueueLabel(currentMode: PlaybackMode, adaptiveEnabled: Boolean): TrackLabel? {
        if (!adaptiveEnabled) return null

        val preferred = currentMode.toLabel()
        if (queueEngine.hasLabel(preferred)) {
            return preferred
        }

        val fallback = currentTrack?.finalLabel
        return if (fallback != null && queueEngine.hasLabel(fallback)) fallback else null
    }

    fun currentPlaylist(activeLabel: TrackLabel?): List<LocalTrack> {
        return if (activeLabel != null) {
            queueEngine.playlistForLabel(activeLabel, queueMode)
        } else {
            queueEngine.playlistForCombined(queueMode)
        }
    }

    fun nextTrack(activeLabel: TrackLabel?, autoAdvance: Boolean): LocalTrack? {
        return if (activeLabel != null) {
            queueEngine.nextForLabel(
                label = activeLabel,
                current = currentTrack,
                queueMode = queueMode,
                repeatCurrent = queueMode == QueueMode.SINGLE_REPEAT && autoAdvance,
            )
        } else {
            queueEngine.nextCombined(
                current = currentTrack,
                queueMode = queueMode,
                repeatCurrent = queueMode == QueueMode.SINGLE_REPEAT && autoAdvance,
            )
        }
    }

    fun previousTrack(activeLabel: TrackLabel?): LocalTrack? {
        return if (activeLabel != null) {
            queueEngine.previousForLabel(activeLabel, currentTrack, queueMode)
        } else {
            queueEngine.previousCombined(currentTrack, queueMode)
        }
    }

    fun selectTrackById(trackId: String?, activeLabel: TrackLabel?): LocalTrack? {
        if (trackId.isNullOrBlank()) {
            return null
        }

        val targetTrack = queueEngine.findTrackById(trackId) ?: return null
        queueEngine.selectTrack(
            track = targetTrack,
            current = currentTrack,
            activeLabel = activeLabel,
        )
        return targetTrack
    }

    fun hasTracksForLabel(label: TrackLabel): Boolean = queueEngine.hasLabel(label)

    fun currentTrackMatches(label: TrackLabel): Boolean = currentTrack?.finalLabel == label

    fun nextTrackForLabel(label: TrackLabel): LocalTrack? {
        return queueEngine.nextForLabel(
            label = label,
            current = currentTrack,
            queueMode = queueMode,
            repeatCurrent = false,
        )
    }

    fun prepareTrack(track: LocalTrack?, playImmediately: Boolean): Boolean {
        if (track == null) {
            return false
        }

        currentTrack = track
        val mediaItem = MediaItem.Builder()
            .setUri(track.file.toUri())
            .setMediaId(track.id)
            .build()
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.playWhenReady = playImmediately
        player.prepare()
        return true
    }

    suspend fun switchTrackWithFade(track: LocalTrack?): Boolean {
        if (track == null) return false
        val wasPlaying = player.isPlaying
        if (wasPlaying) {
            fadeVolume(from = player.volume, to = 0f)
        }
        prepareTrack(track, playImmediately = wasPlaying)
        player.volume = if (wasPlaying) 0f else 1f
        if (wasPlaying) {
            fadeVolume(from = 0f, to = 1f)
        }
        return true
    }

    fun resetVolume() {
        player.volume = 1f
    }

    fun restartCurrentTrackIfIdle(startPlayback: Boolean): Boolean {
        val track = currentTrack ?: return false
        if (player.playbackState != Player.STATE_IDLE) return false
        return prepareTrack(track, playImmediately = startPlayback)
    }

    private suspend fun fadeVolume(from: Float, to: Float) {
        val delta = (to - from) / playerConfig.fadeSteps
        repeat(playerConfig.fadeSteps) { step ->
            player.volume = from + delta * (step + 1)
            delay(playerConfig.fadeStepDelayMs)
        }
        player.volume = to
    }
}
