package com.example.allinonedailer.data.insights

import com.example.allinonedailer.data.PhoneNumbers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Call insights, kept free of Android types so it runs in JVM tests.
 * Call types are CallLog.Calls' values, copied here for the same reason.
 */
object CallTypes {
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val VOICEMAIL = 4
    const val REJECTED = 5
    const val BLOCKED = 6
    const val ANSWERED_EXTERNALLY = 7
}

/** One call-log row, only the columns insights need. */
data class InsightCall(
    val number: String,
    val name: String?,
    val type: Int,
    val dateMs: Long,
    val durationSeconds: Long,
    /** CallLog.Calls.PHONE_ACCOUNT_ID: which SIM; null on old rows. */
    val accountId: String?
)

/** [startMs] inclusive, [endMs] exclusive. */
data class InsightRange(val startMs: Long, val endMs: Long) {
    operator fun contains(ms: Long) = ms in startMs until endMs

    companion object {
        /** Monday 00:00 of this week up to tomorrow 00:00 (the rest of today included). */
        fun thisWeek(nowMs: Long, zone: ZoneId, firstDay: DayOfWeek = DayOfWeek.MONDAY): InsightRange {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val start = today.with(TemporalAdjusters.previousOrSame(firstDay))
            return days(start, today, zone)
        }

        /** The 1st of this month 00:00 up to tomorrow 00:00. */
        fun thisMonth(nowMs: Long, zone: ZoneId): InsightRange {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return days(today.withDayOfMonth(1), today, zone)
        }

        /** Whole days [first]..[last], both included, in [zone] (DST-safe: uses each day's real midnight). */
        fun days(first: LocalDate, last: LocalDate, zone: ZoneId): InsightRange {
            val (a, b) = if (last.isBefore(first)) last to first else first to last
            return InsightRange(
                a.atStartOfDay(zone).toInstant().toEpochMilli(),
                b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
    }
}

data class SimStat(val accountId: String?, val label: String, val talkSeconds: Long, val calls: Int)

data class ContactStat(
    /** PhoneNumbers.key: what Recents' search matches on. */
    val key: String,
    val name: String?,
    val number: String,
    val talkSeconds: Long,
    val calls: Int
)

data class Insights(
    val totalCalls: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    /** Answered incoming + outgoing seconds. */
    val talkSeconds: Long,
    /** Missed ÷ (answered incoming + missed + rejected), 0..1; 0 with no incoming calls. */
    val missedRate: Float,
    /** Most talk time first. */
    val perSim: List<SimStat>,
    val topByTalk: List<ContactStat>,
    val topByCount: List<ContactStat>,
    /** Calls started in each hour of the day, 0..23. */
    val callsByHour: IntArray,
    /** Longest first. */
    val longest: List<InsightCall>
) {
    val busiestHour: Int? get() = callsByHour.indices.maxByOrNull { callsByHour[it] }?.takeIf { callsByHour[it] > 0 }

    // IntArray has identity equals; compare by content so tests can use assertEquals.
    override fun equals(other: Any?) = other is Insights && toString() == other.toString()
    override fun hashCode() = toString().hashCode()
    override fun toString() = "Insights($totalCalls,$incoming,$outgoing,$missed,$talkSeconds,$missedRate,$perSim," +
        "$topByTalk,$topByCount,${callsByHour.contentToString()},$longest)"
}

object InsightsAggregator {

    /**
     * Folds [calls] inside [range] into [Insights]. [simLabels] maps a
     * PHONE_ACCOUNT_ID to a name ("Jio"); ids that map to the same name are
     * one SIM. Unmapped ids and rows without one go under [otherLabel].
     * Pure and linear; ~1,000 rows take well under a millisecond.
     */
    fun aggregate(
        calls: List<InsightCall>,
        range: InsightRange,
        zone: ZoneId,
        simLabels: Map<String, String> = emptyMap(),
        otherLabel: String = "Other",
        topN: Int = 10,
        longestN: Int = 5
    ): Insights {
        var incoming = 0
        var outgoing = 0
        var missed = 0
        var rejected = 0
        var talk = 0L
        var total = 0
        val byHour = IntArray(24)
        val sims = LinkedHashMap<String, MutableSimStat>()
        val contacts = HashMap<String, MutableContactStat>()
        val talked = ArrayList<InsightCall>()

        for (c in calls) {
            if (c.dateMs !in range) continue
            total++
            when (c.type) {
                CallTypes.INCOMING, CallTypes.ANSWERED_EXTERNALLY -> incoming++
                CallTypes.OUTGOING -> outgoing++
                CallTypes.MISSED -> missed++
                CallTypes.REJECTED -> rejected++
            }
            val seconds = talkSeconds(c)
            talk += seconds
            if (seconds > 0) talked.add(c)

            byHour[Instant.ofEpochMilli(c.dateMs).atZone(zone).hour]++

            val label = c.accountId?.let { simLabels[it] } ?: otherLabel
            val sim = sims.getOrPut(label) { MutableSimStat(c.accountId.takeIf { label != otherLabel }, label) }
            sim.calls++
            sim.talk += seconds

            val key = PhoneNumbers.key(c.number).ifEmpty { c.number }
            val contact = contacts.getOrPut(key) { MutableContactStat(key, c.number) }
            contact.calls++
            contact.talk += seconds
            // Newest name wins only if none yet; the caller passes newest first.
            if (contact.name == null && !c.name.isNullOrBlank()) contact.name = c.name
        }

        val incomingAll = incoming + missed + rejected
        val stats = contacts.values.map { ContactStat(it.key, it.name, it.number, it.talk, it.calls) }
        return Insights(
            totalCalls = total,
            incoming = incoming,
            outgoing = outgoing,
            missed = missed,
            talkSeconds = talk,
            missedRate = if (incomingAll == 0) 0f else missed.toFloat() / incomingAll,
            perSim = sims.values
                .map { SimStat(it.accountId, it.label, it.talk, it.calls) }
                .sortedWith(compareByDescending<SimStat> { it.talkSeconds }.thenByDescending { it.calls }),
            topByTalk = stats.filter { it.talkSeconds > 0 }
                .sortedWith(compareByDescending<ContactStat> { it.talkSeconds }.thenByDescending { it.calls }.thenBy { it.key })
                .take(topN),
            topByCount = stats
                .sortedWith(compareByDescending<ContactStat> { it.calls }.thenByDescending { it.talkSeconds }.thenBy { it.key })
                .take(topN),
            callsByHour = byHour,
            longest = talked.sortedByDescending { it.durationSeconds }.take(longestN)
        )
    }

    /** Only answered calls count as talk; missed/rejected rows sometimes carry ring time. */
    fun talkSeconds(c: InsightCall): Long = when (c.type) {
        CallTypes.INCOMING, CallTypes.OUTGOING, CallTypes.ANSWERED_EXTERNALLY -> c.durationSeconds.coerceAtLeast(0)
        else -> 0
    }

    private class MutableSimStat(val accountId: String?, val label: String) {
        var talk = 0L
        var calls = 0
    }

    private class MutableContactStat(val key: String, val number: String) {
        var name: String? = null
        var talk = 0L
        var calls = 0
    }
}
