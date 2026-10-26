package com.example.allinonedailer

import com.example.allinonedailer.data.insights.CallTypes
import com.example.allinonedailer.data.insights.InsightCall
import com.example.allinonedailer.data.insights.InsightRange
import com.example.allinonedailer.data.insights.InsightsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsAggregatorTest {

    private val india = ZoneId.of("Asia/Kolkata")
    private val labels = mapOf("1" to "Jio", "2" to "Airtel", "8991000" to "Jio")

    private fun at(text: String, zone: ZoneId = india) =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun call(
        number: String,
        type: Int,
        time: String,
        seconds: Long = 0,
        sim: String? = "1",
        name: String? = null
    ) = InsightCall(number, name, type, at(time), seconds, sim)

    private fun all() = InsightRange(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)

    @Test
    fun countsTypesAndTalkTime() {
        val calls = listOf(
            call("9876543210", CallTypes.INCOMING, "2026-10-20T10:00", 120),
            call("9876543210", CallTypes.OUTGOING, "2026-10-20T11:00", 60),
            call("9123456789", CallTypes.MISSED, "2026-10-20T12:00", 15), // ring time is not talk
            call("9123456789", CallTypes.REJECTED, "2026-10-20T12:30"),
            call("9123456789", CallTypes.BLOCKED, "2026-10-20T13:00")
        )
        val r = InsightsAggregator.aggregate(calls, all(), india, labels)
        assertEquals(5, r.totalCalls)
        assertEquals(1, r.incoming)
        assertEquals(1, r.outgoing)
        assertEquals(1, r.missed)
        assertEquals(180L, r.talkSeconds)
    }

    @Test
    fun splitsTalkTimePerSimAndMergesIdsWithTheSameName() {
        val calls = listOf(
            call("9876543210", CallTypes.OUTGOING, "2026-10-20T10:00", 100, sim = "1"),
            call("9876543211", CallTypes.INCOMING, "2026-10-20T10:10", 50, sim = "8991000"), // ICCID of the same Jio SIM
            call("9876543212", CallTypes.OUTGOING, "2026-10-20T10:20", 300, sim = "2"),
            call("9876543213", CallTypes.MISSED, "2026-10-20T10:30", sim = "2"),
            call("9876543214", CallTypes.INCOMING, "2026-10-20T10:40", 7, sim = null),
            call("9876543215", CallTypes.INCOMING, "2026-10-20T10:50", 3, sim = "E_unknown")
        )
        val r = InsightsAggregator.aggregate(calls, all(), india, labels, otherLabel = "Other")
        assertEquals(listOf("Airtel", "Jio", "Other"), r.perSim.map { it.label })
        val bySim = r.perSim.associateBy { it.label }
        assertEquals(300L, bySim.getValue("Airtel").talkSeconds)
        assertEquals(2, bySim.getValue("Airtel").calls)
        assertEquals(150L, bySim.getValue("Jio").talkSeconds)
        assertEquals(2, bySim.getValue("Jio").calls)
        assertEquals(10L, bySim.getValue("Other").talkSeconds)
        assertNull(bySim.getValue("Other").accountId)
        assertEquals(r.talkSeconds, r.perSim.sumOf { it.talkSeconds })
    }

    @Test
    fun missedRateIsMissedOverAllIncoming() {
        val calls = listOf(
            call("1", CallTypes.INCOMING, "2026-10-20T10:00", 10),
            call("2", CallTypes.INCOMING, "2026-10-20T10:00", 10),
            call("3", CallTypes.MISSED, "2026-10-20T10:00"),
            call("4", CallTypes.REJECTED, "2026-10-20T10:00"),
            call("5", CallTypes.OUTGOING, "2026-10-20T10:00", 10) // outgoing doesn't count
        )
        val r = InsightsAggregator.aggregate(calls, all(), india)
        assertEquals(0.25f, r.missedRate, 0.0001f)
    }

    @Test
    fun missedRateIsZeroWithoutIncomingCalls() {
        val r = InsightsAggregator.aggregate(listOf(call("1", CallTypes.OUTGOING, "2026-10-20T10:00", 5)), all(), india)
        assertEquals(0f, r.missedRate, 0f)
        val empty = InsightsAggregator.aggregate(emptyList(), all(), india)
        assertEquals(0, empty.totalCalls)
        assertNull(empty.busiestHour)
    }

    @Test
    fun weekStartsMondayMidnightAndIncludesAllOfToday() {
        // Wednesday 22 Oct 2026, 09:00 IST.
        val now = at("2026-10-22T09:00")
        val week = InsightRange.thisWeek(now, india)
        assertEquals(at("2026-10-19T00:00"), week.startMs)
        assertEquals(at("2026-10-23T00:00"), week.endMs)
        assertFalse(at("2026-10-18T23:59:59") in week) // Sunday night before
        assertTrue(at("2026-10-19T00:00") in week)
        assertTrue(at("2026-10-22T23:59:59") in week) // later today
    }

    @Test
    fun callsJustAroundMidnightLandOnTheRightDay() {
        val calls = listOf(
            call("1", CallTypes.INCOMING, "2026-10-21T23:59:30", 60), // starts before midnight
            call("2", CallTypes.INCOMING, "2026-10-22T00:00:00", 30),
            call("3", CallTypes.INCOMING, "2026-10-22T23:59:59", 5),
            call("4", CallTypes.INCOMING, "2026-10-23T00:00:00", 5)
        )
        val day = InsightRange.days(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22), india)
        val r = InsightsAggregator.aggregate(calls, day, india)
        assertEquals(2, r.totalCalls)
        assertEquals(35L, r.talkSeconds)
        assertEquals(1, r.callsByHour[0])
        assertEquals(1, r.callsByHour[23])
    }

    @Test
    fun monthRangeCrossesIntoTheNextMonthCorrectly() {
        val now = at("2026-10-31T22:00")
        val month = InsightRange.thisMonth(now, india)
        assertEquals(at("2026-10-01T00:00"), month.startMs)
        assertEquals(at("2026-11-01T00:00"), month.endMs)
        val calls = listOf(
            call("1", CallTypes.OUTGOING, "2026-09-30T23:59:59", 100),
            call("2", CallTypes.OUTGOING, "2026-10-01T00:00:00", 10),
            call("3", CallTypes.OUTGOING, "2026-10-31T23:59:59", 20),
            call("4", CallTypes.OUTGOING, "2026-11-01T00:00:00", 100)
        )
        assertEquals(30L, InsightsAggregator.aggregate(calls, month, india).talkSeconds)
    }

    @Test
    fun customRangeAcrossMonthsIncludesBothEndDaysAndAcceptsReversedDates() {
        val range = InsightRange.days(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 29), india)
        assertEquals(at("2026-09-29T00:00"), range.startMs)
        assertEquals(at("2026-10-03T00:00"), range.endMs)
    }

    @Test
    fun rangesUseRealMidnightAcrossDaylightSaving() {
        // London clocks went back on 25 Oct 2026: that day is 25 hours long.
        val london = ZoneId.of("Europe/London")
        val day = InsightRange.days(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), london)
        assertEquals(25 * 3_600_000L, day.endMs - day.startMs)
    }

    @Test
    fun hoursUseTheLocalZone() {
        // 18:30 UTC is midnight in India.
        val c = InsightCall("1", null, CallTypes.INCOMING, at("2026-10-20T18:30", ZoneId.of("UTC")), 5, "1")
        val r = InsightsAggregator.aggregate(listOf(c), all(), india)
        assertEquals(1, r.callsByHour[0])
        assertEquals(0, r.busiestHour)
    }

    @Test
    fun topContactsMergeSpellingsOfOneNumberAndRankBothWays() {
        val calls = listOf(
            call("+91 98765 43210", CallTypes.OUTGOING, "2026-10-20T10:00", 30, name = "Asha"),
            call("09876543210", CallTypes.INCOMING, "2026-10-19T10:00", 30),
            call("9876543210", CallTypes.MISSED, "2026-10-18T10:00"),
            call("9000000001", CallTypes.OUTGOING, "2026-10-20T11:00", 600, name = "Ravi"),
            call("121", CallTypes.OUTGOING, "2026-10-20T12:00", 0)
        )
        val r = InsightsAggregator.aggregate(calls, all(), india)
        assertEquals(listOf("Ravi", "Asha"), r.topByTalk.map { it.name }) // 121 has no talk time
        assertEquals("Asha", r.topByCount.first().name)
        assertEquals(3, r.topByCount.first().calls)
        assertEquals("9876543210", r.topByCount.first().key)
        assertEquals(60L, r.topByCount.first().talkSeconds)
    }

    @Test
    fun topListsAndLongestAreCapped() {
        val calls = (1..15).map { i -> call("90000000%02d".format(i), CallTypes.OUTGOING, "2026-10-20T10:00", i * 10L) }
        val r = InsightsAggregator.aggregate(calls, all(), india, topN = 10, longestN = 3)
        assertEquals(10, r.topByTalk.size)
        assertEquals(150L, r.topByTalk.first().talkSeconds)
        assertEquals(listOf(150L, 140L, 130L), r.longest.map { it.durationSeconds })
    }
}
