package com.example.allinonedailer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the Decline action on the incoming-call notification. Answer opens
 * MainActivity directly instead, because Android blocks activity starts from
 * notification trampolines like this receiver.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DECLINE = "com.example.allinonedailer.action.DECLINE_CALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DECLINE) {
            CallService.rejectCurrentCall()
        }
    }
}
