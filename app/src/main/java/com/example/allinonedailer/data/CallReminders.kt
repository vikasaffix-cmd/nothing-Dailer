package com.example.allinonedailer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.allinonedailer.service.CallReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Remind me to call back": one reminder per number and time. A scheduled call
 * ("Call Mom at 6:00 PM", from Contacts, Recents or the keypad) is the same
 * reminder with [kind] SCHEDULED; it can repeat daily or weekly at [minuteOfDay].
 */
data class CallReminder(
    val id: Long,
    val number: String,
    val name: String?,
    val at: Long,
    val kind: ReminderKind = ReminderKind.CALL_BACK,
    val repeat: ReminderRepeat = ReminderRepeat.ONCE,
    /** Local clock time (minutes after midnight) a repeating call keeps; -1 when it doesn't repeat. */
    val minuteOfDay: Int = -1
) {
    fun title(): String = name?.takeIf { it.isNotBlank() } ?: number
    val scheduled: Boolean get() = kind == ReminderKind.SCHEDULED
}

enum class ReminderKind { CALL_BACK, SCHEDULED }

enum class ReminderRepeat { ONCE, DAILY, WEEKLY }

/** The quick choices in the "Remind me" picker. */
enum class ReminderPreset { MINUTES_15, HOUR_1, TONIGHT, MORNING }

/** Pure reminder times, kept free of Android types so they run in JVM tests. */
object ReminderTimes {
    val TONIGHT: LocalTime = LocalTime.of(20, 0)
    val MORNING: LocalTime = LocalTime.of(9, 0)
    const val SNOOZE_MINUTES = 15L

    /** "Tonight" is offered until 19:45 and from 05:00; otherwise "tomorrow morning". */
    private val TONIGHT_CUTOFF: LocalTime = LocalTime.of(19, 45)
    private val NIGHT_ENDS: LocalTime = LocalTime.of(5, 0)

    /** 15 min, 1 h, then tonight or the next morning, whichever makes sense now. */
    fun presets(now: Long, zone: ZoneId): List<ReminderPreset> {
        val time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
        val evening = if (time >= NIGHT_ENDS && time < TONIGHT_CUTOFF) ReminderPreset.TONIGHT else ReminderPreset.MORNING
        return listOf(ReminderPreset.MINUTES_15, ReminderPreset.HOUR_1, evening)
    }

    fun at(preset: ReminderPreset, now: Long, zone: ZoneId): Long = when (preset) {
        ReminderPreset.MINUTES_15 -> now + 15 * 60_000L
        ReminderPreset.HOUR_1 -> now + 60 * 60_000L
        ReminderPreset.TONIGHT -> nextAt(TONIGHT, now, zone)
        ReminderPreset.MORNING -> nextAt(MORNING, now, zone)
    }

    /** The next time the clock shows [time], today or tomorrow. */
    fun nextAt(time: LocalTime, now: Long, zone: ZoneId): Long {
        val current = Instant.ofEpochMilli(now).atZone(zone)
        var target = current.toLocalDate().atTime(time).atZone(zone)
        if (!target.isAfter(current)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    fun snooze(now: Long): Long = now + SNOOZE_MINUTES * 60_000L

    /** Scheduled calls snooze for 10 minutes. */
    const val SCHEDULED_SNOOZE_MINUTES = 10L

    fun scheduledSnooze(now: Long): Long = now + SCHEDULED_SNOOZE_MINUTES * 60_000L

    /**
     * The first time a scheduled call fires when set for [date] at [time]: that
     * moment if still ahead; a repeating one in the past moves to its next day
     * (daily) or the same weekday next week (weekly). Null for a one-off in the past.
     */
    fun firstOccurrence(date: LocalDate, time: LocalTime, repeat: ReminderRepeat, now: Long, zone: ZoneId): Long? {
        val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (at > now) return at
        return when (repeat) {
            ReminderRepeat.ONCE -> null
            else -> nextRepeat(at, repeat, time.hour * 60 + time.minute, now, zone)
        }
    }

    /**
     * The next time a repeating call fires after it was due at [previous]:
     * the same local clock time ([minuteOfDay]) one day or one week later,
     * skipping any that are already past [now] (phone was off). Built from the
     * local date, so it stays at 18:00 across DST changes; a time that falls in
     * a spring-forward gap moves forward by the gap. Null for [ReminderRepeat.ONCE].
     */
    fun nextRepeat(previous: Long, repeat: ReminderRepeat, minuteOfDay: Int, now: Long, zone: ZoneId): Long? {
        val days = when (repeat) {
            ReminderRepeat.ONCE -> return null
            ReminderRepeat.DAILY -> 1L
            ReminderRepeat.WEEKLY -> 7L
        }
        val time = LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), (minuteOfDay % 60).coerceIn(0, 59))
        var date = Instant.ofEpochMilli(previous).atZone(zone).toLocalDate()
        while (true) {
            date = date.plusDays(days)
            val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (at > now) return at
        }
    }

    /**
     * When to set the alarm for a reminder after a reboot or time change: its
     * own time if still ahead, else straight away (a few seconds from now).
     */
    fun alarmTime(reminderAt: Long, now: Long): Long = if (reminderAt > now) reminderAt else now + 5_000L
}

/**
 * Stored reminders (SharedPreferences "call_reminders", one JSON array) plus
 * their alarms. A reminder is removed when it fires; Snooze adds a new one.
 */
object CallReminders {
    private const val TAG = "CallReminders"
    const val PREFS = "call_reminders"
    const val KEY_ITEMS = "items"

