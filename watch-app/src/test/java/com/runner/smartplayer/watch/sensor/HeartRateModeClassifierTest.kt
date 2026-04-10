package com.runner.smartplayer.watch.sensor

import com.runner.smartplayer.watch.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateModeClassifierTest {
    @Test
    fun remainsCalmBelowThreshold() {
        val classifier = HeartRateModeClassifier()
        repeat(10) {
            assertNull(classifier.submitSample(179, sensorReady = true))
        }
        assertEquals(PlaybackMode.CALM, classifier.currentMode())
    }

    @Test
    fun switchesToExcitedAfterStableSamples() {
        val classifier = HeartRateModeClassifier()
        repeat(9) {
            assertNull(classifier.submitSample(180, sensorReady = true))
        }
        assertEquals(PlaybackMode.EXCITED, classifier.submitSample(181, sensorReady = true))
        assertEquals(PlaybackMode.EXCITED, classifier.currentMode())
    }

    @Test
    fun ignoresUnreliableSamples() {
        val classifier = HeartRateModeClassifier()
        repeat(5) { classifier.submitSample(181, sensorReady = true) }
        repeat(20) {
            assertNull(classifier.submitSample(181, sensorReady = false))
        }
        assertEquals(PlaybackMode.CALM, classifier.currentMode())
    }

    @Test
    fun returnsToCalmAfterDrop() {
        val classifier = HeartRateModeClassifier()
        repeat(10) { classifier.submitSample(181, sensorReady = true) }
        repeat(9) {
            assertNull(classifier.submitSample(170, sensorReady = true))
        }
        assertEquals(PlaybackMode.CALM, classifier.submitSample(170, sensorReady = true))
    }
}
