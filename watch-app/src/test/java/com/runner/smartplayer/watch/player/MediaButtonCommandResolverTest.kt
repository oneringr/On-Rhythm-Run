package com.runner.smartplayer.watch.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaButtonCommandResolverTest {
    @Test
    fun resolvesSupportedMediaButtonsOnActionDown() {
        assertEquals(
            MediaButtonCommand.PLAY,
            MediaButtonCommandResolver.resolve(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        )
        assertEquals(
            MediaButtonCommand.PAUSE,
            MediaButtonCommandResolver.resolve(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        )
        assertEquals(
            MediaButtonCommand.TOGGLE_PLAYBACK,
            MediaButtonCommandResolver.resolve(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            )
        )
        assertEquals(
            MediaButtonCommand.TOGGLE_PLAYBACK,
            MediaButtonCommandResolver.resolve(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
        )
        assertEquals(
            MediaButtonCommand.NEXT,
            MediaButtonCommandResolver.resolve(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        )
        assertEquals(
            MediaButtonCommand.PREVIOUS,
            MediaButtonCommandResolver.resolve(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            )
        )
    }

    @Test
    fun ignoresUnsupportedOrActionUpEvents() {
        assertNull(
            MediaButtonCommandResolver.resolve(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
        assertNull(
            MediaButtonCommandResolver.resolve(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP)
        )
        assertNull(MediaButtonCommandResolver.resolve(null))
    }
}
