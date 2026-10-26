package com.example.allinonedailer.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.util.Log
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.QuietHoursGate
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glyph lights settings, in prefs file `glyph_prefs`. Everything is off until
 * the user turns on [isEnabled] in Settings › Glyph lights.
 */
object GlyphSettings {
    const val PREFS = "glyph_prefs"
    const val KEY_ENABLED = "enabled"
    const val KEY_INCOMING = "incoming"
    const val KEY_PATTERN = "pattern"
    const val KEY_FAVOURITES_PATTERN = "favourites_pattern"
    const val KEY_MISSED = "missed"
    const val KEY_CALL_TIMER = "call_timer"
    /** Per-number pattern: this prefix + [PhoneNumbers.key]. */
    const val KEY_CONTACT_PREFIX = "contact_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun isIncomingEnabled(context: Context) = prefs(context).getBoolean(KEY_INCOMING, true)
    fun isMissedEnabled(context: Context) = prefs(context).getBoolean(KEY_MISSED, true)
    fun isCallTimerEnabled(context: Context) = prefs(context).getBoolean(KEY_CALL_TIMER, false)

    fun pattern(context: Context) = read(context, KEY_PATTERN) ?: GlyphPattern.PULSE
    fun favouritesPattern(context: Context) = read(context, KEY_FAVOURITES_PATTERN) ?: GlyphPattern.HEARTBEAT

    /** The pattern picked for this number, or null to use the default / favourites one. */
    fun contactPattern(context: Context, number: String): GlyphPattern? =
        PhoneNumbers.key(number).takeIf { it.isNotEmpty() }?.let { read(context, KEY_CONTACT_PREFIX + it) }

    fun setBoolean(context: Context, key: String, on: Boolean) {
        prefs(context).edit().putBoolean(key, on).apply()
        GlyphController.refresh(context)
    }

    fun setPattern(context: Context, key: String, pattern: GlyphPattern) {
        prefs(context).edit().putString(key, pattern.name).apply()
        GlyphController.refresh(context)
    }

    fun setContactPattern(context: Context, number: String, pattern: GlyphPattern?) {
        val key = PhoneNumbers.key(number).takeIf { it.isNotEmpty() } ?: return
        prefs(context).edit().apply {
            if (pattern == null) remove(KEY_CONTACT_PREFIX + key) else putString(KEY_CONTACT_PREFIX + key, pattern.name)
        }.apply()
        GlyphController.refresh(context)
    }

    private fun read(context: Context, key: String): GlyphPattern? =
        prefs(context).getString(key, null)?.let { name -> GlyphPattern.entries.firstOrNull { it.name == name } }
}

/**
 * Drives the Glyph Matrix on the back of Nothing phones that have one, through
 * Nothing's official Glyph Matrix SDK (com.nothing.ketchum): a pattern while a
 * call rings, an optional minute counter during a call, and a slow blink while
 * missed calls are unread.
 *
 * [update] is called from [CallService] whenever the calls change and
 * [setMissed] from [MissedCallNotifier]; everything runs on the main thread.
 * Whatever the app shows goes off as soon as nothing asks for it, and an
 * uncaught exception turns it off before the process dies.
 */
object GlyphController {

    private const val TAG = "GlyphController"
    private const val DEFAULT_LENGTH = 13
    private const val PREVIEW_MS = 4000L

    private sealed interface Mode {
        data object Off : Mode
        data class Ringing(val number: String, val pattern: GlyphPattern) : Mode
        data class InCall(val connectedAt: Long) : Mode
        data object Missed : Mode
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var app: Context? = null
    private var manager: GlyphMatrixManager? = null
    private var connected = false
    private var mode: Mode = Mode.Off
    private var animation: Job? = null
    private var calls: List<ActiveCallData> = emptyList()
    private var missedPending = false
    private var preview: GlyphPattern? = null
    private var crashHookInstalled = false
    private var reportedFailure = false
    /** Ringing pattern per number, so a call's lookups run once. */
    private val patternCache = HashMap<String, GlyphPattern>()

    /** This phone has a Glyph Matrix the SDK can drive: Phone (3) or Phone (4a) Pro. */
    val isSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && try {
            Common.is25111p() || Common.is23112()
        } catch (e: Throwable) {
            false
        }
    }

    /** The calls changed. */
    fun update(context: Context, calls: List<ActiveCallData>) = onMain(context) {
        this.calls = calls
    }

    /** Unread missed calls appeared ([pending]) or were all read / cleared. */
    fun setMissed(context: Context, pending: Boolean) = onMain(context) {
        missedPending = pending
    }

    /** A setting changed: re-check what should show. */
    fun refresh(context: Context) = onMain(context) {
        patternCache.clear()
    }

    /** Settings: show [pattern] for a few seconds. */
    fun preview(context: Context, pattern: GlyphPattern) {
        onMain(context) { preview = pattern }
        main.postDelayed({
            if (preview == pattern) {
                preview = null
                recompute()
            }
        }, PREVIEW_MS)
    }

    /** The call service is going away: no calls are left. Missed-call blink stays. */
    fun onCallServiceGone(context: Context) = update(context, emptyList())