    private val _reminders = MutableStateFlow<List<CallReminder>>(emptyList())
    /** Upcoming reminders, soonest first. Filled by [load]. */
    val reminders: StateFlow<List<CallReminder>> = _reminders.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<CallReminder> {
        val list = try {
            val array = JSONArray(prefs(context).getString(KEY_ITEMS, "[]"))
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                CallReminder(
                    o.getLong("id"), o.getString("number"), o.optString("name").ifBlank { null }, o.getLong("at"),
                    kind = enumOr(o.optString("kind"), ReminderKind.CALL_BACK),
                    repeat = enumOr(o.optString("repeat"), ReminderRepeat.ONCE),
                    minuteOfDay = o.optInt("minute", -1)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read reminders", e)
            emptyList()
        }.sortedBy { it.at }
        _reminders.value = list
        return list
    }

    @Synchronized
    private fun save(context: Context, list: List<CallReminder>) {
        val array = JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("number", it.number).put("name", it.name ?: "").put("at", it.at)
            // Call-back reminders keep the original four fields, so older readers still parse them.
            if (it.scheduled) o.put("kind", it.kind.name).put("repeat", it.repeat.name).put("minute", it.minuteOfDay)
            array.put(o)
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()
        _reminders.value = list.sortedBy { it.at }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    fun get(context: Context, id: Long): CallReminder? = load(context).firstOrNull { it.id == id }

    /** Upcoming scheduled calls only (Settings → Reminders shows them in their own list). */
    fun scheduledCalls(list: List<CallReminder>): List<CallReminder> = list.filter { it.scheduled }

    /** Stores a scheduled call ("Call Mom at 6:00 PM") and sets its alarm. */
    fun addScheduled(context: Context, number: String, name: String?, at: Long, repeat: ReminderRepeat): CallReminder {
        val current = load(context)
        val id = maxOf(System.currentTimeMillis(), (current.maxOfOrNull { it.id } ?: 0L) + 1)
        val reminder = scheduledReminder(id, number, name, at, repeat)
        save(context, current + reminder)
        ReminderScheduler.schedule(context, reminder)
        return reminder
    }

    /** Changes a scheduled call's time and repeat; its id (and notification) stay the same. */
    fun updateScheduled(context: Context, id: Long, at: Long, repeat: ReminderRepeat) {
        val updated = load(context).map {
            if (it.id == id) scheduledReminder(id, it.number, it.name, at, repeat) else it
        }
        save(context, updated)
        updated.firstOrNull { it.id == id }?.let { ReminderScheduler.schedule(context, it) }
    }

    private fun scheduledReminder(id: Long, number: String, name: String?, at: Long, repeat: ReminderRepeat): CallReminder {
        val local = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return CallReminder(
            id, number, name?.takeIf { it.isNotBlank() }, at,
            kind = ReminderKind.SCHEDULED,
            repeat = repeat,
            minuteOfDay = if (repeat == ReminderRepeat.ONCE) -1 else local.hour * 60 + local.minute
        )
    }

    /**
     * Fired: a repeating scheduled call moves on to its next day or week (and
     * sets that alarm); anything else is forgotten.
     */
    fun fired(context: Context, reminder: CallReminder) {
        val next = if (reminder.scheduled) {
            ReminderTimes.nextRepeat(reminder.at, reminder.repeat, reminder.minuteOfDay, System.currentTimeMillis(), ZoneId.systemDefault())
        } else null
        if (next == null) {
            remove(context, reminder.id)
        } else {
            reschedule(context, reminder.id, next)
        }
    }

    /** Stores a reminder and sets its alarm. */
    fun add(context: Context, number: String, name: String?, at: Long): CallReminder {
        val current = load(context)
        val id = maxOf(System.currentTimeMillis(), (current.maxOfOrNull { it.id } ?: 0L) + 1)
        val reminder = CallReminder(id, number, name?.takeIf { it.isNotBlank() }, at)
        save(context, current + reminder)
        ReminderScheduler.schedule(context, reminder)
        return reminder
    }

    /** One-tap "remind me" from a ringing call: a reminder at [preset] from now (default 1 hour). */
    fun remindLater(
        context: Context,
        number: String,
        name: String?,
        preset: ReminderPreset = ReminderPreset.HOUR_1
    ): CallReminder = add(context, number, name, ReminderTimes.at(preset, System.currentTimeMillis(), ZoneId.systemDefault()))

    fun reschedule(context: Context, id: Long, at: Long) {
        val updated = load(context).map { if (it.id == id) it.copy(at = at) else it }
        save(context, updated)
        updated.firstOrNull { it.id == id }?.let { ReminderScheduler.schedule(context, it) }
    }

    fun cancel(context: Context, id: Long) {
        ReminderScheduler.cancel(context, id)
        save(context, load(context).filterNot { it.id == id })
    }

    /** Fired: forget it (the notification carries what Snooze needs). */
    fun remove(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })

    /** After a reboot, update or time change: set every alarm again; overdue ones fire now. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        load(context).forEach { ReminderScheduler.schedule(context, it, ReminderTimes.alarmTime(it.at, now)) }
    }
}

/**
 * Exact alarms when the user allows them (Android 12+ "Alarms & reminders";
 * on 14+ it's off by default for new installs). Otherwise an inexact
 * while-idle alarm, which can arrive a few minutes late.
 */
object ReminderScheduler {
    private const val TAG = "ReminderScheduler"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, reminder: CallReminder, at: Long = reminder.at) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = firePendingIntent(context, reminder.id)
        try {
            if (canScheduleExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused; using an inexact one", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(context, id))
    }

    private fun firePendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, CallReminderReceiver::class.java)
            .setAction(CallReminderReceiver.ACTION_FIRE)
            .putExtra(CallReminderReceiver.EXTRA_REMINDER_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
