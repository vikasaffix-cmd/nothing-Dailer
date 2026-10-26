package com.example.allinonedailer

import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallNote
import com.example.allinonedailer.data.CallNoteMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CallNoteMatcherTest {

    private val t0 = 1_793_000_000_000L // 26 Oct 2026

    private fun call(id: String, number: String, start: Long, seconds: Long = 60) = CallLogEntry(
        id = id,
        number = number,
        callerName = null,
        callType = 2,
        date = Date(start),
        durationSeconds = seconds
    )

    private fun note(
        number: String,
        start: Long,
        end: Long,
        callId: String? = null,
        text: String = "note"
    ) = CallNote(
        key = "k-$number-$start-$callId",
        callId = callId,
        number = number,
        startTime = start,
        endTime = end,
        text = text,
        editedAt = end
    )

    @Test
    fun numberKeyIgnoresCountryCodeAndFormatting() {
        assertEquals("9876543210", CallNoteMatcher.numberKey("+91 98765 43210"))
        assertEquals("9876543210", CallNoteMatcher.numberKey("09876543210"))
        assertEquals("Private", CallNoteMatcher.numberKey(" Private "))
    }

    @Test
    fun findsTheRowForTheCallThatJustEnded() {
        val entries = listOf(
            call("3", "+919876543210", t0 + 120_000), // a later call
            call("2", "9876543210", t0 + 400, seconds = 90), // this call (DATE ≈ creation time)
            call("1", "9876543210", t0 - 3_600_000) // an hour earlier
        )
        val match = CallNoteMatcher.findCall(entries, "+91 98765 43210", startTime = t0, endTime = t0 + 95_000)
        assertEquals("2", match?.id)
    }

    @Test
    fun ignoresOtherNumbersAtTheSameTime() {
        val entries = listOf(call("1", "9123456789", t0), call("2", "9876543210", t0 + 50))
        assertEquals("2", CallNoteMatcher.findCall(entries, "9876543210", t0, t0 + 30_000)?.id)
    }

    @Test
    fun noMatchBeforeTheRowIsWritten() {
        val entries = listOf(call("1", "9876543210", t0 - 3_600_000))
        assertNull(CallNoteMatcher.findCall(entries, "9876543210", t0, t0 + 30_000))
    }

    @Test
    fun withoutStartTimeUsesEndOfCall() {
        val entries = listOf(
            call("2", "9876543210", t0 + 600_000, seconds = 30),
            call("1", "9876543210", t0, seconds = 120)
        )
        assertEquals("1", CallNoteMatcher.findCall(entries, "9876543210", 0, t0 + 121_000)?.id)
    }

    @Test
    fun byEntryIdPrefersCallIdThenFallsBackToTime() {
        val entries = listOf(
            call("10", "9876543210", t0 + 5_000),
            call("11", "9123456789", t0 + 60_000),
            call("12", "9000000000", t0 + 120_000)
        )
        val tied = note("9876543210", t0 + 5_000, t0 + 30_000, callId = "10", text = "tied")
        val pending = note("9123456789", t0 + 59_000, t0 + 90_000, text = "pending")
        // Row renumbered (e.g. call log restored): same number and start time.
        val renumbered = note("9000000000", t0 + 120_000, 0, callId = "99", text = "moved")
        val map = CallNoteMatcher.byEntryId(entries, listOf(tied, pending, renumbered))
        assertEquals("tied", map["10"]?.text)
        assertEquals("pending", map["11"]?.text)
        assertEquals("moved", map["12"]?.text)
    }

    @Test
    fun aRowGetsOnlyOneNote() {
        val entries = listOf(call("10", "9876543210", t0))
        val tied = note("9876543210", t0, t0 + 10_000, callId = "10", text = "tied")
        val pending = note("9876543210", t0, t0 + 10_000, text = "pending")
        val map = CallNoteMatcher.byEntryId(entries, listOf(tied, pending))
        assertEquals(1, map.size)
        assertEquals("tied", map["10"]?.text)
    }

    @Test
    fun lastForPicksTheNewestNoteForTheNumber() {
        val notes = listOf(
            note("9876543210", t0, t0 + 10_000, text = "old"),
            note("+919876543210", t0 + 86_400_000, t0 + 86_500_000, text = "new"),
            note("9123456789", t0 + 90_000_000, t0 + 90_100_000, text = "other")
        )
        assertEquals("new", CallNoteMatcher.lastFor(notes, "09876543210")?.text)
        assertNull(CallNoteMatcher.lastFor(notes, "9000000000"))
    }

    @Test
    fun firstLineSkipsBlankLines() {
        val n = note("1", t0, t0, text = "\n  Call back Monday \nabout invoice")
        assertTrue(n.firstLine == "Call back Monday")
    }
}
