package com.example.allinonedailer.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.QuietHoursGate
import com.example.allinonedailer.data.QuietHoursRepository
import com.example.allinonedailer.data.QuietHoursRules
import com.example.allinonedailer.data.ScreenAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpamCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "SpamCallScreening"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onScreenCall(callDetails: Call.Details) {
        val rawHandle = callDetails.handle?.schemeSpecificPart
        // Emergency calls (and an emergency service calling back) and outgoing
        // calls are never screened: no block list, rule, hidden or spam check.
        if (isExempt(callDetails, rawHandle)) {
            Log.d(TAG, "Allowing emergency or outgoing call without screening")
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        val hidden = rawHandle.isNullOrBlank() ||
                callDetails.handlePresentation == TelecomManager.PRESENTATION_RESTRICTED
        val number = if (hidden) "" else rawHandle.orEmpty()
        Log.d(TAG, "Screening incoming call from: ${if (hidden) "hidden number" else number}")

        // Block rules first, then quiet hours (silence, never reject).
        serviceScope.launch {
            val repository = CallerIdRepository(applicationContext)
            val quiet = QuietHoursRepository(applicationContext)

            // Hidden callers are blocked only with the switch on. Otherwise block
            // when the user has blocklisted or reported the number, a "starts
            // with" rule matches (both give source "blocklist"), or when spam
            // blocking is enabled and the lookup flagged it as spam.
            val blocked = if (hidden) {
                repository.isBlockHiddenEnabled()
            } else {
                val info = repository.lookupCallerInfoAsync(number)
                (info.source == "blocklist" || (repository.isSpamBlockEnabled() && info.isSpam)).also {
                    if (it) Log.d(TAG, "Block match for $number (${info.spamCategory ?: info.source})")
                }
            }

            val now = System.currentTimeMillis()
            val quietNow = !blocked && quiet.isQuietNow(now)
            // Only worth the call-log read when we'd otherwise block or silence.
            val emergencyCallback = (blocked || quietNow) && quiet.dialledEmergencyRecently(now)
            val decision = QuietHoursRules.decide(
                config = quiet.config(),
                emergency = emergencyCallback,
                blocked = blocked,
                quietNow = quietNow,
                favourite = quietNow && !hidden && quiet.isFavourite(number),
                vip = quietNow && !hidden && quiet.isVip(number),
                previousCallAt = if (hidden) null else quiet.previousCallAt(number),
                now = now
            )
            if (!hidden) quiet.recordIncoming(number, now)
            Log.d(TAG, "Decision: ${decision.action} (${decision.reason})")

            val response = CallResponse.Builder()
            when (decision.action) {
                ScreenAction.BLOCK -> response
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                // Rings silently and still shows as a missed call. Android 10+ only.
                ScreenAction.SILENCE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    response.setSilenceCall(true)
                    QuietHoursGate.markSilenced(number)
                }
                ScreenAction.ALLOW -> Unit
            }
            respondToCall(callDetails, response.build())
        }
    }

    private fun isExempt(details: Call.Details, handle: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            details.callDirection == Call.Details.DIRECTION_OUTGOING
        ) return true
        if (details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
        ) return true
        return !handle.isNullOrBlank() && CallPlacer.isEmergency(applicationContext, handle)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
