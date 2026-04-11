package com.runner.smartplayer.watch.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.data.ManifestLoadResult
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.QueueMode
import com.runner.smartplayer.watch.model.SensorAvailability
import com.runner.smartplayer.watch.model.TrackLabel
import com.runner.smartplayer.watch.model.displayTitle
import com.runner.smartplayer.watch.sensor.HeartRateSensorController
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class AdaptivePlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var queueEngine: AdaptiveQueueEngine
    private lateinit var heartRateSensorController: HeartRateSensorController
    private lateinit var audioManager: AudioManager

    private var currentTrack: LocalTrack? = null
    private var adaptiveEnabled: Boolean = true
    private var debugModeEnabled: Boolean = false
    private var currentMode: PlaybackMode = PlaybackMode.CALM
    private var queueMode: QueueMode = QueueMode.SHUFFLE
    private var fadeJob: Job? = null
    private var loadJob: Job? = null
    private var cachedPlaylist: List<LocalTrack> = emptyList()
    private var cachedVolumePercent: Int = 50

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        audioManager = getSystemService(AudioManager::class.java)
        cachedVolumePercent = currentVolumePercent()
        createNotificationChannel()

        queueEngine = AdaptiveQueueEngine()
        player = ExoPlayer.Builder(this).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
            playWhenReady = false
            volume = 1f
            addListener(playbackListener)
        }
        mediaSession = MediaSessionCompat(this, "RunnerSmartPlayerSession").apply {
            setPlaybackState(playbackStateFor(false))
            isActive = true
        }
        heartRateSensorController = HeartRateSensorController(
            context = this,
            onHeartRateSnapshot = { snapshot ->
                if (!debugModeEnabled) {
                    AppGraph.uiStateStore.updateHeartRate { snapshot }
                }
            },
            onStableModeChanged = ::onStableModeChanged,
        )

        startForeground(NOTIFICATION_ID, buildNotification())
        loadLibrary()
        heartRateSensorController.start()
        startPlaybackTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null,
            ServiceIntents.ACTION_BOOT -> Unit
            ServiceIntents.ACTION_TOGGLE_PLAY -> togglePlayback()
            ServiceIntents.ACTION_NEXT -> playNext()
            ServiceIntents.ACTION_PREVIOUS -> playPrevious()
            ServiceIntents.ACTION_RESCAN -> loadLibrary()
            ServiceIntents.ACTION_TOGGLE_ADAPTIVE -> {
                adaptiveEnabled = intent.getBooleanExtra(
                    ServiceIntents.EXTRA_ADAPTIVE_ENABLED,
                    adaptiveEnabled,
                )
                AppGraph.uiStateStore.setAdaptiveEnabled(adaptiveEnabled)
                refreshPlaylistCache()
                updatePlaybackSnapshot(
                    getString(
                        if (adaptiveEnabled) {
                            R.string.message_adaptive_enabled
                        } else {
                            R.string.message_adaptive_disabled
                        }
                    )
                )
            }
            ServiceIntents.ACTION_TOGGLE_DEBUG_MODE -> {
                setDebugModeEnabled(
                    intent.getBooleanExtra(ServiceIntents.EXTRA_DEBUG_ENABLED, !debugModeEnabled)
                )
            }
            ServiceIntents.ACTION_TOGGLE_DEBUG_PLAYBACK_MODE -> {
                toggleDebugPlaybackMode()
            }
            ServiceIntents.ACTION_VOLUME_UP -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                cachedVolumePercent = currentVolumePercent()
                updatePlaybackSnapshot(getString(R.string.message_volume_percent, cachedVolumePercent))
            }
            ServiceIntents.ACTION_VOLUME_DOWN -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                cachedVolumePercent = currentVolumePercent()
                updatePlaybackSnapshot(getString(R.string.message_volume_percent, cachedVolumePercent))
            }
            ServiceIntents.ACTION_CYCLE_QUEUE_MODE -> {
                queueMode = queueMode.next()
                if (queueMode == QueueMode.SHUFFLE) {
                    queueEngine.reshuffleAll(currentTrack)
                }
                refreshPlaylistCache()
                updatePlaybackSnapshot(getString(R.string.message_queue_mode, queueMode.label()))
            }
            ServiceIntents.ACTION_PLAY_TRACK_BY_ID -> {
                val trackId = intent.getStringExtra(ServiceIntents.EXTRA_TRACK_ID)
                playTrackById(trackId)
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartRateSensorController.stop()
        fadeJob?.cancel()
        loadJob?.cancel()
        player.removeListener(playbackListener)
        player.release()
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun loadLibrary() {
        loadJob?.cancel()
        loadJob = serviceScope.launch(Dispatchers.IO) {
            val result = AppGraph.manifestRepository.loadLibrary()
            withContext(Dispatchers.Main.immediate) {
                when (result) {
                    is ManifestLoadResult.Error -> {
                        Log.w(TAG, "loadLibrary failed: ${result.message}")
                        updatePlaybackSnapshot(result.message)
                        updateNotification()
                    }
                    is ManifestLoadResult.Success -> {
                        Log.d(
                            TAG,
                            "Loaded library ${result.library.libraryName} with ${result.library.tracks.size} tracks",
                        )
                        queueEngine.load(result.library.tracks)
                        AppGraph.uiStateStore.updateLibrary(result.summary)
                        if (currentTrack != null && result.library.tracks.none { it.id == currentTrack?.id }) {
                            currentTrack = null
                            player.stop()
                            player.clearMediaItems()
                        }
                        if (currentTrack == null) {
                            val firstTrack = nextTrackForCurrentContext(autoAdvance = false)
                            prepareTrack(
                                firstTrack,
                                playImmediately = false,
                                reason = getString(R.string.message_library_ready),
                            )
                        } else {
                            refreshPlaylistCache()
                            updatePlaybackSnapshot(result.statusMessage)
                            updateNotification()
                        }
                    }
                }
            }
        }
    }

    private fun togglePlayback() {
        if (currentTrack == null) {
            val seedTrack = nextTrackForCurrentContext(autoAdvance = false)
            prepareTrack(seedTrack, playImmediately = true, reason = getString(R.string.message_start_playback))
            return
        }

        if (player.isPlaying) {
            player.pause()
            updatePlaybackSnapshot(getString(R.string.message_paused))
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                currentTrack?.let { track ->
                    prepareTrack(
                        track,
                        playImmediately = true,
                        reason = getString(R.string.message_start_playback),
                    )
                    return
                }
            }
            player.play()
            updatePlaybackSnapshot(getString(R.string.message_playing))
        }
        mediaSession.setPlaybackState(playbackStateFor(player.isPlaying))
    }

    private fun playNext(autoAdvance: Boolean = false) {
        val nextTrack = nextTrackForCurrentContext(autoAdvance = autoAdvance)
        val reason = if (autoAdvance && queueMode == QueueMode.SINGLE_REPEAT) {
            getString(R.string.message_single_repeat)
        } else {
            getString(R.string.message_next_track)
        }
        prepareTrack(nextTrack, playImmediately = true, reason = reason)
    }

    private fun playPrevious() {
        val previousTrack = previousTrackForCurrentContext()
        prepareTrack(
            previousTrack,
            playImmediately = true,
            reason = getString(R.string.message_previous_track),
        )
    }

    private fun playTrackById(trackId: String?) {
        if (trackId.isNullOrBlank()) {
            updatePlaybackSnapshot(getString(R.string.message_track_not_found))
            return
        }

        val targetTrack = queueEngine.findTrackById(trackId)
        if (targetTrack == null) {
            updatePlaybackSnapshot(getString(R.string.message_track_not_found))
            return
        }

        queueEngine.selectTrack(
            track = targetTrack,
            current = currentTrack,
            activeLabel = activeQueueLabel(),
        )
        prepareTrack(
            targetTrack,
            playImmediately = true,
            reason = getString(R.string.message_jump_to_track, targetTrack.displayTitle()),
        )
    }

    private fun onStableModeChanged(mode: PlaybackMode) {
        if (debugModeEnabled) return
        applyPlaybackMode(
            mode,
            getString(R.string.message_heart_rate_settled_mode, mode.toLabel().label()),
        )
    }

    private fun setDebugModeEnabled(enabled: Boolean) {
        if (debugModeEnabled == enabled) {
            updatePlaybackSnapshot(
                if (enabled) {
                    getString(R.string.message_debug_enabled_hint)
                } else {
                    getString(R.string.message_debug_disabled)
                }
            )
            return
        }

        debugModeEnabled = enabled
        AppGraph.uiStateStore.setDebugModeEnabled(enabled)
        if (enabled) {
            fadeJob?.cancel()
            fadeJob = null
            player.volume = 1f
            heartRateSensorController.stop()
            publishDebugHeartRateSnapshot()
            updatePlaybackSnapshot(getString(R.string.message_debug_enabled_hint))
        } else {
            currentMode = PlaybackMode.CALM
            AppGraph.uiStateStore.setPlaybackMode(PlaybackMode.CALM)
            refreshPlaylistCache()
            heartRateSensorController.resetClassifier()
            heartRateSensorController.start()
            updatePlaybackSnapshot(getString(R.string.message_debug_disabled_restore))
        }
    }

    private fun toggleDebugPlaybackMode() {
        if (!debugModeEnabled) {
            updatePlaybackSnapshot(getString(R.string.message_enable_debug_first))
            return
        }

        val nextMode = when (currentMode) {
            PlaybackMode.CALM -> PlaybackMode.EXCITED
            PlaybackMode.EXCITED -> PlaybackMode.CALM
        }
        if (!adaptiveEnabled) {
            currentMode = nextMode
            AppGraph.uiStateStore.setPlaybackMode(nextMode)
            publishDebugHeartRateSnapshot()
            updatePlaybackSnapshot(
                getString(R.string.message_debug_switched_mode_no_adaptive, nextMode.toLabel().label())
            )
            return
        }
        applyPlaybackMode(
            nextMode,
            getString(R.string.message_debug_switched_mode, nextMode.toLabel().label()),
        )
    }

    private fun applyPlaybackMode(mode: PlaybackMode, settledMessage: String) {
        currentMode = mode
        AppGraph.uiStateStore.setPlaybackMode(mode)
        if (debugModeEnabled) {
            publishDebugHeartRateSnapshot()
        }
        if (!adaptiveEnabled) {
            updatePlaybackSnapshot(settledMessage)
            return
        }

        val targetLabel = mode.toLabel()
        refreshPlaylistCache()
        if (currentTrack?.finalLabel == targetLabel) {
            updatePlaybackSnapshot(settledMessage)
            return
        }

        if (!queueEngine.hasLabel(targetLabel)) {
            updatePlaybackSnapshot(getString(R.string.message_no_tracks_for_label, targetLabel.label()))
            return
        }

        val targetTrack = nextTrackForLabel(
            label = targetLabel,
            repeatCurrent = false,
        )
        fadeJob?.cancel()
        fadeJob = serviceScope.launch {
            switchTrackWithFade(targetTrack, settledMessage)
        }
    }

    private fun publishDebugHeartRateSnapshot() {
        AppGraph.uiStateStore.updateHeartRate {
            it.copy(
                bpm = null,
                mode = currentMode,
                availability = SensorAvailability.STOPPED,
                message = getString(R.string.message_debug_heart_rate),
            )
        }
    }

    private suspend fun switchTrackWithFade(track: LocalTrack?, reason: String) {
        if (track == null) return
        val wasPlaying = player.isPlaying
        if (wasPlaying) {
            fadeVolume(from = player.volume, to = 0f)
        }
        prepareTrack(track, playImmediately = wasPlaying, reason = reason)
        player.volume = if (wasPlaying) 0f else 1f
        if (wasPlaying) {
            fadeVolume(from = 0f, to = 1f)
        }
    }

    private suspend fun fadeVolume(from: Float, to: Float) {
        val steps = 12
        val delta = (to - from) / steps
        repeat(steps) { step ->
            player.volume = from + delta * (step + 1)
            delay(50L)
        }
        player.volume = to
    }

    private fun prepareTrack(track: LocalTrack?, playImmediately: Boolean, reason: String) {
        if (track == null) {
            Log.w(TAG, "prepareTrack called with null track")
            refreshPlaylistCache()
            updatePlaybackSnapshot(getString(R.string.message_no_tracks_available))
            updateNotification()
            return
        }

        Log.d(
            TAG,
            "Preparing track: ${track.displayTitle()} (${track.file.absolutePath}), playImmediately=$playImmediately",
        )
        currentTrack = track
        refreshPlaylistCache()
        val mediaItem = MediaItem.Builder()
            .setUri(track.file.toUri())
            .setMediaId(track.id)
            .build()
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.playWhenReady = playImmediately
        player.prepare()

        updatePlaybackSnapshot(reason)
        mediaSession.setPlaybackState(playbackStateFor(playImmediately))
        updateNotification()
    }

    private fun nextTrackForCurrentContext(autoAdvance: Boolean): LocalTrack? {
        val label = activeQueueLabel()
        return if (label != null) {
            nextTrackForLabel(label, repeatCurrent = queueMode == QueueMode.SINGLE_REPEAT && autoAdvance)
        } else {
            queueEngine.nextCombined(
                current = currentTrack,
                queueMode = queueMode,
                repeatCurrent = queueMode == QueueMode.SINGLE_REPEAT && autoAdvance,
            )
        }
    }

    private fun nextTrackForLabel(
        label: TrackLabel,
        repeatCurrent: Boolean,
    ): LocalTrack? {
        return queueEngine.nextForLabel(
            label = label,
            current = currentTrack,
            queueMode = queueMode,
            repeatCurrent = repeatCurrent,
        )
    }

    private fun previousTrackForCurrentContext(): LocalTrack? {
        val label = activeQueueLabel()
        return if (label != null) {
            queueEngine.previousForLabel(label, currentTrack, queueMode)
        } else {
            queueEngine.previousCombined(currentTrack, queueMode)
        }
    }

    private fun activeQueueLabel(): TrackLabel? {
        if (!adaptiveEnabled) return null

        val preferred = currentMode.toLabel()
        if (queueEngine.hasLabel(preferred)) {
            return preferred
        }

        val fallback = currentTrack?.finalLabel
        return if (fallback != null && queueEngine.hasLabel(fallback)) fallback else null
    }

    private fun currentPlaylist(): List<LocalTrack> {
        val label = activeQueueLabel()
        return if (label != null) {
            queueEngine.playlistForLabel(label, queueMode)
        } else {
            queueEngine.playlistForCombined(queueMode)
        }
    }

    private fun refreshPlaylistCache() {
        cachedPlaylist = currentPlaylist()
    }

    private fun updatePlaybackSnapshot(message: String? = null) {
        val durationMs = player.duration
            .takeIf { it > 0 }
            ?: currentTrack?.durationMs
            ?: 0L
        val positionMs = player.currentPosition.coerceAtLeast(0L).coerceAtMost(durationMs)
        val progressPercent = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val playlist = cachedPlaylist

        AppGraph.uiStateStore.updatePlayback { snapshot ->
            snapshot.copy(
                currentTrack = currentTrack,
                isPlaying = player.isPlaying,
                isAdaptiveEnabled = adaptiveEnabled,
                isDebugModeEnabled = debugModeEnabled,
                playbackMode = currentMode,
                lastMessage = message ?: snapshot.lastMessage,
                displayTitle = currentTrack?.displayTitle() ?: getString(R.string.track_not_ready),
                durationMs = durationMs,
                positionMs = positionMs,
                progressPercent = progressPercent,
                volumePercent = cachedVolumePercent,
                queueMode = queueMode,
                playlistTracks = playlist,
            )
        }
    }

    private fun currentVolumePercent(): Int {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return 0
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (currentVolume * 100f / maxVolume.toFloat()).roundToInt().coerceIn(0, 100)
    }

    private fun startPlaybackTicker() {
        serviceScope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    updatePlaybackSnapshot()
                    delay(300L)
                } else {
                    delay(1000L)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, com.runner.smartplayer.watch.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val previousAction = NotificationCompat.Action(
            R.drawable.ic_skip_previous,
            getString(R.string.previous),
            pendingServiceAction(ServiceIntents.ACTION_PREVIOUS, 2),
        )
        val playPauseAction = NotificationCompat.Action(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (player.isPlaying) R.string.pause else R.string.play),
            pendingServiceAction(ServiceIntents.ACTION_TOGGLE_PLAY, 3),
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next,
            getString(R.string.next),
            pendingServiceAction(ServiceIntents.ACTION_NEXT, 4),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTrack?.displayTitle() ?: getString(R.string.app_name))
            .setContentText(
                getString(
                    R.string.notification_content_format,
                    currentMode.toLabel().label(),
                    queueMode.label(),
                )
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(player.isPlaying)
            .setStyle(MediaNotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pendingServiceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AdaptivePlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun playbackStateFor(isPlaying: Boolean): PlaybackStateCompat {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, player.currentPosition, 1f)
            .build()
    }

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "Playback isPlaying changed: $isPlaying")
            updatePlaybackSnapshot(
                if (isPlaying) {
                    getString(R.string.message_playing)
                } else if (player.playbackState == Player.STATE_READY) {
                    getString(R.string.message_paused)
                } else {
                    null
                }
            )
            mediaSession.setPlaybackState(playbackStateFor(isPlaying))
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> updatePlaybackSnapshot(getString(R.string.message_buffering_audio))
                Player.STATE_READY -> {
                    if (!player.isPlaying && currentTrack != null) {
                        updatePlaybackSnapshot(getString(R.string.message_ready_tap_to_play))
                    } else {
                        updatePlaybackSnapshot()
                    }
                }
                Player.STATE_ENDED -> playNext(autoAdvance = true)
                else -> updatePlaybackSnapshot()
            }
            Log.d(TAG, "Playback state changed: $playbackState")
        }

        override fun onPlayerError(error: PlaybackException) {
            val failingTrack = currentTrack
            Log.e(TAG, "Playback error for ${failingTrack?.file?.absolutePath}", error)
            updatePlaybackSnapshot(
                buildString {
                    append(getString(R.string.message_playback_failed))
                    if (failingTrack != null) {
                        append("：")
                        append(failingTrack.displayTitle())
                    }
                    error.localizedMessage?.takeIf { it.isNotBlank() }?.let {
                        append("（")
                        append(it)
                        append("）")
                    }
                }
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RunnerSmartPlayer"
        private const val CHANNEL_ID = "runner_player_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
