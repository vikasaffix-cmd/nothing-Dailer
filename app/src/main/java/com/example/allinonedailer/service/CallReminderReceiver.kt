package com.example.allinonedailer.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.data.ReminderRepeat
import com.example.allinonedailer.data.ReminderTimes
import com.example.allinonedailer.ui.MainActivity
import com.example.allinonedailer.ui.reminders.ReminderActivity

/**
 * Call-back reminders: fires the "Call back <name>" notification and handles
 * Snooze. Not exported; system broadcasts go to [CallReminderBootReceiver].
 */
class CallReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReminderReceiver"
        const val ACTION_FIRE = "com.example.allinonedailer.action.REMINDER_FIRE"
        const val ACTION_SNOOZE = "com.example.allinonedailer.action.REMINDER_SNOOZE"
        /** Scheduled calls: Dismiss just clears the notification (a repeating one stays set). */
        const val ACTION_DISMISS = "com.example.allinonedailer.action.REMINDER_DISMISS"
        /** Set on a scheduled call's Snooze: it snoozes 10 min and stays a scheduled call. */
        const val EXTRA_SCHEDULED = "com.example.allinonedailer.extra.REMINDER_SCHEDULED"
        const val EXTRA_REMINDER_ID = "com.example.allinonedailer.extra.REMINDER_ID"
        const val EXTRA_NAME = "com.example.allinonedailer.extra.REMINDER_NAME"

        const val CHANNEL_ID = "call_reminders"
        const val NOTIFICATION_TAG = "call_reminder"
        private const val ACCENT = 0xFFD71921.toInt()

        fun notificationId(reminderId: Long): Int = reminderId.toInt()

        fun show(context: Context, reminder: CallReminder) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val id = notificationId(reminder.id)
            val time = DateFormat.getTimeFormat(context).format(reminder.at)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setColor(ACCENT)
                .setContentTitle(context.getString(R.string.reminder_call_back_title, reminder.title()))
                .setContentText(
                    if (reminder.name != null) context.getString(R.string.reminder_body_with_number, reminder.number, time)
                    else context.getString(R.string.reminder_body, time)
                )
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, id,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(
                    0, context.getString(R.string.call),
                    PendingIntent.getActivity(
                        context, id,
                        Intent(context, ReminderActivity::class.java)
                            .setAction(ReminderActivity.ACTION_CALL)
                            .setData(Uri.fromParts("tel", reminder.number, null))
                            .putExtra(EXTRA_REMINDER_ID, reminder.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(
                    0, context.getString(R.string.reminder_snooze),
                    PendingIntent.getBroadcast(
                        context, id,
                        Intent(context, CallReminderReceiver::class.java)
                            .setAction(ACTION_SNOOZE)
                            .setData(Uri.fromParts("tel", reminder.number, null))
                            .putExtra(EXTRA_REMINDER_ID, reminder.id)
                            .putExtra(EXTRA_NAME, reminder.name),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            try {
                manager.notify(NOTIFICATION_TAG, id, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing; reminder not shown", e)
            }
        }

        fun dismiss(context: Context, reminderId: Long) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_TAG, notificationId(reminderId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ACTION_FIRE -> {
                        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
                        val reminder = CallReminders.get(app, id)
                        if (reminder != null) {
                            if (reminder.scheduled) ScheduledCallNotifier.show(app, reminder) else show(app, reminder)
                            CallReminders.fired(app, reminder)
                        }
                    }
                    ACTION_SNOOZE -> {
                        val number = intent.data?.schemeSpecificPart
                        dismiss(app, intent.getLongExtra(EXTRA_REMINDER_ID, -1L))
                        val name = intent.getStringExtra(EXTRA_NAME)
                        val now = System.currentTimeMillis()
                        if (number.isNullOrBlank()) {
                            // Nothing to call back.
                        } else if (intent.getBooleanExtra(EXTRA_SCHEDULED, false)) {
                            CallReminders.addScheduled(app, number, name, ReminderTimes.scheduledSnooze(now), ReminderRepeat.ONCE)
                        } else {
                            CallReminders.add(app, number, name, ReminderTimes.snooze(now))
                        }
                    }
                    ACTION_DISMISS -> dismiss(app, intent.getLongExtra(EXTRA_REMINDER_ID, -1L))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reminder action ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Sets every reminder alarm again after a reboot, an app update, a clock or
 * time-zone change, or when exact alarms are allowed (alarms don't survive
 * those). Exported for the system broadcasts; only these actions do anything.
 */
class CallReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Unit
            else -> return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                CallReminders.rescheduleAll(app)
            } catch (e: Exception) {
                Log.w("CallReminderBoot", "Could not re-set reminders", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
