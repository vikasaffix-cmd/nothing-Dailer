package com.example.allinonedailer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallNotesRepository
import com.example.allinonedailer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap

/** A connected call that just ended, offered for a note. */
data class EndedCall(
    val number: String,
    val name: String?,
    /** Call creation time, which Telecom also writes as the call log's DATE. */
    val startTime: Long,
    val endTime: Long
) {
    val notificationId: Int get() = (endTime / 1000).toInt()
}

/**
 * After a connected call ends: a small "Add a note" card on the keypad for a
 * few seconds, and a quiet notification with an inline reply that also works
 * from the lock screen. Neither blocks going back to the keypad.
 */
object CallNotePrompt {

    private const val TAG = "CallNotePrompt"
    private const val CHANNEL_ID = "call_notes"
    const val ACTION_SAVE_NOTE = "com.example.allinonedailer.action.SAVE_CALL_NOTE"
    const val KEY_NOTE_TEXT = "call_note_text"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_START = "start"
    const val EXTRA_END = "end"
    /** How long the card stays on the keypad. */
    const val CARD_MS = 6_000L
    /** The notification goes away on its own after this. */
    private const val NOTIFICATION_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Calls seen in STATE_ACTIVE; Telecom calls back on the main thread. */
    private val connected: MutableSet<Call> = Collections.newSetFromMap(WeakHashMap())

    private val _pending = MutableStateFlow<EndedCall?>(null)
    /** The call the keypad card is offering a note for, or null. */
    val pending: StateFlow<EndedCall?> = _pending.asStateFlow()

    /** Called from CallService on every state change, before the call is dropped. */
    fun onCallStateChanged(context: Context, call: Call, state: Int, name: String?) {
        if (state == Call.STATE_ACTIVE) connected += call
        if (state != Call.STATE_DISCONNECTED) return
        val wasConnected = connected.remove(call) || call.details.connectTimeMillis > 0
        // Conference legs and the conference itself aren't one person's call.
        if (!wasConnected || call.parent != null ||
            call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
        ) return
        val number = call.details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        val ended = EndedCall(
            number = number,
            name = name,
            startTime = call.details.creationTimeMillis,
            endTime = System.currentTimeMillis()
        )
        _pending.value = ended
        showNotification(context.applicationContext, ended)
    }

    /** The card timed out or was closed. */
    fun dismissCard(call: EndedCall) {
        if (_pending.value == call) _pending.value = null
    }

    /** Saves [text] for [call] off the main thread and clears both prompts. */
    fun save(context: Context, call: EndedCall, text: String) {
        dismissCard(call)
        cancelNotification(context, call)
        if (text.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            CallNotesRepository.saveForEndedCall(app, call.number, call.startTime, call.endTime, text)
        }
    }

    fun cancelNotification(context: Context, call: EndedCall) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG, call.notificationId)
    }

    private fun showNotification(context: Context, call: EndedCall) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Default importance so it shows on the lock screen, but silent and no heads-up.
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.call_note_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        val reply = Intent(context, CallNoteReceiver::class.java)
            .setAction(ACTION_SAVE_NOTE)
            .putExtra(EXTRA_NUMBER, call.number)
            .putExtra(EXTRA_START, call.startTime)
            .putExtra(EXTRA_END, call.endTime)
        // RemoteInput needs a mutable PendingIntent to receive the typed text.
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val replyIntent = PendingIntent.getBroadcast(context, call.notificationId, reply, replyFlags)
        val input = RemoteInput.Builder(KEY_NOTE_TEXT)
            .setLabel(context.getString(R.string.call_note_hint))
            .build()
        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground,
            context.getString(R.string.call_note_add_action),
            replyIntent
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val who = call.name ?: call.number
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(0xFFD71921.toInt())
            .setContentTitle(context.getString(R.string.call_note_prompt_title))
            .setContentText(context.getString(R.string.call_note_prompt_body, who))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter(NOTIFICATION_MS)
            .setContentIntent(open)
            .addAction(action)
            .build()
        try {
            manager.notify(TAG, call.notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission; only the keypad card will show", e)
        }
    }
}

/** Inline reply from the "Add a note" notification. */
class CallNoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CallNotePrompt.ACTION_SAVE_NOTE) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CallNotePrompt.KEY_NOTE_TEXT)?.toString().orEmpty()
        val call = EndedCall(
            number = intent.getStringExtra(CallNotePrompt.EXTRA_NUMBER) ?: return,
            name = null,
            startTime = intent.getLongExtra(CallNotePrompt.EXTRA_START, 0L),
            endTime = intent.getLongExtra(CallNotePrompt.EXTRA_END, 0L)
        )
        CallNotePrompt.cancelNotification(context, call)
        if (text.isBlank()) return
        CallNotePrompt.pending.value?.takeIf { it.endTime == call.endTime }?.let(CallNotePrompt::dismissCard)
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallNotesRepository.saveForEndedCall(app, call.number, call.startTime, call.endTime, text)
            } finally {
                result.finish()
            }
        }
    }
}
