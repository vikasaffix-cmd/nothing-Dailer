package com.example.allinonedailer.ui.reminders

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.allinonedailer.data.AppLanguageSetting
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.data.ReminderRepeat
import com.example.allinonedailer.data.ReminderTimes
import com.example.allinonedailer.service.CallActionReceiver
import com.example.allinonedailer.service.CallReminderReceiver
import com.example.allinonedailer.service.MissedCallNotifier
import com.example.allinonedailer.ui.theme.AllinoneDailerTheme

/**
 * Invisible activity behind reminder notification buttons (notifications may
 * only start activities, not receiver trampolines):
 * - [ACTION_CALL]: the reminder's Call button; places it through [CallPlacer].
 * - [ACTION_PICK]: "Remind me" on a missed-call notification; shows the picker.
 * - [ACTION_ALERT]: a scheduled call's "Time to call" page, also over the lock
 *   screen; its Call button asks to unlock first.
 */
class ReminderActivity : ComponentActivity() {

    companion object {
        const val ACTION_CALL = "com.example.allinonedailer.action.REMINDER_CALL"
        const val ACTION_PICK = "com.example.allinonedailer.action.REMINDER_PICK"
        const val EXTRA_NAME = "com.example.allinonedailer.extra.REMINDER_PICK_NAME"
        /** A scheduled call's alert page (notification tap or full-screen intent). */
        const val ACTION_ALERT = "com.example.allinonedailer.action.REMINDER_ALERT"
        const val EXTRA_AT = "com.example.allinonedailer.extra.REMINDER_AT"

        fun alertIntent(context: Context, reminder: CallReminder): Intent =
            Intent(context, ReminderActivity::class.java)
                .setAction(ACTION_ALERT)
                .setData(Uri.fromParts("tel", reminder.number, null))
                .putExtra(CallReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
                .putExtra(EXTRA_NAME, reminder.name)
                .putExtra(EXTRA_AT, reminder.at)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the picker for [number]; [missedCallId] marks that missed call read once a time is chosen. */
        fun pickIntent(context: Context, number: String, name: String?, missedCallId: Long = -1L): Intent =
            Intent(context, ReminderActivity::class.java)
                .setAction(ACTION_PICK)
                .setData(Uri.fromParts("tel", number, null))
                .putExtra(EXTRA_NAME, name)
                .putExtra(CallActionReceiver.EXTRA_MISSED_CALL_ID, missedCallId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageSetting.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
        if (number.isNullOrBlank()) return close()

        when (intent.action) {
            ACTION_CALL -> {
                CallReminderReceiver.dismiss(this, intent.getLongExtra(CallReminderReceiver.EXTRA_REMINDER_ID, -1L))
                // Skip on recreation so a rotation can't dial twice.
                if (savedInstanceState == null) CallPlacer.placeCall(this, number)
                close()
            }
            ACTION_PICK -> {
                val name = intent.getStringExtra(EXTRA_NAME)
                val missedCallId = intent.getLongExtra(CallActionReceiver.EXTRA_MISSED_CALL_ID, -1L)
                setContent {
                    AllinoneDailerTheme {
                        ReminderPickerDialog(
                            title = name ?: number,
                            subtitle = if (name != null) number else null,
                            onDismiss = { close() },
                            onPick = { at ->
                                scheduleReminder(this, number, name, at)
                                if (missedCallId >= 0) {
                                    val app = applicationContext
                                    Thread { MissedCallNotifier.markRead(app, missedCallId) }.start()
                                }
                                close()
                            }
                        )
                    }
                }
            }
            ACTION_ALERT -> {
                showOverLockScreen()
                val id = intent.getLongExtra(CallReminderReceiver.EXTRA_REMINDER_ID, -1L)
                val name = intent.getStringExtra(EXTRA_NAME)
                val at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis())
                setContent {
                    AllinoneDailerTheme {
                        ScheduledCallAlert(
                            title = name ?: number,
                            subtitle = if (name != null) number else null,
                            at = at,
                            onCall = {
                                CallReminderReceiver.dismiss(this, id)
                                callAfterUnlock(number)
                            },
                            onSnooze = {
                                CallReminderReceiver.dismiss(this, id)
                                val app = applicationContext
                                val snoozeAt = ReminderTimes.scheduledSnooze(System.currentTimeMillis())
                                Thread { CallReminders.addScheduled(app, number, name, snoozeAt, ReminderRepeat.ONCE) }.start()
                                toastScheduledCall(this, snoozeAt)
                                close()
                            },
                            onDismiss = {
                                CallReminderReceiver.dismiss(this, id)
                                close()
                            }
                        )
                    }
                }
            }
            else -> close()
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    /** Places the call once the phone is unlocked, so a locked phone can't call anyone by itself. */
    private fun callAfterUnlock(number: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    CallPlacer.placeCall(this@ReminderActivity, number)
                    close()
                }
            })
        } else {
            CallPlacer.placeCall(this, number)
            close()
        }
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
