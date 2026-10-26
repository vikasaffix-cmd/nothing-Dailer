package com.example.allinonedailer.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log

/**
 * The one way the app places an outgoing call.
 *
 * `startActivity(ACTION_CALL)` can't place emergency calls: Telecom sends them
 * to the default dialer's DIAL screen, which is this app, so 112 would bounce
 * back to the keypad. [TelecomManager.placeCall] from the default dialer with
 * CALL_PHONE places them directly.
 *
 * Emergency numbers never get a SIM, video or block check: the system picks
 * the account. Without CALL_PHONE (or on a SecurityException) a normal number
 * opens the keypad with the number filled in, and an emergency number opens
 * the system emergency dialer.
 */
object CallPlacer {
    private const val TAG = "CallPlacer"
    /** Intent.ACTION_DIAL_EMERGENCY is hidden from the SDK. */
    private const val ACTION_DIAL_EMERGENCY = "android.intent.action.DIAL_EMERGENCY"

    /**
     * Indian emergency numbers, plus 112/911 as the GSM defaults. Used on top of
     * the system's list, which can be empty without a SIM or miss 100–108.
     */
    val KNOWN_EMERGENCY = setOf("112", "100", "101", "102", "108", "911")

    enum class Outcome { PLACED, FALLBACK, FAILED }

    sealed interface Decision {
        val number: String
        val emergency: Boolean

        /** Place through Telecom with these extras (keys are TelecomManager constants). */
        data class Place(
            override val number: String,
            override val emergency: Boolean,
            val extras: Map<String, Any>
        ) : Decision

        /** No call permission: show the number instead of calling. */
        data class Fallback(override val number: String, override val emergency: Boolean) : Decision
    }

    /** Spaces, dashes, dots and brackets dropped; digits, +, *, # and comma/semicolon kept. */
    fun clean(number: String): String = number.filter { it.isDigit() || it in "+*#,;" }

    /**
     * True when [number] is an emergency number per [systemCheck] or [KNOWN_EMERGENCY].
     * A failing system check falls back to the list.
     */
    fun isEmergency(number: String, systemCheck: (String) -> Boolean): Boolean {
        val n = clean(number)
        if (n.isEmpty()) return false
        if (n in KNOWN_EMERGENCY) return true
        return try {
            systemCheck(n)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Pure decision: what to do for [number]. [account] is the chosen SIM
     * (a PhoneAccountHandle in the app); any type so tests can use a fake.
     */
    fun decide(
        number: String,
        video: Boolean = false,
        account: Any? = null,
        speaker: Boolean = false,
        hasCallPermission: Boolean,
        systemCheck: (String) -> Boolean
    ): Decision {
        val n = clean(number)
        val emergency = isEmergency(n, systemCheck)
        if (!hasCallPermission) return Decision.Fallback(n, emergency)
        if (emergency) return Decision.Place(n, true, emptyMap())
        val extras = buildMap<String, Any> {
            if (video) put(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
            if (account != null) put(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            if (speaker) put(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
        }
        return Decision.Place(n, false, extras)
    }

    /** The system's emergency check; on 26–28 the older PhoneNumberUtils one. */
    fun systemEmergencyCheck(context: Context): (String) -> Boolean = { n ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(n) == true
        } else {
            @Suppress("DEPRECATION")
            PhoneNumberUtils.isEmergencyNumber(n)
        }
    }

    /** True when [number] is an emergency number on this phone. */
    fun isEmergency(context: Context, number: String): Boolean =
        isEmergency(number, systemEmergencyCheck(context))

    /**
     * Places a call to [number]. Callers keep their own toasts for [Outcome.FAILED].
     * Without a [sim], [autoSim] lets Smart SIM pick one; the keypad passes false
     * because its SIM toggle already shows that pick (none lit = ask).
     */
    fun placeCall(
        context: Context,
        number: String,
        video: Boolean = false,
        sim: PhoneAccountHandle? = null,
        speaker: Boolean = false,
        autoSim: Boolean = true
    ): Outcome {
        val permitted = context.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val account = sim ?: if (autoSim) smartSim(context, number) else null
        val decision = decide(number, video, account, speaker, permitted, systemEmergencyCheck(context))
        if (decision.number.isEmpty()) return Outcome.FAILED
        return when (decision) {
            is Decision.Place -> try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                    ?: return fallback(context, decision.number, decision.emergency)
                telecom.placeCall(Uri.fromParts("tel", decision.number, null), toBundle(decision.extras))
                Outcome.PLACED
            } catch (e: SecurityException) {
                Log.w(TAG, "placeCall not allowed", e)
                fallback(context, decision.number, decision.emergency)
            } catch (e: Exception) {
                Log.w(TAG, "placeCall failed", e)
                fallback(context, decision.number, decision.emergency)
            }
            is Decision.Fallback -> fallback(context, decision.number, decision.emergency)
        }
    }

    /**
     * Smart SIM's pick (pinned > last used > same network) when no SIM was
     * chosen. Never for an emergency number; any failure means no pick, so
     * the call still goes out on the system default.
     */
    private fun smartSim(context: Context, number: String): PhoneAccountHandle? = try {
        if (isEmergency(context, number)) null else SmartSim.pick(context, number)
    } catch (e: Exception) {
        Log.w(TAG, "Smart SIM failed", e)
        null
    }

    private fun toBundle(extras: Map<String, Any>): Bundle = Bundle().apply {
        for ((key, value) in extras) when (value) {
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is PhoneAccountHandle -> putParcelable(key, value)
        }
    }

    /**
     * Shows the number without calling: the system emergency dialer for an
     * emergency number (it can call without our permission), else ACTION_DIAL,
     * which opens this app's keypad with the number.
     */
    private fun fallback(context: Context, number: String, emergency: Boolean): Outcome {
        val uri = Uri.fromParts("tel", number, null)
        val intents = buildList<Intent> {
            // The system emergency dialer (com.android.phone) handles DIAL_EMERGENCY.
            if (emergency) add(Intent(ACTION_DIAL_EMERGENCY, uri))
            add(Intent(Intent.ACTION_DIAL, uri))
        }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return Outcome.FALLBACK
            } catch (e: Exception) {
                Log.w(TAG, "Fallback ${intent.action} failed", e)
            }
        }
        return Outcome.FAILED
    }
}
