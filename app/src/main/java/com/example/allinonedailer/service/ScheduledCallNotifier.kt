package com.example.allinonedailer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.ui.reminders.ReminderActivity
import com.example.allinonedailer.ui.reminders.scheduledSummary

/**
 * Scheduled calls ("Call Mom at 6:00 PM"): the "Time to call Mom" notification
 * with Call, Snooze 10 min and Dismiss, plus an optional full-screen alert.
 * Fired by [CallReminderReceiver]; it shares its notification tag and ids, so
 * the reminder's Call button and [CallReminderReceiver.dismiss] work for both.
 */
object ScheduledCallNotifier {
    private const val TAG = "ScheduledCallNotifier"
    const val CHANNEL_ID = "scheduled_calls"
    private const val ACCENT = 0xFFD71921.toInt()

    /** Settings → Reminders → "Full-screen alert" (off by default), kept with the reminders. */
    private const val KEY_FULL_SCREEN = "scheduled_full_screen"

    fun fullScreen(context: Context): Boolean =
        context.getSharedPreferences(CallReminders.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FULL_SCREEN, false)

    fun setFullScreen(context: Context, on: Boolean) {
        context.getSharedPreferences(CallReminders.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FULL_SCREEN, on).apply()
    }

    /** Android 14+ lets the user turn full-screen notifications off per app. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun show(context: Context, reminder: CallReminder) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.scheduled_calls_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val id = CallReminderReceiver.notificationId(reminder.id)
        val tel = Uri.fromParts("tel", reminder.number, null)
        val alert = PendingIntent.getActivity(
            context, id,
            ReminderActivity.alertIntent(context, reminder),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_schedule)
            .setColor(ACCENT)
            .setContentTitle(context.getString(R.string.scheduled_call_title, reminder.title()))
            .setContentText(
                listOfNotNull(reminder.number.takeIf { reminder.name != null }, scheduledSummary(context, reminder))
                    .joinToString(" · ")
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(alert)
            .addAction(
                0, context.getString(R.string.call),
                PendingIntent.getActivity(
                    context, id,
                    Intent(context, ReminderActivity::class.java)
                        .setAction(ReminderActivity.ACTION_CALL)
                        .setData(tel)
                        .putExtra(CallReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0, context.getString(R.string.scheduled_call_snooze),
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, CallReminderReceiver::class.java)
                        .setAction(CallReminderReceiver.ACTION_SNOOZE)
                        .setData(tel)
                        .putExtra(CallReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
                        .putExtra(CallReminderReceiver.EXTRA_NAME, reminder.name)
                        .putExtra(CallReminderReceiver.EXTRA_SCHEDULED, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0, context.getString(R.string.scheduled_call_dismiss),
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, CallReminderReceiver::class.java)
                        .setAction(CallReminderReceiver.ACTION_DISMISS)
                        .setData(tel)
                        .putExtra(CallReminderReceiver.EXTRA_REMINDER_ID, reminder.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        // Full screen over the lock screen / an idle phone; a heads-up while the phone is in use.
        if (fullScreen(context) && canUseFullScreen(context)) builder.setFullScreenIntent(alert, true)
        try {
            manager.notify(CallReminderReceiver.NOTIFICATION_TAG, id, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing; scheduled call not shown", e)
        }
    }
}
