package com.example.allinonedailer.data

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import com.example.allinonedailer.R

/** How hard the app's vibrations feel. */
enum class HapticStrength(@StringRes val label: Int) {
    LIGHT(R.string.vibration_light), MEDIUM(R.string.vibration_medium), STRONG(R.string.vibration_strong)
}

/**
 * Every vibration the app plays goes through here, so they all feel alike and
 * all follow the Vibration settings, the system "touch vibration" switch and
 * Do Not Disturb.
 */
object Haptics {

    enum class Effect {
        /** A keypad key, backspace, or the in-call DTMF keypad. */
        KEY,
        /** Long-press on a key: `0` for +, clear-all, speed dial. */
        KEY_LONG,
        /** Any in-call button. */
        BUTTON,
        /** Crossing a threshold: the answer knob, a new letter on the A–Z scroller. */
        TICK,
        /** An outgoing call is being placed. */
        CALL_PLACED,
        /** The outgoing call was answered: a short double buzz. */
        CONNECTED,
        /** A call ended: one longer buzz. */
        ENDED,
        /** A second call is ringing during a call. */
        CALL_WAITING,
        /** Flip to silence muted a ringing call: two short taps. */
        SILENCED
    }

    private const val TAG = "Haptics"
    private const val PREFS = "haptics_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRENGTH = "strength"
    private const val KEY_KEYPAD = "keypad"
    private const val KEY_CALL_EVENTS = "call_events"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()

    fun strength(context: Context): HapticStrength =
        prefs(context).getString(KEY_STRENGTH, null)
            ?.let { name -> HapticStrength.entries.firstOrNull { it.name == name } }
            ?: HapticStrength.MEDIUM

    fun setStrength(context: Context, strength: HapticStrength) =
        prefs(context).edit().putString(KEY_STRENGTH, strength.name).apply()

    fun isKeypadEnabled(context: Context) = prefs(context).getBoolean(KEY_KEYPAD, true)
    fun setKeypadEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_KEYPAD, on).apply()

    fun isCallEventsEnabled(context: Context) = prefs(context).getBoolean(KEY_CALL_EVENTS, true)
    fun setCallEventsEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_CALL_EVENTS, on).apply()

    private val Effect.isTouch get() = this in setOf(Effect.KEY, Effect.KEY_LONG, Effect.BUTTON, Effect.TICK, Effect.CALL_PLACED)

    fun play(context: Context, effect: Effect) {
        try {
            if (!allowed(context, effect)) return
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            val vibration = effectFor(vibrator, effect, strength(context))
            Log.d(TAG, "play $effect")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Touch usages follow the system touch-vibration intensity; call events
                // use a usage the vibrator still allows while the app is in the background.
                val usage = if (effect.isTouch) VibrationAttributes.USAGE_TOUCH
                else VibrationAttributes.USAGE_COMMUNICATION_REQUEST
                vibrator.vibrate(vibration, VibrationAttributes.createForUsage(usage))
            } else {
                val usage = if (effect.isTouch) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                else AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibration, AudioAttributes.Builder().setUsage(usage).build())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't vibrate for $effect", e)
        }
    }

    private fun allowed(context: Context, effect: Effect): Boolean {
        if (!isEnabled(context)) return false
        when (effect) {
            Effect.KEY, Effect.KEY_LONG -> if (!isKeypadEnabled(context)) return false
            Effect.CONNECTED, Effect.ENDED -> if (!isCallEventsEnabled(context)) return false
            else -> Unit
        }
        if (effect.isTouch) {
            // Settings › Sound & vibration › Touch feedback.
            val touch = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
            return touch != 0
        }
        // Call events stay quiet under Do Not Disturb.
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return filter == null || filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun amplitude(strength: HapticStrength) = when (strength) {
        HapticStrength.LIGHT -> 90
        HapticStrength.MEDIUM -> 170
        HapticStrength.STRONG -> 255
    }

    private fun scale(strength: HapticStrength) = when (strength) {
        HapticStrength.LIGHT -> 0.45f
        HapticStrength.MEDIUM -> 0.75f
        HapticStrength.STRONG -> 1f
    }

    /**
     * How much longer "on" time to give when the motor can't vary its strength
     * (no amplitude control, like the A069P's): a longer buzz feels stronger.
     */
    private fun lengthFactor(vibrator: Vibrator, strength: HapticStrength) =
        if (vibrator.hasAmplitudeControl()) 1f else when (strength) {
            HapticStrength.LIGHT -> 0.6f
            HapticStrength.MEDIUM -> 1f
            HapticStrength.STRONG -> 1.6f
        }

    private fun effectFor(vibrator: Vibrator, effect: Effect, strength: HapticStrength): VibrationEffect =
        when (effect) {
            Effect.KEY, Effect.BUTTON -> touch(vibrator, strength, VibrationEffect.Composition.PRIMITIVE_CLICK,
                predefined = when (strength) {
                    HapticStrength.LIGHT -> VibrationEffect.EFFECT_TICK
                    HapticStrength.MEDIUM -> VibrationEffect.EFFECT_CLICK
                    HapticStrength.STRONG -> VibrationEffect.EFFECT_HEAVY_CLICK
                }, fallbackMs = 20)
            Effect.TICK -> touch(vibrator, strength, VibrationEffect.Composition.PRIMITIVE_TICK,
                predefined = VibrationEffect.EFFECT_TICK, fallbackMs = 12)
            Effect.KEY_LONG, Effect.CALL_PLACED -> touch(vibrator, strength, VibrationEffect.Composition.PRIMITIVE_THUD,
                predefined = VibrationEffect.EFFECT_HEAVY_CLICK, fallbackMs = 40)
            Effect.CONNECTED -> waveform(vibrator, strength, longArrayOf(0, 45, 90, 45))
            Effect.ENDED -> waveform(vibrator, strength, longArrayOf(0, 220))
            Effect.CALL_WAITING -> waveform(vibrator, strength, longArrayOf(0, 70, 90, 70, 90, 260))
            Effect.SILENCED -> waveform(vibrator, strength, longArrayOf(0, 30, 70, 30))
        }

    /**
     * A crisp click: a composed primitive scaled to [strength], else the predefined
     * effect, but only where the motor really supports them. Otherwise (and on
     * older Android) a plain one-shot buzz, which every motor can play.
     */
    private fun touch(
        vibrator: Vibrator,
        strength: HapticStrength,
        primitive: Int,
        predefined: Int,
        fallbackMs: Long
    ): VibrationEffect = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(primitive) ->
            VibrationEffect.startComposition().addPrimitive(primitive, scale(strength)).compose()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllEffectsSupported(predefined) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES ->
            VibrationEffect.createPredefined(predefined)
        else -> VibrationEffect.createOneShot(
            (fallbackMs * lengthFactor(vibrator, strength)).toLong().coerceAtLeast(1),
            if (vibrator.hasAmplitudeControl()) amplitude(strength) else VibrationEffect.DEFAULT_AMPLITUDE
        )
    }

    /** On/off timings starting with a pause; every "on" segment gets the same strength. */
    private fun waveform(vibrator: Vibrator, strength: HapticStrength, timings: LongArray): VibrationEffect {
        val factor = lengthFactor(vibrator, strength)
        val scaled = LongArray(timings.size) { i -> if (i % 2 == 1) (timings[i] * factor).toLong() else timings[i] }
        if (!vibrator.hasAmplitudeControl()) return VibrationEffect.createWaveform(scaled, -1)
        val amps = IntArray(timings.size) { i -> if (i % 2 == 1) amplitude(strength) else 0 }
        return VibrationEffect.createWaveform(scaled, amps, -1)
    }
}