    private fun onMain(context: Context, change: () -> Unit) {
        val run = {
            app = context.applicationContext
            change()
            recompute()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    private fun recompute() {
        val context = app ?: return
        val next = try {
            desiredMode(context)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't work out the Glyph state", e)
            Mode.Off
        }
        if (next == mode) return
        Log.d(TAG, "Glyph: $mode -> $next")
        mode = next
        reportedFailure = false
        animation?.cancel()
        animation = null
        when {
            next == Mode.Off -> stopMatrix()
            connected -> startAnimation()
            else -> connect(context)
        }
    }

    private fun desiredMode(context: Context): Mode {
        if (!isSupported || !GlyphSettings.isEnabled(context) || isDndOn(context)) return Mode.Off
        val ringing = calls.firstOrNull { it.state == Call.STATE_RINGING }
        if (ringing == null) patternCache.clear()
        // Quiet hours silenced this call: it rings without sound, so without lights too.
        if (ringing != null && GlyphSettings.isIncomingEnabled(context) && !QuietHoursGate.isSilenced(ringing.phoneNumber)) {
            val pattern = patternCache.getOrPut(ringing.phoneNumber) { patternFor(context, ringing.phoneNumber) }
            return Mode.Ringing(ringing.phoneNumber, pattern)
        }
        val active = calls.firstOrNull { it.state == Call.STATE_ACTIVE }
        if (active != null) {
            return if (GlyphSettings.isCallTimerEnabled(context) && active.connectTimeMillis > 0) {
                Mode.InCall(active.connectTimeMillis)
            } else {
                Mode.Off
            }
        }
        if (calls.isNotEmpty()) return Mode.Off
        preview?.let { return Mode.Ringing("", it) }
        if (missedPending && GlyphSettings.isMissedEnabled(context)) return Mode.Missed
        return Mode.Off
    }

    private fun patternFor(context: Context, number: String): GlyphPattern =
        GlyphSettings.contactPattern(context, number)
            ?: if (isStarred(context, number)) GlyphSettings.favouritesPattern(context) else GlyphSettings.pattern(context)

    private fun isStarred(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        return try {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.STARRED), null, null, null
            )?.use { cursor ->
                var starred = false
                while (!starred && cursor.moveToNext()) starred = cursor.getInt(0) == 1
                starred
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Any Do Not Disturb mode keeps the lights off. */
    private fun isDndOn(context: Context): Boolean = try {
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Exception) {
        false
    }

    private fun connect(context: Context) {
        if (manager != null) return // already connecting; onServiceConnected starts the animation
        val gm = try {
            GlyphMatrixManager.getInstance(context)
        } catch (e: Throwable) {
            Log.w(TAG, "Glyph SDK unavailable", e)
            null
        } ?: return
        manager = gm
        installCrashHook()
        gm.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                main.post {
                    if (manager !== gm) return@post
                    val registered = try {
                        gm.register(if (Common.is23112()) Glyph.DEVICE_23112 else Glyph.DEVICE_25111p)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Glyph register failed", e)
                        false
                    }
                    Log.d(TAG, "Glyph service connected, registered=$registered")
                    connected = true
                    if (mode == Mode.Off) stopMatrix() else startAnimation()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                main.post {
                    if (manager !== gm) return@post
                    Log.d(TAG, "Glyph service disconnected")
                    animation?.cancel()
                    animation = null
                    connected = false
                    manager = null
                }
            }
        })
    }

    private fun startAnimation() {
        val gm = manager ?: return
        val n = matrixLength()
        val current = mode
        animation?.cancel()
        animation = scope.launch {
            val started = SystemClock.uptimeMillis()
            while (isActive) {
                when (current) {
                    is Mode.Ringing -> {
                        show(gm, GlyphPatterns.ringing(current.pattern, n, SystemClock.uptimeMillis() - started))
                        delay(40)
                    }
                    is Mode.InCall -> {
                        show(gm, GlyphPatterns.callTimer(n, System.currentTimeMillis() - current.connectedAt))
                        delay(500)
                    }
                    Mode.Missed -> {
                        show(gm, GlyphPatterns.missed(n, on = true))
                        delay(400)
                        show(gm, GlyphPatterns.missed(n, on = false))
                        delay(3600)
                    }
                    Mode.Off -> return@launch
                }
            }
        }
    }

    private fun show(gm: GlyphMatrixManager, frame: IntArray) {
        try {
            gm.setAppMatrixFrame(frame)
        } catch (e: Exception) {
            // E.g. not allowed in the background without the debug flag; say so once per mode.
            if (!reportedFailure) Log.w(TAG, "Glyph frame refused", e)
            reportedFailure = true
        }
    }

    /** Clears what the app shows and lets go of the Glyph service. */
    private fun stopMatrix() {
        val gm = manager ?: return
        try {
            gm.closeAppMatrix()
        } catch (e: Exception) {
            Log.w(TAG, "closeAppMatrix failed", e)
        }
        try {
            gm.unInit()
        } catch (e: Exception) {
            Log.w(TAG, "Glyph unInit failed", e)
        }
        manager = null
        connected = false
    }

    private fun matrixLength(): Int = try {
        Common.getDeviceMatrixLength().takeIf { it > 0 } ?: DEFAULT_LENGTH
    } catch (e: Throwable) {
        DEFAULT_LENGTH
    }

    /** A crash anywhere in the app turns the lights off before the process goes. */
    private fun installCrashHook() {
        if (crashHookInstalled) return
        crashHookInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                manager?.closeAppMatrix()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
