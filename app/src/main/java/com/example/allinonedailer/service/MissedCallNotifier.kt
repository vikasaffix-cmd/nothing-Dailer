package com.example.allinonedailer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.allinonedailer.R
import com.example.allinonedailer.ui.MainActivity
import com.example.allinonedailer.ui.reminders.ReminderActivity
import kotlinx.coroutines.delay

/**
 * Missed-call notifications: one per unread missed call, grouped under a
 * summary when there are several. Entries come from the call log (NEW missed
 * calls), so they match what Recents shows.
 */
object MissedCallNotifier {

    private const val TAG = "MissedCallNotifier"
    private const val CHANNEL_ID = "missed_calls"
    private const val GROUP_KEY = "com.example.allinonedailer.MISSED_CALLS"
    private const val NOTIFICATION_TAG = "missed_call"
    private const val SUMMARY_ID = 0
    private const val ACCENT = 0xFFD71921.toInt()

    /** Telecom can tell us before its call-log row is written; wait this long once. */
    private const val CALL_LOG_WAIT_MS = 1500L

    private data class MissedCall(val id: Long, val number: String, val name: String?, val date: Long) {
        fun title(context: Context): String =
            name ?: number.ifBlank { context.getString(R.string.private_number) }
    }

    suspend fun show(context: Context, count: Int, latestNumber: String?) {
        var calls = queryNewMissedCalls(context, count)
        if (!latestNumber.isNullOrBlank() && calls.none { PhoneNumberUtils.compare(it.number, latestNumber) }) {
            delay(CALL_LOG_WAIT_MS)
            calls = queryNewMissedCalls(context, count)
        }
        if (calls.isEmpty() || (!latestNumber.isNullOrBlank() &&
                calls.none { PhoneNumberUtils.compare(it.number, latestNumber) })
        ) {
            // Still not in the call log: show what Telecom gave us.
            val number = latestNumber.orEmpty()
            calls = listOf(MissedCall(-1L, number, lookupContactName(context, number), System.currentTimeMillis())) +
                calls.take((count - 1).coerceAtLeast(0))
        }
        post(context, calls)
    }

    /** User opened Recents: remove the notifications and mark every missed call read. */
    fun clear(context: Context) {
        cancelAll(context)
        try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NEW, 0)
                put(CallLog.Calls.IS_READ, 1)
            }
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI, values,
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark missed calls read", e)
        }
        resetTelecomCount(context)
    }

    /** One missed call was handled (called back, messaged or swiped away). */
    fun markRead(context: Context, callId: Long) {
        getManager(context).cancel(NOTIFICATION_TAG, callId.toInt())
        try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NEW, 0)
                put(CallLog.Calls.IS_READ, 1)
            }
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI, values,
                "${CallLog.Calls._ID} = ?", arrayOf(callId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark missed call $callId read", e)
        }
        if (queryNewMissedCalls(context, 1).isEmpty()) {
            cancelAll(context)
            resetTelecomCount(context)
        }
    }

    fun cancelAll(context: Context) {
        GlyphController.setMissed(context, false)
        val manager = getManager(context)
        manager.activeNotifications
            .filter { it.tag == NOTIFICATION_TAG }
            .forEach { manager.cancel(NOTIFICATION_TAG, it.id) }
    }

    private fun post(context: Context, calls: List<MissedCall>) {
        val manager = getManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.missed_calls), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        // Drop notifications for calls that are no longer unread.
        val keep = calls.map { it.id.toInt() }.toSet() + SUMMARY_ID
        manager.activeNotifications
            .filter { it.tag == NOTIFICATION_TAG && it.id !in keep }
            .forEach { manager.cancel(NOTIFICATION_TAG, it.id) }

        try {
            calls.forEach { manager.notify(NOTIFICATION_TAG, it.id.toInt(), buildChild(context, it)) }
            GlyphController.setMissed(context, calls.isNotEmpty())
            if (calls.size > 1) {
                manager.notify(NOTIFICATION_TAG, SUMMARY_ID, buildSummary(context, calls))
            } else {
                manager.cancel(NOTIFICATION_TAG, SUMMARY_ID)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing; missed calls only show in Recents", e)
        }
    }

    private fun buildChild(context: Context, call: MissedCall) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(ACCENT)
            .setContentTitle(call.title(context))
            .setContentText(context.getString(R.string.missed_call))
            .setWhen(call.date)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_KEY)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openRecentsIntent(context))
            .setDeleteIntent(dismissIntent(context, call.id))
            .apply {
                if (call.number.isNotBlank()) {
                    addAction(0, context.getString(R.string.call_back), actionIntent(context, MainActivity.ACTION_MISSED_CALL_BACK, call, 1))
                    addAction(0, context.getString(R.string.message), actionIntent(context, MainActivity.ACTION_MISSED_CALL_MESSAGE, call, 2))
                    // 15 min / 1 h / tonight / custom, in a small picker over the current screen.
                    addAction(
                        0, context.getString(R.string.reminder_remind_me),
                        PendingIntent.getActivity(
                            context, requestCode(call.id, 3),
                            ReminderActivity.pickIntent(context, call.number, call.name, call.id),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
            }
            .build()

    private fun buildSummary(context: Context, calls: List<MissedCall>) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(ACCENT)
            .setContentTitle(context.resources.getQuantityString(R.plurals.missed_calls_count, calls.size, calls.size))
            .setContentText(calls.joinToString(", ") { it.title(context) })
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setSummaryText(context.getString(R.string.missed_calls))
                    .also { style -> calls.forEach { style.addLine(it.title(context)) } }
            )
            .setWhen(calls.maxOf { it.date })
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openRecentsIntent(context))
            .setDeleteIntent(dismissIntent(context, -1L))
            .build()

    private fun openRecentsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_MISSED_CALLS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionIntent(context: Context, action: String, call: MissedCall, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode(call.id, slot),
            Intent(context, MainActivity::class.java)
                .setAction(action)
                .setData(Uri.fromParts("tel", call.number, null))
                .putExtra(CallActionReceiver.EXTRA_MISSED_CALL_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun dismissIntent(context: Context, callId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode(callId, 0),
        Intent(context, CallActionReceiver::class.java)
            .setAction(CallActionReceiver.ACTION_MISSED_CALLS_DISMISSED)
            .putExtra(CallActionReceiver.EXTRA_MISSED_CALL_ID, callId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun requestCode(callId: Long, slot: Int): Int = (callId * 4 + slot).toInt()

    private fun queryNewMissedCalls(context: Context, limit: Int): List<MissedCall> = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val result = mutableListOf<MissedCall>()
            while (cursor.moveToNext() && result.size < limit) {
                val number = cursor.getString(1).orEmpty()
                val name = lookupContactName(context, number) ?: cursor.getString(2)?.takeIf { it.isNotBlank() }
                result += MissedCall(cursor.getLong(0), number, name, cursor.getLong(3))
            }
            result
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read missed calls", e)
        emptyList()
    }

    private fun lookupContactName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return try {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Lets Telecom restart its missed-call count, so the next one isn't reported as "2". */
    private fun resetTelecomCount(context: Context) {
        try {
            context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
        } catch (e: SecurityException) {
            Log.w(TAG, "Not the default dialer; Telecom keeps its missed-call count", e)
        }
    }

    private fun getManager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
