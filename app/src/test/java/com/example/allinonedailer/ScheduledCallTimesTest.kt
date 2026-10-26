package com.example.allinonedailer

import com.example.allinonedailer.data.ReminderRepeat
import com.example.allinonedailer.data.ReminderTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduledCallTimesTest {

    private val india = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")

    private fun t(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun local(at: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(at).atZone(zone)

    private val six = 18 * 60

    @Test
    fun onceNeverRepeats() {
        val at = t(india, 2026, 10, 26, 18)
        assertNull(ReminderTimes.nextRepeat(at, ReminderRepeat.ONCE, six, at, india))
    }

    @Test
    fun dailyNextDay() {
        val at = t(india, 2026, 10, 26, 18)
        assertEquals(t(india, 2026, 10, 27, 18), ReminderTimes.nextRepeat(at, ReminderRepeat.DAILY, six, at, india))
    }

    @Test
    fun dailyAcrossMonthAndYearEnds() {
        assertEquals(
            t(india, 2026, 11, 1, 18),
            ReminderTimes.nextRepeat(t(india, 2026, 10, 31, 18), ReminderRepeat.DAILY, six, t(india, 2026, 10, 31, 18), india)
        )
        assertEquals(
            t(india, 2027, 3, 1, 18),
            ReminderTimes.nextRepeat(t(india, 2027, 2, 28, 18), ReminderRepeat.DAILY, six, t(india, 2027, 2, 28, 18), india)
        )
        assertEquals(
            t(india, 2027, 1, 1, 18),
            ReminderTimes.nextRepeat(t(india, 2026, 12, 31, 18), ReminderRepeat.DAILY, six, t(india, 2026, 12, 31, 18), india)
        )
    }

    @Test
    fun weeklyAcrossMonthEndAndLeapDay() {
        // Friday 30 Oct -> Friday 6 Nov.
        assertEquals(
            t(india, 2026, 11, 6, 18),
            ReminderTimes.nextRepeat(t(india, 2026, 10, 30, 18), ReminderRepeat.WEEKLY, six, t(india, 2026, 10, 30, 18), india)
        )
        // 2028 is a leap year: Sat 26 Feb -> Sat 4 Mar (29 Feb in between).
        assertEquals(
            t(india, 2028, 3, 4, 18),
            ReminderTimes.nextRepeat(t(india, 2028, 2, 26, 18), ReminderRepeat.WEEKLY, six, t(india, 2028, 2, 26, 18), india)
        )
    }

    @Test
    fun dailyKeepsClockTimeWhenClocksGoBack() {
        // UK clocks go back on Sunday 25 Oct 2026: still 18:00, but 25 hours later.
        val before = t(london, 2026, 10, 24, 18)
        val next = ReminderTimes.nextRepeat(before, ReminderRepeat.DAILY, six, before, london)!!
        assertEquals(LocalDateTime.of(2026, 10, 25, 18, 0), local(next, london).toLocalDateTime())
        assertEquals(25 * 3_600_000L, next - before)
    }

    @Test
    fun dailyKeepsClockTimeWhenClocksGoForward() {
        // UK clocks go forward on Sunday 29 Mar 2026: 18:00 again, 23 hours later.
        val before = t(london, 2026, 3, 28, 18)
        val next = ReminderTimes.nextRepeat(before, ReminderRepeat.DAILY, six, before, london)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 18, 0), local(next, london).toLocalDateTime())
        assertEquals(23 * 3_600_000L, next - before)
    }

    @Test
    fun timeInTheSpringGapMovesForwardThenComesBack() {
        // 01:30 doesn't exist in London on 29 Mar 2026; it fires at 02:30 BST, and 01:30 the day after.
        val minute = 90
        val before = t(london, 2026, 3, 28, 1, 30)
        val gap = ReminderTimes.nextRepeat(before, ReminderRepeat.DAILY, minute, before, london)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), local(gap, london).toLocalDateTime())
        val after = ReminderTimes.nextRepeat(gap, ReminderRepeat.DAILY, minute, gap, london)!!
        assertEquals(LocalDateTime.of(2026, 3, 30, 1, 30), local(after, london).toLocalDateTime())
    }

    @Test
    fun weeklyAcrossUsDstEnd() {
        // US clocks go back on Sunday 1 Nov 2026: Saturday 09:00 stays 09:00 the next Saturday.
        val before = t(newYork, 2026, 10, 31, 9)
        val next = ReminderTimes.nextRepeat(before, ReminderRepeat.WEEKLY, 9 * 60, before, newYork)!!
        assertEquals(LocalDateTime.of(2026, 11, 7, 9, 0), local(next, newYork).toLocalDateTime())
        assertEquals(7 * 24 * 3_600_000L + 3_600_000L, next - before)
    }

    @Test
    fun missedDaysAreSkipped() {
        // Phone off for three days: the next one is still ahead, at the same clock time.
        val due = t(india, 2026, 10, 20, 18)
        val now = t(india, 2026, 10, 23, 19)
        assertEquals(t(india, 2026, 10, 24, 18), ReminderTimes.nextRepeat(due, ReminderRepeat.DAILY, six, now, india))
        assertEquals(t(india, 2026, 10, 27, 18), ReminderTimes.nextRepeat(due, ReminderRepeat.WEEKLY, six, now, india))
    }

    @Test
    fun firstOccurrence() {
        val now = t(india, 2026, 10, 26, 19)
        val today = LocalDate.of(2026, 10, 26)
        val sixPm = LocalTime.of(18, 0)
        // Ahead: as set.
        assertEquals(t(india, 2026, 10, 26, 20), ReminderTimes.firstOccurrence(today, LocalTime.of(20, 0), ReminderRepeat.ONCE, now, india))
        assertEquals(t(india, 2026, 10, 27, 18), ReminderTimes.firstOccurrence(today.plusDays(1), sixPm, ReminderRepeat.ONCE, now, india))
        // Already past today: a one-off is refused, repeats start at their next turn.
        assertNull(ReminderTimes.firstOccurrence(today, sixPm, ReminderRepeat.ONCE, now, india))
        assertEquals(t(india, 2026, 10, 27, 18), ReminderTimes.firstOccurrence(today, sixPm, ReminderRepeat.DAILY, now, india))
        assertEquals(t(india, 2026, 11, 2, 18), ReminderTimes.firstOccurrence(today, sixPm, ReminderRepeat.WEEKLY, now, india))
    }

    @Test
    fun scheduledSnoozeIsTenMinutes() {
        val now = t(india, 2026, 10, 26, 18)
        assertEquals(t(india, 2026, 10, 26, 18, 10), ReminderTimes.scheduledSnooze(now))
    }
}
