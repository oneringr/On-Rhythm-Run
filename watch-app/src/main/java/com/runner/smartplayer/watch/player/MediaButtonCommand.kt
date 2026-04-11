package com.runner.smartplayer.watch.player

import android.view.KeyEvent

enum class MediaButtonCommand {
    PLAY,
    PAUSE,
    TOGGLE_PLAYBACK,
    NEXT,
    PREVIOUS,
}

object MediaButtonCommandResolver {
    fun resolve(event: KeyEvent?): MediaButtonCommand? {
        return resolve(
            action = event?.action,
            keyCode = event?.keyCode,
        )
    }

    fun resolve(action: Int?, keyCode: Int?): MediaButtonCommand? {
        if (action != KeyEvent.ACTION_DOWN || keyCode == null) {
            return null
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> MediaButtonCommand.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaButtonCommand.PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> MediaButtonCommand.TOGGLE_PLAYBACK
            KeyEvent.KEYCODE_MEDIA_NEXT -> MediaButtonCommand.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaButtonCommand.PREVIOUS
            else -> null
        }
    }
}
