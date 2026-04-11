package com.runner.smartplayer.watch.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.config.PlayerConfig
import com.runner.smartplayer.watch.ui.MainActivity

data class NotificationState(
    val trackTitle: String?,
    val isPlaying: Boolean,
    val modeLabel: String,
    val queueModeLabel: String,
)

class NotificationController(
    private val context: Context,
    private val mediaSession: MediaSessionCompat,
    private val playerConfig: PlayerConfig,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            playerConfig.notificationChannelId,
            context.getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun build(state: NotificationState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val previousAction = NotificationCompat.Action(
            R.drawable.ic_skip_previous,
            context.getString(R.string.previous),
            pendingServiceAction(ServiceIntents.ACTION_PREVIOUS, 2),
        )
        val playPauseAction = NotificationCompat.Action(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            context.getString(if (state.isPlaying) R.string.pause else R.string.play),
            pendingServiceAction(ServiceIntents.ACTION_TOGGLE_PLAY, 3),
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next,
            context.getString(R.string.next),
            pendingServiceAction(ServiceIntents.ACTION_NEXT, 4),
        )

        return NotificationCompat.Builder(context, playerConfig.notificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.trackTitle ?: context.getString(R.string.app_name))
            .setContentText(
                context.getString(
                    R.string.notification_content_format,
                    state.modeLabel,
                    state.queueModeLabel,
                )
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .setStyle(MediaNotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .build()
    }

    fun update(state: NotificationState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Skip notification update because POST_NOTIFICATIONS is not granted")
            return
        }
        runCatching {
            notificationManager.notify(playerConfig.notificationId, build(state))
        }.onFailure { throwable ->
            if (throwable is SecurityException) {
                Log.w(TAG, "Failed to post playback notification", throwable)
            } else {
                throw throwable
            }
        }
    }

    private fun pendingServiceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AdaptivePlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "RunnerSmartPlayer"
    }
}
