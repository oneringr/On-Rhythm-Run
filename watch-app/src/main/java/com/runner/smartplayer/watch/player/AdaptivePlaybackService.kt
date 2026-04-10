package com.runner.smartplayer.watch.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.data.ManifestLoadResult
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.PlaybackMode
import com.runner.smartplayer.watch.sensor.HeartRateSensorController
import com.runner.smartplayer.watch.state.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AdaptivePlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var queueEngine: AdaptiveQueueEngine
    private lateinit var heartRateSensorController: HeartRateSensorController

    private var currentTrack: LocalTrack? = null
    private var adaptiveEnabled: Boolean = true
    private var currentMode: PlaybackMode = PlaybackMode.CALM

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
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
                AppGraph.uiStateStore.updateHeartRate { snapshot }
            },
            onStableModeChanged = ::onStableModeChanged,
        )

        startForeground(NOTIFICATION_ID, buildNotification())
        loadLibrary()
        heartRateSensorController.start()
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
                AppGraph.uiStateStore.setMessage(
                    if (adaptiveEnabled) "已开启自适应模式" else "已关闭自适应模式"
                )
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartRateSensorController.stop()
        player.removeListener(playbackListener)
        player.release()
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun loadLibrary() {
        when (val result = AppGraph.manifestRepository.loadLibrary()) {
            is ManifestLoadResult.Error -> {
                Log.w(TAG, "loadLibrary failed: ${result.message}")
                AppGraph.uiStateStore.setMessage(result.message)
            }

            is ManifestLoadResult.Success -> {
                Log.d(
                    TAG,
                    "Loaded library ${result.library.libraryName} with ${result.library.tracks.size} tracks",
                )
                queueEngine.load(result.library.tracks)
                AppGraph.uiStateStore.updateLibrary(result.summary)
                AppGraph.uiStateStore.setMessage(result.statusMessage)
                if (currentTrack != null && result.library.tracks.none { it.id == currentTrack?.id }) {
                    currentTrack = null
                    player.stop()
                    player.clearMediaItems()
                }
                if (currentTrack == null) {
                    val firstTrack = queueEngine.nextCombined(current = null)
                    prepareTrack(firstTrack, playImmediately = false, reason = "曲库已就绪")
                }
            }
        }
        updateNotification()
    }

    private fun togglePlayback() {
        if (currentTrack == null) {
            val seedTrack = nextTrackForCurrentContext()
            prepareTrack(seedTrack, playImmediately = true, reason = "开始播放")
            return
        }

        if (player.isPlaying) {
            player.pause()
            AppGraph.uiStateStore.setMessage("已暂停")
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                currentTrack?.let { track ->
                    prepareTrack(track, playImmediately = true, reason = "开始播放")
                    return
                }
            }
            player.play()
            AppGraph.uiStateStore.setMessage("正在播放")
        }
        AppGraph.uiStateStore.updatePlayback { it.copy(isPlaying = player.isPlaying) }
        mediaSession.setPlaybackState(playbackStateFor(player.isPlaying))
    }

    private fun playNext() {
        val nextTrack = nextTrackForCurrentContext()
        prepareTrack(nextTrack, playImmediately = true, reason = "下一首")
    }

    private fun playPrevious() {
        val previousTrack = queueEngine.previous(currentTrack)
        prepareTrack(previousTrack, playImmediately = true, reason = "上一首")
    }

    private fun onStableModeChanged(mode: PlaybackMode) {
        currentMode = mode
        AppGraph.uiStateStore.setPlaybackMode(mode)
        if (!adaptiveEnabled) {
            return
        }

        val targetLabel = mode.toLabel()
        if (currentTrack?.finalLabel == targetLabel) {
            AppGraph.uiStateStore.setMessage("心率稳定，当前处于${labelText(targetLabel)}模式")
            return
        }

        if (!queueEngine.hasLabel(targetLabel)) {
            AppGraph.uiStateStore.setMessage("当前没有${labelText(targetLabel)}歌曲可播放")
            return
        }

        val targetTrack = queueEngine.nextForLabel(targetLabel, currentTrack)
        serviceScope.launch {
            switchTrackWithFade(targetTrack, "已切换到${labelText(targetLabel)}模式")
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
            AppGraph.uiStateStore.setMessage("当前没有可播放的歌曲")
            return
        }

        Log.d(
            TAG,
            "Preparing track: ${track.title} (${track.file.absolutePath}), playImmediately=$playImmediately",
        )
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
        if (!playImmediately) {
            player.pause()
        }

        AppGraph.uiStateStore.updatePlayback {
            it.copy(
                currentTrack = track,
                isPlaying = playImmediately,
                playbackMode = currentMode,
                lastMessage = reason,
                isAdaptiveEnabled = adaptiveEnabled,
            )
        }
        mediaSession.setPlaybackState(playbackStateFor(playImmediately))
        updateNotification()
    }

    private fun nextTrackForCurrentContext(): LocalTrack? {
        if (adaptiveEnabled) {
            val targetLabel = currentMode.toLabel()
            if (queueEngine.hasLabel(targetLabel)) {
                return queueEngine.nextForLabel(targetLabel, currentTrack)
            }
            currentTrack?.finalLabel?.let { fallback ->
                if (queueEngine.hasLabel(fallback)) {
                    AppGraph.uiStateStore.setMessage("没有${labelText(targetLabel)}歌曲，继续当前队列")
                    return queueEngine.nextForLabel(fallback, currentTrack)
                }
            }
        }
        return queueEngine.nextCombined(currentTrack)
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
            "上一首",
            pendingServiceAction(ServiceIntents.ACTION_PREVIOUS, 2),
        )
        val playPauseAction = NotificationCompat.Action(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (player.isPlaying) "暂停" else "播放",
            pendingServiceAction(ServiceIntents.ACTION_TOGGLE_PLAY, 3),
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next,
            "下一首",
            pendingServiceAction(ServiceIntents.ACTION_NEXT, 4),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTrack?.title ?: getString(R.string.app_name))
            .setContentText(currentTrack?.artist ?: "跑步心率自适应播放器")
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
            mediaSession.setPlaybackState(playbackStateFor(isPlaying))
            AppGraph.uiStateStore.updatePlayback { it.copy(isPlaying = isPlaying) }
            Log.d(TAG, "Playback isPlaying changed: $isPlaying")
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> AppGraph.uiStateStore.setMessage("正在缓冲音频…")
                Player.STATE_READY -> {
                    if (currentTrack != null) {
                        AppGraph.uiStateStore.setMessage(
                            if (player.isPlaying) "正在播放" else "已就绪，点击播放"
                        )
                    }
                }
                Player.STATE_ENDED -> playNext()
            }
            Log.d(TAG, "Playback state changed: $playbackState")
        }

        override fun onPlayerError(error: PlaybackException) {
            val failingTrack = currentTrack
            Log.e(TAG, "Playback error for ${failingTrack?.file?.absolutePath}", error)
            AppGraph.uiStateStore.updatePlayback { it.copy(isPlaying = false) }
            AppGraph.uiStateStore.setMessage(
                buildString {
                    append("播放失败")
                    if (failingTrack != null) {
                        append("：")
                        append(failingTrack.title)
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
            "音乐播放",
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

    private fun labelText(label: com.runner.smartplayer.watch.model.TrackLabel): String {
        return if (label.value() == "calm") "舒缓" else "激动"
    }
}
