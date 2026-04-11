package com.runner.smartplayer.watch.player

import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.QueueMode
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

        val next = queueEngine.nextForLabel(
            label = TrackLabel.EXCITED,
            current = null,
            queueMode = QueueMode.SHUFFLE,
        )
        assertTrue(next?.finalLabel == TrackLabel.EXCITED)
    }

    @Test
    fun returnsPreviousHistoryTrack() {
        val queueEngine = AdaptiveQueueEngine(Random(0))
        val first = buildTrack("calm-1", TrackLabel.CALM)
        val second = buildTrack("calm-2", TrackLabel.CALM)
        queueEngine.load(listOf(first, second))

        val nowPlaying = queueEngine.nextCombined(
            current = null,
            queueMode = QueueMode.SHUFFLE,
        )
        queueEngine.nextCombined(
            current = nowPlaying,
            queueMode = QueueMode.SHUFFLE,
        )
        assertEquals(
            nowPlaying?.id,
            queueEngine.previousCombined(current = second, queueMode = QueueMode.SHUFFLE)?.id,
        )
    }

    @Test
    fun listLoopWrapsBackToFirstTrack() {
        val queueEngine = AdaptiveQueueEngine(Random(2))
        val first = buildTrack("track-1", TrackLabel.CALM)
        val second = buildTrack("track-2", TrackLabel.CALM)
        queueEngine.load(listOf(first, second))

        val wrapped = queueEngine.nextCombined(
            current = second,
            queueMode = QueueMode.LIST_LOOP,
        )

        assertEquals(first.id, wrapped?.id)
    }

    @Test
    fun singleRepeatKeepsCurrentTrackWhenAutoAdvancing() {
        val queueEngine = AdaptiveQueueEngine(Random(3))
        val track = buildTrack("track-1", TrackLabel.EXCITED)
        queueEngine.load(listOf(track))

        val repeated = queueEngine.nextCombined(
            current = track,
            queueMode = QueueMode.SINGLE_REPEAT,
            repeatCurrent = true,
        )

        assertEquals(track.id, repeated?.id)
    }

    @Test
    fun shuffleHistoryIsCappedToAvoidUnboundedGrowth() {
        val queueEngine = AdaptiveQueueEngine(Random(4))
        val fallback = buildTrack("fallback", TrackLabel.CALM)
        queueEngine.load(listOf(buildTrack("seed", TrackLabel.CALM)))

        repeat(105) { index ->
            queueEngine.nextCombined(
                current = buildTrack("history-$index", TrackLabel.CALM),
                queueMode = QueueMode.SHUFFLE,
            )
        }

        repeat(99) {
            queueEngine.previousCombined(current = fallback, queueMode = QueueMode.SHUFFLE)
        }

        assertEquals(
            "history-5",
            queueEngine.previousCombined(current = fallback, queueMode = QueueMode.SHUFFLE)?.id,
        )
        assertEquals(
            fallback.id,
            queueEngine.previousCombined(current = fallback, queueMode = QueueMode.SHUFFLE)?.id,
        )
    }

    @Test
    fun previousForLabelSkipsHistoryFromDifferentLabels() {
        val queueEngine = AdaptiveQueueEngine(Random(5))
        val calmHistory = buildTrack("calm-history", TrackLabel.CALM)
        val excitedHistory = buildTrack("excited-history", TrackLabel.EXCITED)
        val calmRecent = buildTrack("calm-recent", TrackLabel.CALM)
        val currentExcited = buildTrack("current-excited", TrackLabel.EXCITED)
        queueEngine.load(listOf(calmHistory, excitedHistory, calmRecent, currentExcited))

        queueEngine.nextCombined(current = calmHistory, queueMode = QueueMode.SHUFFLE)
        queueEngine.nextCombined(current = excitedHistory, queueMode = QueueMode.SHUFFLE)
        queueEngine.nextCombined(current = calmRecent, queueMode = QueueMode.SHUFFLE)

        assertEquals(
            excitedHistory.id,
            queueEngine.previousForLabel(
                label = TrackLabel.EXCITED,
                current = currentExcited,
                queueMode = QueueMode.SHUFFLE,
            )?.id,
        )
        assertEquals(
            calmRecent.id,
            queueEngine.previousCombined(current = currentExcited, queueMode = QueueMode.SHUFFLE)?.id,
        )
    }

    @Test
    fun consecutiveDuplicateHistoryEntriesAreIgnored() {
        val queueEngine = AdaptiveQueueEngine(Random(6))
        val onlyTrack = buildTrack("only", TrackLabel.CALM)
        val fallback = buildTrack("fallback", TrackLabel.CALM)
        queueEngine.load(listOf(onlyTrack))

        repeat(5) {
            queueEngine.nextCombined(current = onlyTrack, queueMode = QueueMode.SHUFFLE)
        }

        assertEquals(
            onlyTrack.id,
            queueEngine.previousCombined(current = fallback, queueMode = QueueMode.SHUFFLE)?.id,
        )
        assertEquals(
            fallback.id,
            queueEngine.previousCombined(current = fallback, queueMode = QueueMode.SHUFFLE)?.id,
        )
    }

    @Test
    fun canFindTrackAndAdvanceShuffleIndexAfterManualSelection() {
        val queueEngine = AdaptiveQueueEngine(Random(7))
        val first = buildTrack("first", TrackLabel.CALM)
        val second = buildTrack("second", TrackLabel.CALM)
        val third = buildTrack("third", TrackLabel.CALM)
        queueEngine.load(listOf(first, second, third))

        val shuffledPlaylist = queueEngine.playlistForLabel(TrackLabel.CALM, QueueMode.SHUFFLE)
        val selected = shuffledPlaylist[1]
        assertEquals(selected.id, queueEngine.findTrackById(selected.id)?.id)

        queueEngine.selectTrack(
            track = selected,
            current = shuffledPlaylist[0],
            activeLabel = TrackLabel.CALM,
        )

        val next = queueEngine.nextForLabel(
            label = TrackLabel.CALM,
            current = selected,
            queueMode = QueueMode.SHUFFLE,
        )

        assertEquals(shuffledPlaylist[2].id, next?.id)
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
