package com.runner.smartplayer.watch.player

import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.TrackLabel
import kotlin.random.Random

class AdaptiveQueueEngine(
    private val random: Random = Random.Default,
) {
    private var calmQueue: MutableList<LocalTrack> = mutableListOf()
    private var excitedQueue: MutableList<LocalTrack> = mutableListOf()
    private var combinedQueue: MutableList<LocalTrack> = mutableListOf()
    private var calmIndex = 0
    private var excitedIndex = 0
    private var combinedIndex = 0
    private val history = ArrayDeque<LocalTrack>()

    fun load(tracks: List<LocalTrack>) {
        calmQueue = tracks.filter { it.finalLabel == TrackLabel.CALM }.shuffled(random).toMutableList()
        excitedQueue = tracks.filter { it.finalLabel == TrackLabel.EXCITED }.shuffled(random).toMutableList()
        combinedQueue = tracks.shuffled(random).toMutableList()
        calmIndex = 0
        excitedIndex = 0
        combinedIndex = 0
        history.clear()
    }

    fun nextCombined(current: LocalTrack?): LocalTrack? {
        current?.let { history.addLast(it) }
        val track = combinedQueue.nextAt(combinedIndex)
        combinedIndex = combinedQueue.bumpIndex(combinedIndex)
        return track
    }

    fun nextForLabel(label: TrackLabel, current: LocalTrack?): LocalTrack? {
        current?.let { history.addLast(it) }
        return when (label) {
            TrackLabel.CALM -> {
                val track = calmQueue.nextAt(calmIndex)
                calmIndex = calmQueue.bumpIndex(calmIndex)
                track
            }

            TrackLabel.EXCITED -> {
                val track = excitedQueue.nextAt(excitedIndex)
                excitedIndex = excitedQueue.bumpIndex(excitedIndex)
                track
            }
        }
    }

    fun previous(current: LocalTrack?): LocalTrack? {
        if (history.isEmpty()) {
            return current
        }
        return history.removeLast()
    }

    fun hasLabel(label: TrackLabel): Boolean {
        return when (label) {
            TrackLabel.CALM -> calmQueue.isNotEmpty()
            TrackLabel.EXCITED -> excitedQueue.isNotEmpty()
        }
    }

    private fun MutableList<LocalTrack>.nextAt(index: Int): LocalTrack? {
        if (isEmpty()) return null
        if (index in indices) return this[index]
        return first()
    }

    private fun MutableList<LocalTrack>.bumpIndex(index: Int): Int {
        if (isEmpty()) return 0
        val next = index + 1
        if (next < size) return next
        shuffle(random)
        return 0
    }
}
