package com.example.allinonedailer.service

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot

/** Ringing patterns for the Glyph Matrix; the names are stored in prefs, so don't rename them. */
enum class GlyphPattern { PULSE, RIPPLE, SPIN, HEARTBEAT, FLASH }

/**
 * Draws Glyph Matrix frames: an n×n array of LED brightness 0–255, row by row
 * from the top left. Pure Kotlin so the JVM tests can run it. The matrix is
 * round, so everything is drawn inside the circle; corners stay dark.
 */
object GlyphPatterns {

    private const val FULL = 255

    /** One frame of a ringing pattern, [t] ms after it started. */
    fun ringing(pattern: GlyphPattern, n: Int, t: Long): IntArray = when (pattern) {
        // The whole disc breathes in and out.
        GlyphPattern.PULSE -> {
            val level = 0.15 + 0.85 * (0.5 - 0.5 * cos(2 * PI * (t % 1600) / 1600.0))
            disc(n) { _, _ -> level }
        }
        // Rings spread out from the centre.
        GlyphPattern.RIPPLE -> {
            val radius = n / 2.0
            val front = (t % 1200) / 1200.0 * (radius + 2)
            disc(n) { d, _ -> exp(-(d - front) * (d - front) / 1.2) }
        }
        // A bright head with a fading tail runs round the edge.
        GlyphPattern.SPIN -> {
            val head = (t % 1200) / 1200.0
            ring(n) { a ->
                val behind = (head - a + 1.0) % 1.0
                if (behind < 0.4) 1.0 - behind / 0.4 else 0.0
            }
        }
        // Two quick beats, then a pause.
        GlyphPattern.HEARTBEAT -> {
            val phase = t % 1400
            val level = when {
                phase < 140 -> 1.0
                phase < 280 -> 0.1
                phase < 420 -> 0.8
                else -> 0.1 * (1 - (phase - 420) / 980.0)
            }
            disc(n) { d, _ -> if (d <= n / 2.0 - 2) level else level * 0.5 }
        }
        // Hard on / off strobe.
        GlyphPattern.FLASH -> {
            val on = (t % 600) < 300
            disc(n) { _, _ -> if (on) 1.0 else 0.0 }
        }
    }

    /**
     * Call timer: the edge is a clock face of 12 segments, one more lit per
     * minute (after 12 minutes it starts again), and the centre dot ticks
     * every second. Dimmer than ringing; it's on for the whole call.
     */
    fun callTimer(n: Int, elapsedMs: Long): IntArray {
        val minutes = (elapsedMs / 60_000).toInt()
        val lit = if (minutes == 0) 0 else (minutes - 1) % 12 + 1
        val tick = (elapsedMs / 1000) % 2 == 0L
        val frame = ring(n) { a -> if ((a * 12).toInt() < lit) 0.55 else 0.06 }
        centreDot(n, frame, if (tick) 0.35 else 0.0)
        return frame
    }

    /** Missed call: the centre dot, [on] or off. The controller times the slow blink. */
    fun missed(n: Int, on: Boolean): IntArray =
        IntArray(n * n).also { centreDot(n, it, if (on) 0.8 else 0.0) }

    /** Distance of pixel ([x], [y]) from the centre of an n×n matrix. */
    private fun distance(n: Int, x: Int, y: Int): Double {
        val c = (n - 1) / 2.0
        return hypot(x - c, y - c)
    }

    /** Fraction of a turn clockwise from 12 o'clock, 0 until 1. */
    private fun angle(n: Int, x: Int, y: Int): Double {
        val c = (n - 1) / 2.0
        val a = atan2(x - c, c - y) / (2 * PI)
        return if (a < 0) a + 1 else a
    }

    private fun level(value: Double): Int = (value.coerceIn(0.0, 1.0) * FULL).toInt()

    /** Every pixel inside the circle, brightness from its distance and angle. */
    private fun disc(n: Int, brightness: (distance: Double, angle: Double) -> Double): IntArray {
        val frame = IntArray(n * n)
        val radius = n / 2.0
        for (y in 0 until n) for (x in 0 until n) {
            val d = distance(n, x, y)
            if (d <= radius) frame[y * n + x] = level(brightness(d, angle(n, x, y)))
        }
        return frame
    }

    /** The outer two-pixel band of the circle, brightness by angle. */
    private fun ring(n: Int, brightness: (angle: Double) -> Double): IntArray {
        val radius = n / 2.0
        return disc(n) { d, a -> if (d >= radius - 2) brightness(a) else 0.0 }
    }

    private fun centreDot(n: Int, frame: IntArray, value: Double) {
        for (y in 0 until n) for (x in 0 until n) {
            if (distance(n, x, y) <= 1.5) frame[y * n + x] = level(value)
        }
    }
}
