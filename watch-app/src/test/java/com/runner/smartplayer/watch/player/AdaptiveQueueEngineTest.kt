package com.runner.smartplayer.watch.player

import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.TrackLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.random.Random

class AdaptiveQueueEngineTest {
    @Test
    fun returnsExcitedTracksWhenRequested() {
        val queueEngine = AdaptiveQueueEngine(Random(1))
        val tracks = listOf(
            buildTrack("calm-1", TrackLabel.CALM),
            buildTrack("excited-1", TrackLabel.EXCITED),
            buildTrack("excited-2", TrackLabel.EXCITED),
        )
        queueEngine.load(tracks)

        val next = queueEngine.nextForLabel(TrackLabel.EXCITED, current = null)
        assertTrue(next?.finalLabel == TrackLabel.EXCITED)
    }

    @Test
    fun returnsPreviousHistoryTrack() {
        val queueEngine = AdaptiveQueueEngine(Random(0))
        val first = buildTrack("calm-1", TrackLabel.CALM)
        val second = buildTrack("calm-2", TrackLabel.CALM)
        queueEngine.load(listOf(first, second))

        val nowPlaying = queueEngine.nextCombined(null)
        queueEngine.nextCombined(nowPlaying)
        assertEquals(nowPlaying?.id, queueEngine.previous(current = second)?.id)
    }

    private fun buildTrack(id: String, label: TrackLabel): LocalTrack {
        return LocalTrack(
            id = id,
            relativePath = "tracks/$id.mp3",
            sourceFileName = "$id.mp3",
            title = id,
            artist = "artist",
            durationMs = 1000L,
            bpm = 120.0,
            energyScore = 0.5,
            suggestedLabel = label,
            finalLabel = label,
            sizeBytes = 10L,
            modifiedAt = Instant.parse("2026-04-10T12:00:00Z"),
            file = File(id),
        )
    }
}
