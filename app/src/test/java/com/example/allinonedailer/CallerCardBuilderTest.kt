package com.example.allinonedailer

import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallNote
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallerCardBuilder
import com.example.allinonedailer.data.CardDay
import com.example.allinonedailer.data.ContactEventKind
import com.example.allinonedailer.data.LastCallKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class CallerCardBuilderTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 26, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val number = "+91 98765 43210"

    private val INCOMING = 1
    private val OUTGOING = 2
    private val MISSED = 3
    private val REJECTED = 5

    private fun call(type: Int, at: Long, seconds: Long = 0, num: String = "9876543210") =
        CallLogEntry(id = at.toString(), number = num, callerName = null, callType = type, date = Date(at), durationSeconds = seconds)

    private fun build(
        note: CallNote? = null,
        reminders: List<CallReminder> = emptyList(),
        calls: List<CallLogEntry> = emptyList(),
        events: List<Pair<ContactEventKind, String>> = emptyList()
    ) = CallerCardBuilder.build(number, note, reminders, calls, events, now, zone)

    @Test
    fun nothingKnownGivesNoCard() {
        assertNull(build())
        // Other people's calls and reminders don't count.
        assertNull(build(calls = listOf(call(MISSED, now - hour, num = "9000000000")),
            reminders = listOf(CallReminder(1, "9000000000", null, now + hour))))
    }

    @Test
    fun lastCallYesterdayOutgoing() {
        val card = build(calls = listOf(call(OUTGOING, now - day, 240), call(INCOMING, now - 3 * day, 60)))!!
        val last = card.lastCall!!
        assertEquals(CardDay.YESTERDAY, last.day)
        assertEquals(LastCallKind.OUTGOING, last.kind)
        assertEquals(240L, last.durationSeconds)
    }

    @Test
    fun lastCallTodayAndEarlier() {
        assertEquals(CardDay.TODAY, build(calls = listOf(call(INCOMING, now - hour, 30)))!!.lastCall!!.day)
        assertEquals(CardDay.EARLIER, build(calls = listOf(call(INCOMING, now - 5 * day, 30)))!!.lastCall!!.day)
    }

    @Test
    fun missedAndRejectedHaveNoDuration() {
        assertEquals(0L, build(calls = listOf(call(MISSED, now - hour, 12)))!!.lastCall!!.durationSeconds)
        assertEquals(LastCallKind.REJECTED, build(calls = listOf(call(REJECTED, now - hour)))!!.lastCall!!.kind)
    }

    @Test
    fun countsMissedCallsInTheLastSevenDaysOnly() {
        val card = build(calls = listOf(
            call(MISSED, now - hour), call(MISSED, now - 2 * day), call(MISSED, now - 6 * day),
            call(MISSED, now - 8 * day), call(INCOMING, now - 3 * day, 60)
        ))!!
        assertEquals(3, card.missedThisWeek)
    }

    @Test
    fun soonestReminderForThisNumber() {
        val card = build(reminders = listOf(
            CallReminder(1, "+919876543210", null, now + 3 * hour),
            CallReminder(2, "09876543210", null, now + hour),
            CallReminder(3, "9111111111", null, now + 10)
        ))!!
        assertEquals(now + hour, card.reminderAt)
    }

    @Test
    fun noteUsesItsFirstLine() {
        val note = CallNote("k", "1", "9876543210", now - day, now - day, "\n  Send the invoice \nthen call", now)
        assertEquals("Send the invoice", build(note = note)!!.note)
        assertNull(build(note = note.copy(text = "   ")))
    }

    @Test
    fun birthdayAndAnniversaryToday() {
        val card = build(events = listOf(
            ContactEventKind.BIRTHDAY to "1990-10-26",
            ContactEventKind.ANNIVERSARY to "--10-26",
            ContactEventKind.ANNIVERSARY to "2015-10-27"
        ))!!
        assertEquals(listOf(ContactEventKind.BIRTHDAY, ContactEventKind.ANNIVERSARY), card.eventsToday)
        assertNull(build(events = listOf(ContactEventKind.BIRTHDAY to "1990-10-25")))
    }

    @Test
    fun parsesContactEventDates() {
        val md = MonthDay.of(10, 26)
        assertEquals(md, CallerCardBuilder.parseMonthDay("1990-10-26"))
        assertEquals(md, CallerCardBuilder.parseMonthDay("--10-26"))
        assertEquals(md, CallerCardBuilder.parseMonthDay("19901026"))
        assertEquals(md, CallerCardBuilder.parseMonthDay("1990-10-26T00:00:00.000Z"))
        assertNull(CallerCardBuilder.parseMonthDay("26/10/1990"))
        assertNull(CallerCardBuilder.parseMonthDay("1990-13-40"))
    }

    @Test
    fun everythingTogether() {
        val card = build(
            note = CallNote("k", "1", number, now - day, now - day, "Owes me lunch", now),
            reminders = listOf(CallReminder(1, number, "Asha", now + hour)),
            calls = listOf(call(OUTGOING, now - day, 240), call(MISSED, now - 2 * day)),
            events = listOf(ContactEventKind.BIRTHDAY to "--10-26")
        )!!
        assertEquals("Owes me lunch", card.note)
        assertEquals(1, card.missedThisWeek)
        assertEquals(LastCallKind.OUTGOING, card.lastCall!!.kind)
        assertEquals(listOf(ContactEventKind.BIRTHDAY), card.eventsToday)
    }
}
