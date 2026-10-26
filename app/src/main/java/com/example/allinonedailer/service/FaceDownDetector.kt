package com.example.allinonedailer.service

/**
 * Decides when the phone has been turned face-down, from gravity readings
 * (m/s², the phone's own axes: z points out of the screen). Pure Kotlin so it
 * can be unit-tested with sample sequences.
 *
 * - Face-down means gravity points out of the screen: z ≤ -[FACE_DOWN_Z]
 *   (about 35° from flat or closer).
 * - It needs a flip: nothing fires until a reading that is clearly not
 *   face-down (z ≥ -[ARM_Z]) has been seen, so a phone already lying face-down
 *   when the call starts stays ringing.
 * - The phone has to stay face-down for [HOLD_MS]; a brief tilt resets it.
 * - It fires at most once.
 */
class FaceDownDetector {

    companion object {
        const val GRAVITY = 9.81f
        /** z at or below this counts as face-down (cos 35° ≈ 0.82 g). */
        const val FACE_DOWN_Z = 0.82f * GRAVITY
        /** z at or above -this arms the detector: screen up, upright or on its side. */
        const val ARM_Z = 0.3f * GRAVITY
        const val HOLD_MS = 500L
    }

    var armed = false
        private set
    var fired = false
        private set
    private var downSinceMs = -1L

    /** Feeds one reading; true exactly once, when the flip has been held long enough. */
    fun onReading(z: Float, timeMs: Long): Boolean {
        if (fired) return false
        if (z >= -ARM_Z) armed = true
        if (!armed || z > -FACE_DOWN_Z) {
            downSinceMs = -1L
            return false
        }
        if (downSinceMs < 0) downSinceMs = timeMs
        if (timeMs - downSinceMs >= HOLD_MS) {
            fired = true
            return true
        }
        return false
    }
}
