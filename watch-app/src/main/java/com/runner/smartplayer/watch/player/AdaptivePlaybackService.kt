package com.runner.smartplayer.watch.player

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.config.PlayerConfig
import com.runner.smartplayer.watch.data.ManifestLoadResult
import com.runner.smartplayer.watch.data.ManifestRepository
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.model.SensorAvailability
import com.runner.smartplayer.watch.model.TrackLabel
import com.runner.smartplayer.watch.model.displayTitle
import com.runner.smartplayer.watch.sensor.HeartRateController
import com.runner.smartplayer.watch.state.AppGraph
import com.runner.smartplayer.watch.state.StateStore
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
    private lateinit var audioManager: AudioManager
    private lateinit var stateStore: StateStore
    private lateinit var manifestRepository: ManifestRepository
    private lateinit var playerConfig: PlayerConfig
    private lateinit var heartRateController: HeartRateController
    private lateinit var playbackController: PlaybackController
    private lateinit var notificationController: NotificationController
    private lateinit var modeManager: AdaptiveModeManager

    private var loadJob: Job? = null
    private var cachedPlaylist: List<LocalTrack> = emptyList()
    private var cachedVolumePercent: Int = 50

    override fun onCreate() {
        super.onCreate()
        stateStore = AppGraph.uiStateStore
        manifestRepository = AppGraph.manifestRepository
        playerConfig = AppGraph.playerConfig
        audioManager = getSystemService(AudioManager::class.java)
        cachedVolumePercent = currentVolumePercent()

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
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(mediaSessionCallback)
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(this@AdaptivePlaybackService, MediaButtonReceiver::class.java)
            }
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this@AdaptivePlaybackService,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)
            setPlaybackState(playbackStateFor(false))
            isActive = true
        }
        playbackController = PlaybackController(
            player = player,
            queueEngine = AppGraph.createQueueEngine(),
            playerConfig = playerConfig,
        )
        heartRateController = AppGraph.createHeartRateController(
            onHeartRateSnapshot = { snapshot ->
                if (!modeManager.debugModeEnabled) {
                    stateStore.updateHeartRate { snapshot }
                }
            },
            onStableModeChanged = { mode ->
                modeManager.onStableModeChanged(mode, buildAdaptiveModeMessages())
            },
        )
        modeManager = AdaptiveModeManager(
            serviceScope = serviceScope,
            stateStore = stateStore,
            sensorController = heartRateController,
            onRefreshPlaylist = ::refreshPlaylistCache,
            onResetPlaybackVolume = playbackController::resetVolume,
            onCurrentTrackLabel = { playbackController.currentTrack?.finalLabel },
            onHasTracksForLabel = playbackController::hasTracksForLabel,
            onNextTrackForLabel = playbackController::nextTrackForLabel,
            onSwitchTrackWithFade = ::switchTrackWithFade,
            onUpdateMessage = { message ->
                updatePlaybackSnapshot(message)
                updateNotification()
            },
            onPublishDebugHeartRateSnapshot = ::publishDebugHeartRateSnapshot,
        )
        notificationController = NotificationController(
            context = this,
            mediaSession = mediaSession,
            playerConfig = playerConfig,
        )
        notificationController.createChannel()

        startForeground(
            playerConfig.notificationId,
            notificationController.build(currentNotificationState()),
        )
        loadLibrary()
        heartRateController.start()
        startPlaybackTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            updateNotification()
            return START_STICKY
        }

        when (intent?.action) {
            null,
            ServiceIntents.ACTION_BOOT -> Unit
            ServiceIntents.ACTION_TOGGLE_PLAY -> togglePlayback()
            ServiceIntents.ACTION_NEXT -> playNext()
            ServiceIntents.ACTION_PREVIOUS -> playPrevious()
            ServiceIntents.ACTION_RESCAN -> loadLibrary()
            ServiceIntents.ACTION_TOGGLE_ADAPTIVE -> {
                modeManager.setAdaptiveEnabled(
                    intent.getBooleanExtra(
                        ServiceIntents.EXTRA_ADAPTIVE_ENABLED,
                        modeManager.adaptiveEnabled,
                    ),
                    buildAdaptiveModeMessages(),
                )
            }
            ServiceIntents.ACTION_TOGGLE_DEBUG_MODE -> {
                modeManager.setDebugModeEnabled(
                    intent.getBooleanExtra(
                        ServiceIntents.EXTRA_DEBUG_ENABLED,
                        !modeManager.debugModeEnabled,
                    ),
                    buildAdaptiveModeMessages(),
                )
            }
            ServiceIntents.ACTION_TOGGLE_DEBUG_PLAYBACK_MODE -> {
                modeManager.toggleDebugPlaybackMode(buildAdaptiveModeMessages())
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
                playbackController.cycleQueueMode()
                refreshPlaylistCache()
                updatePlaybackSnapshot(
                    getString(R.string.message_queue_mode, playbackController.queueMode.label())
                )
            }
            ServiceIntents.ACTION_PLAY_TRACK_BY_ID -> {
                playTrackById(intent.getStringExtra(ServiceIntents.EXTRA_TRACK_ID))
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartRateController.stop()
        modeManager.cancel()
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
            val result = manifestRepository.loadLibrary()
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
                        playbackController.load(result.library.tracks)
                        stateStore.updateLibrary(result.summary)
                        playbackController.syncCurrentTrack(result.library.tracks)
                        if (playbackController.currentTrack == null) {
                            val firstTrack = playbackController.nextTrack(
                                activeQueueLabel(),
                                autoAdvance = false,
                            )
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
        if (player.isPlaying) {
            pausePlayback()
        } else {
            playPlayback()
        }
    }

    private fun playPlayback() {
        if (playbackController.currentTrack == null) {
            val seedTrack = playbackController.nextTrack(activeQueueLabel(), autoAdvance = false)
            prepareTrack(
                seedTrack,
                playImmediately = true,
                reason = getString(R.string.message_start_playback),
            )
            return
        }

        if (playbackController.restartCurrentTrackIfIdle(startPlayback = true)) {
            refreshPlaylistCache()
            updatePlaybackSnapshot(getString(R.string.message_start_playback))
            mediaSession.setPlaybackState(playbackStateFor(true))
            updateNotification()
            return
        }
        if (!player.isPlaying) {
            player.play()
            updatePlaybackSnapshot(getString(R.string.message_playing))
            mediaSession.setPlaybackState(playbackStateFor(true))
        }
    }

    private fun pausePlayback() {
        if (!player.isPlaying) {
            mediaSession.setPlaybackState(playbackStateFor(false))
            return
        }
        player.pause()
        updatePlaybackSnapshot(getString(R.string.message_paused))
        mediaSession.setPlaybackState(playbackStateFor(false))
    }

    private fun playNext(autoAdvance: Boolean = false) {
        val nextTrack = playbackController.nextTrack(activeQueueLabel(), autoAdvance)
        val reason = if (autoAdvance && playbackController.queueMode == com.runner.smartplayer.watch.model.QueueMode.SINGLE_REPEAT) {
            getString(R.string.message_single_repeat)
        } else {
            getString(R.string.message_next_track)
        }
        prepareTrack(nextTrack, playImmediately = true, reason = reason)
    }

    private fun playPrevious() {
        val previousTrack = playbackController.previousTrack(activeQueueLabel())
        prepareTrack(
            previousTrack,
            playImmediately = true,
            reason = getString(R.string.message_previous_track),
        )
    }

    private fun playTrackById(trackId: String?) {
        val targetTrack = playbackController.selectTrackById(trackId, activeQueueLabel())
        if (targetTrack == null) {
            updatePlaybackSnapshot(getString(R.string.message_track_not_found))
            return
        }

        prepareTrack(
            targetTrack,
            playImmediately = true,
            reason = getString(R.string.message_jump_to_track, targetTrack.displayTitle()),
        )
    }

    private fun publishDebugHeartRateSnapshot(mode: PlaybackMode, message: String) {
        stateStore.updateHeartRate {
            it.copy(
                bpm = null,
                mode = mode,
                availability = SensorAvailability.STOPPED,
                message = message,
            )
        }
    }

    private suspend fun switchTrackWithFade(track: LocalTrack?, reason: String) {
        if (!playbackController.switchTrackWithFade(track)) {
            return
        }
        refreshPlaylistCache()
        updatePlaybackSnapshot(reason)
        mediaSession.setPlaybackState(playbackStateFor(player.isPlaying))
        updateNotification()
    }

    private fun prepareTrack(track: LocalTrack?, playImmediately: Boolean, reason: String) {
        if (!playbackController.prepareTrack(track, playImmediately)) {
            Log.w(TAG, "prepareTrack called with null track")
            refreshPlaylistCache()
            updatePlaybackSnapshot(getString(R.string.message_no_tracks_available))
            updateNotification()
            return
        }

        Log.d(
            TAG,
            "Preparing track: ${playbackController.currentTrack?.displayTitle()}, playImmediately=$playImmediately",
        )
        refreshPlaylistCache()
        updatePlaybackSnapshot(reason)
        mediaSession.setPlaybackState(playbackStateFor(playImmediately))
        updateNotification()
    }

    private fun activeQueueLabel(): TrackLabel? {
        return playbackController.activeQueueLabel(
            currentMode = modeManager.currentMode,
            adaptiveEnabled = modeManager.adaptiveEnabled,
        )
    }

    private fun refreshPlaylistCache() {
        cachedPlaylist = playbackController.currentPlaylist(activeQueueLabel())
    }

    private fun updatePlaybackSnapshot(message: String? = null) {
        val durationMs = player.duration
            .takeIf { it > 0 }
            ?: playbackController.currentTrack?.durationMs
            ?: 0L
        val positionMs = player.currentPosition.coerceAtLeast(0L).coerceAtMost(durationMs)
        val progressPercent = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        stateStore.updatePlayback { snapshot ->
            snapshot.copy(
                currentTrack = playbackController.currentTrack,
                isPlaying = player.isPlaying,
                isAdaptiveEnabled = modeManager.adaptiveEnabled,
                isDebugModeEnabled = modeManager.debugModeEnabled,
                playbackMode = modeManager.currentMode,
                lastMessage = message ?: snapshot.lastMessage,
                displayTitle = playbackController.currentTrack?.displayTitle()
                    ?: getString(R.string.track_not_ready),
                durationMs = durationMs,
                positionMs = positionMs,
                progressPercent = progressPercent,
                volumePercent = cachedVolumePercent,
                queueMode = playbackController.queueMode,
                playlistTracks = cachedPlaylist,
            )
        }
    }

    private fun currentNotificationState(): NotificationState {
        return NotificationState(
            trackTitle = playbackController.currentTrack?.displayTitle(),
            isPlaying = player.isPlaying,
            modeLabel = modeManager.currentMode.toLabel().label(),
            queueModeLabel = playbackController.queueMode.label(),
        )
    }

    private fun updateNotification() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        notificationController.update(currentNotificationState())
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
                    delay(playerConfig.tickerIntervalPlayingMs)
                } else {
                    delay(playerConfig.tickerIntervalIdleMs)
                }
            }
        }
    }

    private fun playbackStateFor(isPlaying: Boolean): PlaybackStateCompat {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, player.currentPosition, 1f)
            .build()
    }

    private fun buildAdaptiveModeMessages(): AdaptiveModeMessages {
        return AdaptiveModeMessages(
            adaptiveEnabled = getString(R.string.message_adaptive_enabled),
            adaptiveDisabled = getString(R.string.message_adaptive_disabled),
            debugEnabledHint = getString(R.string.message_debug_enabled_hint),
            debugDisabled = getString(R.string.message_debug_disabled),
            debugDisabledRestore = getString(R.string.message_debug_disabled_restore),
            enableDebugFirst = getString(R.string.message_enable_debug_first),
            debugHeartRate = getString(R.string.message_debug_heart_rate),
            settledMode = { mode ->
                getString(R.string.message_heart_rate_settled_mode, mode.toLabel().label())
            },
            debugSwitchedMode = { mode ->
                getString(R.string.message_debug_switched_mode, mode.toLabel().label())
            },
            debugSwitchedModeNoAdaptive = { mode ->
                getString(R.string.message_debug_switched_mode_no_adaptive, mode.toLabel().label())
            },
            noTracksForLabel = { label ->
                getString(R.string.message_no_tracks_for_label, label.label())
            },
        )
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
                    if (!player.isPlaying && playbackController.currentTrack != null) {
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
            val failingTrack = playbackController.currentTrack
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

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d(TAG, "MediaSession onPlay")
            playPlayback()
            updateNotification()
        }

        override fun onPause() {
            Log.d(TAG, "MediaSession onPause")
            pausePlayback()
            updateNotification()
        }

        override fun onSkipToNext() {
            Log.d(TAG, "MediaSession onSkipToNext")
            playNext()
            updateNotification()
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "MediaSession onSkipToPrevious")
            playPrevious()
            updateNotification()
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val event = mediaButtonEvent?.extras?.let { extras ->
                BundleCompat.getParcelable(extras, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            }
            return when (MediaButtonCommandResolver.resolve(event)) {
                MediaButtonCommand.PLAY -> {
                    onPlay()
                    true
                }
                MediaButtonCommand.PAUSE -> {
                    onPause()
                    true
                }
                MediaButtonCommand.TOGGLE_PLAYBACK -> {
                    Log.d(TAG, "MediaSession toggle playback from media button")
                    togglePlayback()
                    updateNotification()
                    true
                }
                MediaButtonCommand.NEXT -> {
                    onSkipToNext()
                    true
                }
                MediaButtonCommand.PREVIOUS -> {
                    onSkipToPrevious()
                    true
                }
                null -> super.onMediaButtonEvent(mediaButtonEvent)
            }
        }
    }

    companion object {
        private const val TAG = "RunnerSmartPlayer"
    }
}
