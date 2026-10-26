package com.example.allinonedailer

import com.example.allinonedailer.service.GlyphPattern
import com.example.allinonedailer.service.GlyphPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphPatternsTest {

    private val n = 13

    private fun lit(frame: IntArray) = frame.count { it > 0 }

    @Test
    fun everyPatternFillsAValidFrame() {
        for (pattern in GlyphPattern.entries) for (t in 0L..3000L step 40) {
            val frame = GlyphPatterns.ringing(pattern, n, t)
            assertEquals(n * n, frame.size)
            assertTrue("$pattern at $t", frame.all { it in 0..255 })
        }
    }

    @Test
    fun cornersStayDark() {
        val frame = GlyphPatterns.ringing(GlyphPattern.FLASH, n, 0)
        for (i in listOf(0, n - 1, n * (n - 1), n * n - 1)) assertEquals(0, frame[i])
        assertTrue(frame[(n / 2) * n + n / 2] > 0)
    }

    @Test
    fun flashTurnsFullyOff() {
        assertEquals(0, lit(GlyphPatterns.ringing(GlyphPattern.FLASH, n, 400)))
    }

    @Test
    fun timerLightsOneMoreSegmentPerMinute() {
        val bright = { ms: Long -> GlyphPatterns.callTimer(n, ms).count { it > 100 } }
        val one = bright(61_000)
        val two = bright(121_000)
        assertEquals(0, bright(1_000))
        assertTrue(one > 0)
        assertTrue(two > one)
        // After 12 minutes the clock starts again.
        assertEquals(one, bright(13 * 60_000L + 1_000))
    }

    @Test
    fun missedBlinkIsOnlyTheCentreDot() {
        val on = GlyphPatterns.missed(n, on = true)
        assertTrue(lit(on) in 1..12)
        assertEquals(0, lit(GlyphPatterns.missed(n, on = false)))
    }

    @Test
    fun worksForTheLargerPhone3Matrix() {
        assertEquals(25 * 25, GlyphPatterns.ringing(GlyphPattern.SPIN, 25, 100).size)
    }
}
