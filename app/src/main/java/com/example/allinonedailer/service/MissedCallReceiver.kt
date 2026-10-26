package com.example.allinonedailer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * As the default dialer, Telecom hands missed-call notifications to us instead
 * of showing its own. A count of 0 means Telecom wants them cleared.
 */
class MissedCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (count <= 0) {
                    MissedCallNotifier.cancelAll(app)
                } else {
                    MissedCallNotifier.show(app, count, number)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
