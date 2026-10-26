package com.example.allinonedailer

import android.provider.CallLog
import com.example.allinonedailer.data.CallFilter
import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.RecentsItem
import com.example.allinonedailer.data.groupCallLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class CallLogGroupsTest {

    /** Today's local midnight plus [minutes] (negative = yesterday evening). */
    private fun atMidnightPlus(minutes: Int, daysAgo: Int = 0): Date {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            add(Calendar.MINUTE, minutes)
        }
        return cal.time
    }

    private var nextId = 0
    private fun entry(
        number: String,
        date: Date,
        type: Int = CallLog.Calls.INCOMING_TYPE,
        name: String? = null
    ) = CallLogEntry(
        id = "${nextId++}", number = number, callerName = name,
        callType = type, date = date, durationSeconds = 30
    )

    /** "Day label" for headers and "count" for groups, to compare shapes. */
    private fun shape(items: List<RecentsItem>): List<String> = items.map {
        when (it) {
            is RecentsItem.Day -> it.label
            is RecentsItem.Calls -> "${it.group.latest.number} x${it.group.count}"
        }
    }

    @Test
    fun sameNumberAcrossMidnightSplitsIntoTwoDays() {
        val items = groupCallLogs(
            listOf(
                entry("9876543210", atMidnightPlus(5)),
                entry("9876543210", atMidnightPlus(-5))
            ),
            CallFilter.ALL, ""
        )
        assertEquals(listOf("Today", "9876543210 x1", "Yesterday", "9876543210 x1"), shape(items))
    }

    @Test
    fun runsOfOneNumberGroupWithinADay() {
        val items = groupCallLogs(
            listOf(
                entry("+91 98765 43210", atMidnightPlus(50)),
                entry("098765 43210", atMidnightPlus(40)),
                entry("9123456789", atMidnightPlus(30)),
                entry("9876543210", atMidnightPlus(20))
            ),
            CallFilter.ALL, ""
        )
        // Spellings of one number fold together; a different number in between breaks the run.
        assertEquals(
            listOf("Today", "+91 98765 43210 x2", "9123456789 x1", "9876543210 x1"),
            shape(items)
        )
    }

    @Test
    fun olderDaysGetDateLabelsAndYearOnlyWhenNeeded() {
        val lastYear = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.time
        val items = groupCallLogs(
            listOf(
                entry("121", atMidnightPlus(60, daysAgo = 3)),
                entry("121", lastYear)
            ),
            CallFilter.ALL, ""
        )
        val days = items.filterIsInstance<RecentsItem.Day>()
        assertEquals(2, days.size)
        val year = Calendar.getInstance().apply { time = lastYear }.get(Calendar.YEAR).toString()
        val threeDaysAgo = Calendar.getInstance().apply { time = atMidnightPlus(60, daysAgo = 3) }
        if (threeDaysAgo.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) {
            assertTrue(days[0].label, !days[0].label.contains(year) && days[0].label != "Yesterday")
        }
        assertTrue(days[1].label, days[1].label.endsWith(year))
        // Short codes group too, but not across days.
        assertEquals(2, items.count { it is RecentsItem.Calls })
    }

    @Test
    fun filterDropsCallsBeforeGrouping() {
        val items = groupCallLogs(
            listOf(
                entry("9876543210", atMidnightPlus(50), CallLog.Calls.MISSED_TYPE),
                entry("9876543210", atMidnightPlus(40), CallLog.Calls.OUTGOING_TYPE),
                entry("9876543210", atMidnightPlus(30), CallLog.Calls.MISSED_TYPE)
            ),
            CallFilter.MISSED, ""
        )
        // With the outgoing call filtered out, both missed calls form one run.
        assertEquals(listOf("Today", "9876543210 x2"), shape(items))
    }

    @Test
    fun blockedFilterCoversRejectedAndBlocked() {
        val items = groupCallLogs(
            listOf(
                entry("1400000001", atMidnightPlus(50), CallLog.Calls.REJECTED_TYPE),
                entry("1400000002", atMidnightPlus(40), CallLog.Calls.BLOCKED_TYPE),
                entry("1400000003", atMidnightPlus(30), CallLog.Calls.MISSED_TYPE)
            ),
            CallFilter.BLOCKED, ""
        )
        assertEquals(listOf("Today", "1400000001 x1", "1400000002 x1"), shape(items))
    }

    @Test
    fun searchByNameOrDigits() {
        val calls = listOf(
            entry("+91 98765 43210", atMidnightPlus(50), name = "Ravi Kumar"),
            entry("1800 123 4567", atMidnightPlus(40))
        )
        assertEquals(listOf("Today", "+91 98765 43210 x1"), shape(groupCallLogs(calls, CallFilter.ALL, "ravi")))
        assertEquals(listOf("Today", "1800 123 4567 x1"), shape(groupCallLogs(calls, CallFilter.ALL, "1800123")))
        assertEquals(emptyList<String>(), shape(groupCallLogs(calls, CallFilter.ALL, "nobody")))
    }

    @Test
    fun headerKeysAreUniquePerDay() {
        val items = groupCallLogs(
            listOf(
                entry("9876543210", atMidnightPlus(5)),
                entry("9123456789", atMidnightPlus(-5)),
                entry("9876543210", atMidnightPlus(-10))
            ),
            CallFilter.ALL, ""
        )
        val keys = items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
