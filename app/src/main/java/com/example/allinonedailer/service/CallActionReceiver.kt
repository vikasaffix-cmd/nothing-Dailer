package com.example.allinonedailer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the Decline action on the incoming-call notification, and the
 * swipe-away of missed-call notifications. Answer, Call back and Message open
 * MainActivity directly instead, because Android blocks activity starts from
 * notification trampolines like this receiver.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DECLINE = "com.example.allinonedailer.action.DECLINE_CALL"
        /** Hang up on the ongoing-call notification (status-bar call chip). */
        const val ACTION_HANG_UP = "com.example.allinonedailer.action.HANG_UP"
        const val ACTION_MISSED_CALLS_DISMISSED = "com.example.allinonedailer.action.MISSED_CALLS_DISMISSED"
        /** Call-log id of the dismissed missed call; -1 for the summary (all of them). */
        const val EXTRA_MISSED_CALL_ID = "com.example.allinonedailer.extra.MISSED_CALL_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> CallService.rejectCurrentCall()
            ACTION_HANG_UP -> CallService.disconnectCurrentCall()
            ACTION_MISSED_CALLS_DISMISSED -> {
                val app = context.applicationContext
                val id = intent.getLongExtra(EXTRA_MISSED_CALL_ID, -1L)
                val pending = goAsync()
                Thread {
                    try {
                        if (id >= 0) MissedCallNotifier.markRead(app, id) else MissedCallNotifier.clear(app)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
