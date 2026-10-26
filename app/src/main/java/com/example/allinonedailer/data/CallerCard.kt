package com.example.allinonedailer.data

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

enum class LastCallKind { INCOMING, OUTGOING, MISSED, REJECTED }
enum class CardDay { TODAY, YESTERDAY, EARLIER }
enum class ContactEventKind { BIRTHDAY, ANNIVERSARY }

data class LastCallInfo(val day: CardDay, val date: Long, val durationSeconds: Long, val kind: LastCallKind)

/** What the incoming-call screen shows about a caller under their name. */
data class CallerCard(
    val note: String? = null,
    /** The soonest pending call-back reminder for this number. */
    val reminderAt: Long? = null,
    val lastCall: LastCallInfo? = null,
    val missedThisWeek: Int = 0,
    /** Birthday / anniversary falling on today. */
    val eventsToday: List<ContactEventKind> = emptyList()
) {
    val isEmpty: Boolean
        get() = note == null && reminderAt == null && lastCall == null && missedThisWeek == 0 && eventsToday.isEmpty()
}

/** Builds the card from what the repositories returned (pure; unit-tested on the JVM). */
object CallerCardBuilder {

    const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * [calls] are call-log rows (any number; only [number]'s are used), [events]
     * the contact's Event rows as (kind, START_DATE). Returns null when there's
     * nothing to show.
     */
    fun build(
        number: String,
        note: CallNote?,
        reminders: List<CallReminder>,
        calls: List<CallLogEntry>,
        events: List<Pair<ContactEventKind, String>>,
        now: Long,
        zone: ZoneId
    ): CallerCard? {
        val key = CallNoteMatcher.numberKey(number)
        val mine = calls.filter { CallNoteMatcher.numberKey(it.number) == key }.sortedByDescending { it.date.time }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val lastCall = mine.firstOrNull()?.let { entry ->
            val kind = when (entry.callType) {
                CallLog.Calls.OUTGOING_TYPE -> LastCallKind.OUTGOING
                CallLog.Calls.MISSED_TYPE -> LastCallKind.MISSED
                CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> LastCallKind.REJECTED
                else -> LastCallKind.INCOMING
            }
            val day = Instant.ofEpochMilli(entry.date.time).atZone(zone).toLocalDate()
            LastCallInfo(
                day = when (day) {
                    today -> CardDay.TODAY
                    today.minusDays(1) -> CardDay.YESTERDAY
                    else -> CardDay.EARLIER
                },
                date = entry.date.time,
                durationSeconds = if (kind == LastCallKind.INCOMING || kind == LastCallKind.OUTGOING) entry.durationSeconds else 0,
                kind = kind
            )
        }
        val missed = mine.count { it.callType == CallLog.Calls.MISSED_TYPE && it.date.time >= now - WEEK_MS }
        val reminder = reminders.filter { CallNoteMatcher.numberKey(it.number) == key }.minByOrNull { it.at }
        val todayMd = MonthDay.from(today)
        val eventsToday = events.filter { (_, date) -> parseMonthDay(date) == todayMd }.map { it.first }.distinct()

        return CallerCard(
            note = note?.firstLine?.takeIf { it.isNotBlank() },
            reminderAt = reminder?.at,
            lastCall = lastCall,
            missedThisWeek = missed,
            eventsToday = eventsToday
        ).takeUnless { it.isEmpty }
    }

    /**
     * The month and day of a contact Event START_DATE: "1990-04-12", "--04-12"
     * (no year), "19900412", or with a time after it. Null if unreadable.
     */
    fun parseMonthDay(date: String): MonthDay? {
        val s = date.trim()
        val match = Regex("""^(?:\d{4}|-)-?(\d{2})-?(\d{2})""").find(s) ?: return null
        return try {
            MonthDay.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Loads [CallerCard]s off the main thread. Each source runs on its own, and
 * whatever hasn't answered within [TIMEOUT_MS] is left out, so a slow query
 * never holds the card (and never the ringing UI, which doesn't wait for it).
 * The screen and the pill share one load per number.
 */
object CallerCardLoader {
    private const val TAG = "CallerCard"
    private const val TIMEOUT_MS = 1200L
    private const val CACHE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Pair<Long, Deferred<CallerCard?>>>()

    suspend fun load(context: Context, number: String): CallerCard? {
        if (number.none(Char::isDigit)) return null
        val app = context.applicationContext
        val key = CallNoteMatcher.numberKey(number)
        val now = System.currentTimeMillis()
        val job = cache[key]?.takeIf { now - it.first < CACHE_MS }?.second
            ?: scope.async { compute(app, number) }.also { cache[key] = now to it }
        return withTimeoutOrNull(TIMEOUT_MS + 300) { job.await() }
    }

    private suspend fun compute(context: Context, number: String): CallerCard? {
        val note = scope.async { CallNotesRepository.lastNoteFor(context, number) }
        val reminders = scope.async { CallReminders.load(context) }
        val calls = scope.async { queryCalls(context, number) }
        val events = scope.async { queryEvents(context, number) }
        suspend fun <T> Deferred<T>.within(): T? = withTimeoutOrNull(TIMEOUT_MS) { await() }
        return try {
            CallerCardBuilder.build(
                number = number,
                note = note.within(),
                reminders = reminders.within().orEmpty(),
                calls = calls.within().orEmpty(),
                events = events.within().orEmpty(),
                now = System.currentTimeMillis(),
                zone = ZoneId.systemDefault()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't build the caller card", e)
            null
        }
    }

    /** This number's calls, newest first (matched on the last digits, then exactly by the builder). */
    private fun queryCalls(context: Context, number: String): List<CallLogEntry> {
        val tail = number.filter(Char::isDigit).takeLast(7)
        val list = mutableListOf<CallLogEntry>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%$tail"),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && list.size < 200) {
                    list += CallLogEntry(
                        id = c.getString(0),
                        number = c.getString(1) ?: "",
                        callerName = null,
                        callType = c.getInt(2),
                        date = Date(c.getLong(3)),
                        durationSeconds = c.getLong(4)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the call log", e)
        }
        return list
    }

    /** Birthday and anniversary rows of the contact with this number. */
    private fun queryEvents(context: Context, number: String): List<Pair<ContactEventKind, String>> {
        val out = mutableListOf<Pair<ContactEventKind, String>>()
        try {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val contactId = context.contentResolver.query(
                lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return out
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val kind = when (c.getInt(0)) {
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> ContactEventKind.BIRTHDAY
                        ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> ContactEventKind.ANNIVERSARY
                        else -> null
                    } ?: continue
                    c.getString(1)?.let { out += kind to it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read contact events", e)
        }
        return out
    }
}
