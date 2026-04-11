package com.runner.smartplayer.watch.player

import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.QueueMode
import com.runner.smartplayer.watch.model.TrackLabel
import kotlin.random.Random

class AdaptiveQueueEngine(
    private val random: Random = Random.Default,
) {
    private companion object {
        const val MAX_HISTORY_SIZE = 100
    }

    private var orderedQueue: MutableList<LocalTrack> = mutableListOf()
    private var calmOrderedQueue: MutableList<LocalTrack> = mutableListOf()
    private var excitedOrderedQueue: MutableList<LocalTrack> = mutableListOf()
    private var calmQueue: MutableList<LocalTrack> = mutableListOf()
    private var excitedQueue: MutableList<LocalTrack> = mutableListOf()
    private var combinedQueue: MutableList<LocalTrack> = mutableListOf()
    private var calmIndex = 0
    private var excitedIndex = 0
    private var combinedIndex = 0
    private val history = ArrayDeque<LocalTrack>()

    fun load(tracks: List<LocalTrack>) {
        orderedQueue = tracks.toMutableList()
        val (calm, excited) = tracks.partition { it.finalLabel == TrackLabel.CALM }
        calmOrderedQueue = calm.toMutableList()
        excitedOrderedQueue = excited.toMutableList()
        calmQueue = calm.shuffled(random).toMutableList()
        excitedQueue = excited.shuffled(random).toMutableList()
        combinedQueue = tracks.shuffled(random).toMutableList()
        calmIndex = 0
        excitedIndex = 0
        combinedIndex = 0
        history.clear()
    }

    fun reshuffleAll(current: LocalTrack? = null) {
        calmQueue.reshuffleAvoidingLeadingTrack(current?.takeIf { it.finalLabel == TrackLabel.CALM })
        excitedQueue.reshuffleAvoidingLeadingTrack(current?.takeIf { it.finalLabel == TrackLabel.EXCITED })
        combinedQueue.reshuffleAvoidingLeadingTrack(current)
        calmIndex = 0
        excitedIndex = 0
        combinedIndex = 0
        history.clear()
    }

    fun nextCombined(
        current: LocalTrack?,
        queueMode: QueueMode,
        repeatCurrent: Boolean = false,
    ): LocalTrack? {
        return when (queueMode) {
            QueueMode.SHUFFLE -> {
                current?.let(::rememberHistory)
                val track = combinedQueue.nextAt(combinedIndex)
                combinedIndex = combinedQueue.bumpIndex(combinedIndex, track)
                track
            }
            QueueMode.LIST_LOOP -> nextSequential(orderedQueue, current, repeatCurrent = false)
            QueueMode.SINGLE_REPEAT -> nextSequential(orderedQueue, current, repeatCurrent)
        }
    }

    fun nextForLabel(
        label: TrackLabel,
        current: LocalTrack?,
        queueMode: QueueMode,
        repeatCurrent: Boolean = false,
    ): LocalTrack? {
        return when (queueMode) {
            QueueMode.SHUFFLE -> {
                current?.let(::rememberHistory)
                when (label) {
                    TrackLabel.CALM -> {
                        val track = calmQueue.nextAt(calmIndex)
                        calmIndex = calmQueue.bumpIndex(calmIndex, track)
                        track
                    }
                    TrackLabel.EXCITED -> {
                        val track = excitedQueue.nextAt(excitedIndex)
                        excitedIndex = excitedQueue.bumpIndex(excitedIndex, track)
                        track
                    }
                }
            }
            QueueMode.LIST_LOOP -> nextSequential(queueForLabel(label, ordered = true), current, false)
            QueueMode.SINGLE_REPEAT -> nextSequential(
                queueForLabel(label, ordered = true),
                current,
                repeatCurrent,
            )
        }
    }

    fun previousCombined(current: LocalTrack?, queueMode: QueueMode): LocalTrack? {
        return when (queueMode) {
            QueueMode.SHUFFLE -> previousFromHistory(current)
            QueueMode.LIST_LOOP,
            QueueMode.SINGLE_REPEAT -> previousSequential(orderedQueue, current)
        }
    }

    fun previousForLabel(label: TrackLabel, current: LocalTrack?, queueMode: QueueMode): LocalTrack? {
        return when (queueMode) {
            QueueMode.SHUFFLE -> previousMatchingHistory(label, current)
            QueueMode.LIST_LOOP,
            QueueMode.SINGLE_REPEAT -> previousSequential(queueForLabel(label, ordered = true), current)
        }
    }

    fun hasLabel(label: TrackLabel): Boolean {
        return when (label) {
            TrackLabel.CALM -> calmQueue.isNotEmpty()
            TrackLabel.EXCITED -> excitedQueue.isNotEmpty()
        }
    }

    fun playlistForCombined(queueMode: QueueMode): List<LocalTrack> {
        return when (queueMode) {
            QueueMode.SHUFFLE -> combinedQueue.toList()
            QueueMode.LIST_LOOP,
            QueueMode.SINGLE_REPEAT -> orderedQueue.toList()
        }
    }

    fun playlistForLabel(label: TrackLabel, queueMode: QueueMode): List<LocalTrack> {
        return when (queueMode) {
            QueueMode.SHUFFLE -> queueForLabel(label, ordered = false).toList()
            QueueMode.LIST_LOOP,
            QueueMode.SINGLE_REPEAT -> queueForLabel(label, ordered = true).toList()
        }
    }

    fun findTrackById(trackId: String): LocalTrack? {
        return orderedQueue.firstOrNull { it.id == trackId }
    }

    fun selectTrack(track: LocalTrack, current: LocalTrack?, activeLabel: TrackLabel?) {
        if (current != null && current.id != track.id) {
            rememberHistory(current)
        }

        when (activeLabel) {
            TrackLabel.CALM -> calmIndex = calmQueue.nextIndexAfter(track)
            TrackLabel.EXCITED -> excitedIndex = excitedQueue.nextIndexAfter(track)
            null -> combinedIndex = combinedQueue.nextIndexAfter(track)
        }
    }

    private fun previousFromHistory(current: LocalTrack?): LocalTrack? {
        if (history.isEmpty()) {
            return current
        }
        return history.removeLast()
    }

    private fun previousMatchingHistory(label: TrackLabel, current: LocalTrack?): LocalTrack? {
        if (history.isEmpty()) {
            return current
        }

        val skipped = ArrayDeque<LocalTrack>()
        while (history.isNotEmpty()) {
            val track = history.removeLast()
            if (track.finalLabel == label) {
                while (skipped.isNotEmpty()) {
                    history.addLast(skipped.removeLast())
                }
                return track
            }
            skipped.addLast(track)
        }
        while (skipped.isNotEmpty()) {
            history.addLast(skipped.removeLast())
        }
        return current
    }

    private fun rememberHistory(track: LocalTrack) {
        if (history.lastOrNull()?.id == track.id) return
        history.addLast(track)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeFirst()
        }
    }

    private fun nextSequential(
        queue: List<LocalTrack>,
        current: LocalTrack?,
        repeatCurrent: Boolean,
    ): LocalTrack? {
        // All queue variants share the same track IDs, so current.id is stable across mode switches.
        if (queue.isEmpty()) return null
        if (repeatCurrent && current != null) {
            return current
        }
        if (current == null) {
            return queue.first()
        }

        val currentIndex = queue.indexOfFirst { it.id == current.id }
        if (currentIndex == -1 || currentIndex == queue.lastIndex) {
            return queue.first()
        }
        return queue[currentIndex + 1]
    }

    private fun previousSequential(queue: List<LocalTrack>, current: LocalTrack?): LocalTrack? {
        if (queue.isEmpty()) return null
        if (current == null) {
            return queue.last()
        }

        val currentIndex = queue.indexOfFirst { it.id == current.id }
        if (currentIndex <= 0) {
            return queue.last()
        }
        return queue[currentIndex - 1]
    }

    private fun queueForLabel(label: TrackLabel, ordered: Boolean): List<LocalTrack> {
        return when (label) {
            TrackLabel.CALM -> if (ordered) calmOrderedQueue else calmQueue
            TrackLabel.EXCITED -> if (ordered) excitedOrderedQueue else excitedQueue
        }
    }

    private fun MutableList<LocalTrack>.nextAt(index: Int): LocalTrack? {
        if (isEmpty()) return null
        if (index in indices) return this[index]
        return first()
    }

    private fun MutableList<LocalTrack>.bumpIndex(index: Int, lastTrack: LocalTrack?): Int {
        if (isEmpty()) return 0
        val next = index + 1
        if (next < size) return next
        reshuffleAvoidingLeadingTrack(lastTrack)
        return 0
    }

    private fun List<LocalTrack>.nextIndexAfter(track: LocalTrack): Int {
        if (isEmpty()) return 0
        val currentIndex = indexOfFirst { it.id == track.id }
        if (currentIndex == -1 || currentIndex == lastIndex) {
            return 0
        }
        return currentIndex + 1
    }

    private fun MutableList<LocalTrack>.reshuffleAvoidingLeadingTrack(avoidTrack: LocalTrack?) {
        if (isEmpty()) return
        shuffle(random)
        if (avoidTrack == null || size <= 1 || first().id != avoidTrack.id) {
            return
        }

        val swapIndex = 1 + random.nextInt(size - 1)
        val firstTrack = this[0]
        this[0] = this[swapIndex]
        this[swapIndex] = firstTrack
    }
}
