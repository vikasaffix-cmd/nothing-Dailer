package com.example.allinonedailer

import com.example.allinonedailer.service.FaceDownDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDownDetectorTest {

    private val up = 9.7f      // flat, screen up
    private val hand = 4.0f    // held in the hand, tilted towards you
    private val down = -9.7f   // flat, screen down
    private val step = 60L     // SENSOR_DELAY_UI

    /** Feeds [zs] [step] ms apart from [start]; returns the times at which it fired. */
    private fun FaceDownDetector.feed(zs: List<Float>, start: Long = 0L): List<Long> =
        zs.mapIndexedNotNull { i, z -> (start + i * step).takeIf { onReading(z, it) } }

    private fun repeat(z: Float, ms: Long) = List((ms / step).toInt()) { z }

    @Test
    fun flipFromFaceUpFiresAfterHalfASecond() {
        val d = FaceDownDetector()
        val fired = d.feed(repeat(up, 600) + listOf(2f, -5f) + repeat(down, 1000))
        assertEquals(1, fired.size)
        // The first face-down reading is at 12 × 60 = 720 ms (the -5 is not yet face-down).
        val firstDown = (repeat(up, 600).size + 2) * step
        assertTrue(fired[0] - firstDown >= FaceDownDetector.HOLD_MS)
        assertTrue(fired[0] - firstDown < FaceDownDetector.HOLD_MS + step)
    }

    @Test
    fun flipFromTheHandFires() {
        assertEquals(1, FaceDownDetector().feed(repeat(hand, 300) + repeat(down, 800)).size)
    }

    @Test
    fun alreadyFaceDownWhenTheCallStartsNeverFires() {
        val d = FaceDownDetector()
        assertTrue(d.feed(repeat(down, 5000)).isEmpty())
        assertFalse(d.armed)
    }

    @Test
    fun alreadyFaceDownThenPickedUpAndFlippedFires() {
        val d = FaceDownDetector()
        val fired = d.feed(repeat(down, 2000) + repeat(up, 300) + repeat(down, 700))
        assertEquals(1, fired.size)
    }

    @Test
    fun briefTiltDoesNotFire() {
        // Face-down for under half a second, then back up, a few times.
        val wobble = repeat(up, 300) + repeat(down, 360) + repeat(up, 300) + repeat(down, 420) + repeat(up, 600)
        assertTrue(FaceDownDetector().feed(wobble).isEmpty())
    }

    @Test
    fun steepTiltIsNotFaceDown() {
        // Screen pointing down at about 45°: z ≈ -0.7 g, not enough.
        assertTrue(FaceDownDetector().feed(repeat(up, 300) + repeat(-6.9f, 2000)).isEmpty())
    }

    @Test
    fun firesOnlyOnce() {
        val d = FaceDownDetector()
        val fired = d.feed(repeat(up, 300) + repeat(down, 800) + repeat(up, 300) + repeat(down, 800))
        assertEquals(1, fired.size)
        assertTrue(d.fired)
    }
}
