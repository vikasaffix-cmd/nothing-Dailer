package com.example.allinonedailer.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import com.example.allinonedailer.data.Haptics

/**
 * Flip to silence: while a call rings, turning the phone face-down silences the
 * ringer and its vibration (TelecomManager.silenceRinger, allowed for the default
 * dialer). The call keeps ringing silently and ends up as missed if unanswered.
 *
 * The sensor is registered only while something rings ([update]) and is
 * unregistered as soon as it fires or the ringing stops.
 */
class FlipToSilence(private val context: Context, private val onSilenced: () -> Unit) : SensorEventListener {

    companion object {
        private const val TAG = "FlipToSilence"
        private const val PREFS = "flip_to_silence"
        private const val KEY_ENABLED = "enabled"
        /** Low-pass weight for the raw accelerometer (the gravity sensor needs none). */
        private const val SMOOTHING = 0.8f

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
        fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var detector: FaceDownDetector? = null
    private var sensorType = 0
    private var smoothedZ = Float.NaN

    /** True once this ringing was silenced by a flip; cleared when the ringing stops. */
    var silenced = false
        private set

    /** Call whenever the calls change; [ringing] = some call is ringing right now. */
    fun update(ringing: Boolean) {
        if (!ringing) {
            stop()
            silenced = false
            return
        }
        if (detector != null || silenced || !isEnabled(context)) return
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        detector = FaceDownDetector()
        sensorType = sensor.type
        smoothedZ = Float.NaN
        // Readings arrive on the main thread; a handful a second is plenty.
        if (sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)) {
            Log.d(TAG, "Listening (${sensor.name})")
        } else {
            detector = null
        }
    }

    fun stop() {
        if (detector == null) return
        sensors?.unregisterListener(this)
        detector = null
        Log.d(TAG, "Stopped listening")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val d = detector ?: return
        val raw = event.values[2]
        val z = if (sensorType == Sensor.TYPE_GRAVITY || smoothedZ.isNaN()) raw
        else SMOOTHING * smoothedZ + (1 - SMOOTHING) * raw
        smoothedZ = z
        if (!d.onReading(z, event.timestamp / 1_000_000L)) return

        stop()
        silenced = true
        try {
            context.getSystemService(TelecomManager::class.java)?.silenceRinger()
            Log.d(TAG, "Face-down: ringer silenced")
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to silence the ringer", e)
        }
        onSilenced()
        // A short buzz to confirm, once Telecom's own vibration has stopped.
        main.postDelayed({ Haptics.play(context, Haptics.Effect.SILENCED) }, 250)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
