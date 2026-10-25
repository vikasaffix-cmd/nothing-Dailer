package com.example.allinonedailer.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.allinonedailer.data.CallerIdRepository
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
        if (rawHandle == null) {
            respondToCall(callDetails, CallResponse.Builder().build()) // allow
            return
        }
        Log.d(TAG, "Screening incoming call from: $rawHandle")

        serviceScope.launch {
            val repository = CallerIdRepository(applicationContext)
            val info = repository.lookupCallerInfoAsync(rawHandle)

            // Block when the user has blocklisted the number, or when spam
            // blocking is enabled and the lookup flagged it as spam.
            val shouldBlock = info.source == "blocklist" ||
                    (repository.isSpamBlockEnabled() && info.isSpam)

            val response = CallResponse.Builder()
            if (shouldBlock) {
                Log.d(TAG, "Blocking call from $rawHandle (${info.spamCategory ?: info.source})")
                response
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
            }
            respondToCall(callDetails, response.build())
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
