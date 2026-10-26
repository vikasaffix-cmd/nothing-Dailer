package com.example.allinonedailer.data

import android.provider.CallLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.annotation.StringRes
import com.example.allinonedailer.R

/** Recents filter chips. */
enum class CallFilter(@StringRes val label: Int, @StringRes val emptyLabel: Int) {
    ALL(R.string.filter_all, R.string.recents_empty),
    MISSED(R.string.filter_missed, R.string.recents_empty_missed),
    INCOMING(R.string.filter_incoming, R.string.recents_empty_incoming),
    OUTGOING(R.string.filter_outgoing, R.string.recents_empty_outgoing),
    BLOCKED(R.string.filter_blocked, R.string.recents_empty_blocked);

    fun matches(entry: CallLogEntry): Boolean = when (this) {
        ALL -> true
        MISSED -> entry.callType == CallLog.Calls.MISSED_TYPE
        INCOMING -> entry.callType == CallLog.Calls.INCOMING_TYPE ||
            entry.callType == CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
        OUTGOING -> entry.callType == CallLog.Calls.OUTGOING_TYPE
        BLOCKED -> entry.callType == CallLog.Calls.REJECTED_TYPE ||
            entry.callType == CallLog.Calls.BLOCKED_TYPE
    }
}

/** Consecutive calls with the same number, newest first. */
data class CallGroup(val calls: List<CallLogEntry>) {
    val latest: CallLogEntry get() = calls.first()
    val key: String get() = latest.id
    val count: Int get() = calls.size
    val hasRecording: Boolean get() = calls.any { it.recordingPath != null }
    val isSpam: Boolean get() = calls.any { it.isSpam }
}

/** A row in Recents: a day header or a group of calls. */
sealed interface RecentsItem {
    val key: String

    data class Day(val label: String, override val key: String) : RecentsItem
    data class Calls(val group: CallGroup) : RecentsItem {
        override val key: String get() = group.key
    }
}

private fun numberKey(number: String): String =
    number.filter(Char::isDigit).takeLast(10).ifEmpty { number }

/** yyyyDDD in local time, to tell calendar days apart. */
private fun dayKey(cal: Calendar, millis: Long): Int {
    cal.timeInMillis = millis
    return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
}

/**
 * Filters [entries] (already newest first) by [filter] and [query], folds runs
 * of the same number on the same day into one group, and puts a header before
 * each day ("Today", "Yesterday", "Thu, 23 Oct"). Call off the main thread.
 */
fun groupCallLogs(
    entries: List<CallLogEntry>,
    filter: CallFilter,
    query: String,
    /** Call note text by call id; search matches it too. */
    notes: Map<String, String> = emptyMap(),
    todayLabel: String = "Today",
    yesterdayLabel: String = "Yesterday"
): List<RecentsItem> {
    val q = query.trim()
    val qDigits = q.filter(Char::isDigit)
    val cal = Calendar.getInstance()
    val now = System.currentTimeMillis()
    val today = dayKey(cal, now)
    val yesterday = dayKey(cal, now - 24 * 60 * 60 * 1000L)
    val thisYear = today / 1000
    val dayFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
    val dayYearFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())

    val items = mutableListOf<RecentsItem>()
    var run = mutableListOf<CallLogEntry>()
    var runKey: String? = null
    var runDay = -1

    for (entry in entries) {
        if (!filter.matches(entry)) continue
        if (q.isNotEmpty()) {
            val byName = entry.callerName?.contains(q, ignoreCase = true) == true
            val byNumber = qDigits.isNotEmpty() && entry.number.filter(Char::isDigit).contains(qDigits)
            val byNote = notes[entry.id]?.contains(q, ignoreCase = true) == true
            if (!byName && !byNumber && !byNote) continue
        }
        val key = numberKey(entry.number)
        val day = dayKey(cal, entry.date.time)
        if ((key != runKey || day != runDay) && run.isNotEmpty()) {
            items.add(RecentsItem.Calls(CallGroup(run)))
            run = mutableListOf()
        }
        if (day != runDay) {
            val label = when {
                day == today -> todayLabel
                day == yesterday -> yesterdayLabel
                day / 1000 == thisYear -> dayFormat.format(entry.date)
                else -> dayYearFormat.format(entry.date)
            }
            items.add(RecentsItem.Day(label, "d:$day"))
        }
        runKey = key
        runDay = day
        run.add(entry)
    }
    if (run.isNotEmpty()) items.add(RecentsItem.Calls(CallGroup(run)))
    return items
}

/** "2m 14s", "45s", "1h 3m"; the formats come from string resources in the UI. */
fun formatCallDuration(
    seconds: Long,
    hoursMinutes: String = "%1\$dh %2\$dm",
    minutesSeconds: String = "%1\$dm %2\$ds",
    secondsOnly: String = "%1\$ds"
): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> hoursMinutes.format(h, m)
        m > 0 -> minutesSeconds.format(m, s)
        else -> secondsOnly.format(s)
    }
}

/** Missed, rejected and blocked calls never connected, so they have no duration. */
fun CallLogEntry.showsDuration(): Boolean =
    callType == CallLog.Calls.INCOMING_TYPE ||
        callType == CallLog.Calls.OUTGOING_TYPE ||
        callType == CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
