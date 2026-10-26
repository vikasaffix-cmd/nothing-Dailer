package com.example.allinonedailer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.telecom.Call
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import com.example.allinonedailer.R
import com.example.allinonedailer.ui.MainActivity

/**
 * The ongoing-call notification: CallStyle.forOngoingCall, posted from
 * CallService as a phoneCall foreground service, so Android shows its
 * status-bar call chip with the timer (Android 12+) and a tap returns to the
 * call. CallRecordingService reuses [ID] and [current] for its own foreground
 * notification, so recording a call doesn't add a second one.
 */
object OngoingCallNotification {
    private const val TAG = "OngoingCall"
    private const val CHANNEL_ID = "ongoing_calls"
    const val ID = 2002

    /** The notification on screen now, or null when no call is up. */
    var current: Notification? = null
        private set

    private var lastKey: String? = null
    private var foreground = false

    private val shownStates = setOf(
        Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING,
        Call.STATE_CONNECTING, Call.STATE_PULLING_CALL
    )

    /** Called whenever the call list changes; reposts only when what it shows changed. */
    fun update(service: Service, call: ActiveCallData?, recording: Boolean) {
        if (call == null || call.state !in shownStates) {
            stop(service)
            return
        }
        val key = listOf(
            call.callId, call.callerName, call.phoneNumber, call.state,
            call.connectTimeMillis, call.accountLabel, recording
        ).joinToString("|")
        if (key == lastKey) return
        lastKey = key

        val notification = build(service, call, recording)
        current = notification
        if (!foreground) {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                } else {
                    0
                }
                ServiceCompat.startForeground(service, ID, notification, type)
                foreground = true
                return
            } catch (e: Exception) {
                // Not allowed as a foreground service right now: still show it as a plain notification.
                Log.w(TAG, "startForeground failed; posting the call notification normally", e)
            }
        }
        try {
            service.getSystemService(NotificationManager::class.java).notify(ID, notification)
        } catch (e: Exception) {
            // No notification permission, or (outside a foreground service) Android refuses the CallStyle.
            Log.w(TAG, "Couldn't post the ongoing-call notification", e)
        }
    }

    fun stop(service: Service) {
        if (current == null && !foreground) return
        current = null
        lastKey = null
        if (foreground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        service.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    private fun build(service: Service, call: ActiveCallData, recording: Boolean): Notification {
        val manager = service.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    service.getString(R.string.ongoing_calls_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            service, 10,
            Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val hangUp = PendingIntent.getBroadcast(
            service, 11,
            Intent(service, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANG_UP),
            flags
        )

        val name = call.callerName ?: call.phoneNumber
        val person = Person.Builder().setName(name).setImportant(true).build()
        val text = service.getString(
            when {
                call.state == Call.STATE_HOLDING -> R.string.on_hold
                call.state != Call.STATE_ACTIVE -> R.string.calling
                recording -> R.string.ongoing_call_recording
                else -> R.string.ongoing_call
            }
        )
        val timed = call.state == Call.STATE_ACTIVE && call.connectTimeMillis > 0

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(name)
            .setContentText(text)
            .setSubText(call.accountLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setUsesChronometer(timed)
            .setShowWhen(timed)
            .setWhen(if (timed) call.connectTimeMillis else System.currentTimeMillis())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .build()
    }
}
