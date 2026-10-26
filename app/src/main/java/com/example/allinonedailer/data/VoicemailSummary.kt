package com.example.allinonedailer.data

import java.util.Calendar
import java.util.TimeZone

/** What the caller asked for. */
enum class VoicemailAction { CALL_BACK, MESSAGE }

/** A date and/or time the caller mentioned, as a calendar start. */
data class ReminderTime(val startMillis: Long, val allDay: Boolean)

/** The pieces of a one-line voicemail summary, in the language spoken. */
data class VoicemailSummary(
    /** "en", "hi" or "mr". */
    val language: String,
    val who: String?,
    val what: String?,
    /** The date/time words as spoken, e.g. "tomorrow at 5 pm" or "उद्या 5 वाजता". */
    val whenText: String?,
    /** e.g. "₹500" or "₹5 हजार". */
    val amount: String?,
    val action: VoicemailAction?,
    val reminder: ReminderTime?,
    /** e.g. "Ravi: delivery, 5 pm, call back". */
    val line: String
)

/**
 * Rule-based, on-device summary of a voicemail transcript in English, Hindi
 * or Marathi: who called, what about, any time or amount, and whether they
 * want a call back. No network and no model: plain pattern matching, so it
 * is basic but private.
 */
object VoicemailSummarizer {

    fun summarize(
        transcript: String,
        language: String?,
        knownName: String?,
        receivedAt: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): VoicemailSummary? {
        val text = normalize(transcript).trim()
        if (text.isEmpty()) return null
        val lang = language?.take(2)?.lowercase()?.takeIf { it in LANGUAGES } ?: detectLanguage(text)

        val name = findName(text)
        val who = knownName?.takeIf { it.isNotBlank() } ?: name?.value
        val action = findAction(text)
        val amount = findAmount(text)
        val time = findWhen(text, receivedAt, timeZone)
        val what = findTopic(text) ?: firstWords(text, name?.range, listOfNotNull(action?.second, amount?.second, time?.range))

        val parts = listOfNotNull(what, time?.text, amount?.first, action?.let { actionLabel(it.first, lang) })
        val body = parts.joinToString(", ").ifEmpty { text.split(SPACES).take(8).joinToString(" ") + "…" }
        return VoicemailSummary(
            language = lang,
            who = who,
            what = what,
            whenText = time?.text,
            amount = amount?.first,
            action = action?.first,
            reminder = time?.reminder,
            line = if (who != null) "$who: $body" else body
        )
    }

    // ---- Language ----------------------------------------------------------

    private val LANGUAGES = setOf("en", "hi", "mr")

    private val MARATHI_MARKERS = setOf(
        "आहे", "आहेत", "नाही", "मी", "तुम्ही", "तुम्हाला", "करा", "उद्या", "वाजता", "आणि", "आम्ही",
        "माझे", "माझं", "माझा", "माझी", "होते", "होता", "आपण", "काय", "पाहिजे", "झाले", "झालं", "साठी", "परवा"
    )
    private val HINDI_MARKERS = setOf(
        "है", "हैं", "नहीं", "मैं", "आप", "आपको", "करें", "कीजिए", "कल", "बजे", "और", "हम",
        "मेरा", "मेरी", "था", "थी", "क्या", "चाहिए", "गया", "लिए", "का", "की", "को", "परसों"
    )

    /** "en" for mostly Latin text, else "mr" or "hi" by common words. */
    fun detectLanguage(text: String): String {
        val letters = text.count { it.isLetter() }
        val devanagari = text.count { it in 'ऀ'..'ॿ' && it.isLetter() }
        if (letters == 0 || devanagari * 3 < letters) return "en"
        val words = WORD.findAll(text).map { it.value }.toList()
        val mr = words.count { it in MARATHI_MARKERS }
        val hi = words.count { it in HINDI_MARKERS }
        return if (mr > hi) "mr" else "hi"
    }

    /** Lower case, Devanagari digits as 0-9, precomposed nukta letters split (फ़ → फ + ़). */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s.lowercase()) {
            when (c) {
                in '०'..'९' -> append('0' + (c - '०'))
                in 'क़'..'य़' -> {
                    append(NUKTA_BASE[c - 'क़'])
                    append('़')
                }
                else -> append(c)
            }
        }
    }

    private val NUKTA_BASE = charArrayOf('क', 'ख', 'ग', 'ज', 'ड', 'ढ', 'फ', 'य')

    private val WORD = Regex("""[\p{L}\p{M}\p{Nd}']+""")
    private val SPACES = Regex("""\s+""")

    /** A Devanagari-safe whole word ([\b] doesn't understand vowel signs). */
    private fun w(pattern: String, suffix: String = "") =
        """(?<![\p{L}\p{M}])(?:$pattern)$suffix(?![\p{L}\p{M}])"""

    // ---- Who ---------------------------------------------------------------

    private class Found(val value: String, val range: IntRange)

    private val NAME_STOPWORDS = setOf(
        "calling", "regarding", "about", "just", "going", "from", "the", "a", "an", "your", "to", "sir", "madam",
        "not", "in", "on", "very", "really", "here", "back", "available", "sorry", "trying", "call", "reminding",
        "following", "with", "also", "actually", "speaking", "ma'am", "me", "you", "i", "we", "so", "and", "only",
        "मैं", "हम", "आप", "जी", "से", "मी", "आम्ही", "तुम्ही", "सर", "मॅडम", "मैडम"
    )

    private val NAME_PATTERNS = listOf(
        Regex("""\b(?:this is|it's|it is|my name is|i am|i'm)\s+([a-z][a-z']+)"""),
        Regex("""^(?:(?:hi|hello|hey)[,!.]?\s+)?([a-z][a-z']+)\s+here\b"""),
        Regex("""मेरा\s+नाम\s+(\S+)"""),
        Regex("""मैं\s+(\S+)\s+(?:बोल|बात)"""),
        Regex("""मैं\s+(\S+)\s+से\s+(?:बोल|बात)"""),
        Regex("""(?:माझं|माझे|माझा)\s+नाव\s+(\S+)"""),
        Regex("""मी\s+(\S+)\s+(?:बोल|कडून|मधून)"""),
        Regex("""(\S+)\s+(?:बोल\s+(?:रहा|रही)|बोलतो|बोलते)""")
    )

    private fun findName(text: String): Found? {
        for (p in NAME_PATTERNS) {
            for (m in p.findAll(text)) {
                val g = m.groups[1] ?: continue
                val raw = g.value.trim(',', '.', '!', '?', '।', '\'')
                if (raw.length < 2 || raw in NAME_STOPWORDS) continue
                return Found(raw.replaceFirstChar { it.titlecase() }, m.range)
            }
        }
        return null
    }

    // ---- Action ------------------------------------------------------------

    private val CALL_BACK = listOf(
        Regex("""\b(?:call\s+(?:me\s+|us\s+)?back|callback|call\s+me|call\s+us|give\s+(?:me\s+|us\s+)?a\s+call|ring\s+me|please\s+call|return\s+(?:my|the|this)\s+call)\b"""),
        Regex(w("""(?:कॉल|फ़?ोन)\s*(?:बैक|करें|करना|कीजिए|करो|कर\s*(?:दो|दीजिए|देना|लेना|लीजिए)|करा|करावा|करावं|करशील|करू\s*शकता|बॅक)""")),
        Regex(w("""(?:वापस|परत)\s*(?:कॉल|फ़?ोन)"""))
    )
    private val MESSAGE = listOf(
        Regex("""\b(?:(?:text|message|whatsapp|sms)\s+me|send\s+(?:me\s+)?(?:a\s+)?(?:message|text|sms|whatsapp)|drop\s+(?:me\s+)?a\s+(?:message|text))\b"""),
        Regex(w("""(?:मैसेज|मेसेज|मॅसेज|व्हाट्सएप|वॉट्सऐप|व्हॉट्सॲप|व्हाट्सअप|एसएमएस)\s*(?:करें|करना|करो|कर\s*(?:दो|दीजिए|देना)|भेज\S*|करा|पाठवा)"""))
    )

    private fun findAction(text: String): Pair<VoicemailAction, IntRange>? {
        CALL_BACK.firstNotNullOfOrNull { it.find(text) }?.let { return VoicemailAction.CALL_BACK to it.range }
        MESSAGE.firstNotNullOfOrNull { it.find(text) }?.let { return VoicemailAction.MESSAGE to it.range }
        return null
    }

    fun actionLabel(action: VoicemailAction, language: String): String = when (action) {
        VoicemailAction.CALL_BACK -> when (language) {
            "hi" -> "कॉल बैक करें"
            "mr" -> "परत फोन करा"
            else -> "call back"
        }
        VoicemailAction.MESSAGE -> when (language) {
            "hi" -> "मैसेज करें"
            "mr" -> "मेसेज करा"
            else -> "message back"
        }
    }

    // ---- Amount ------------------------------------------------------------

    private const val NUMBER = """(\d[\d,]*(?:\.\d+)?)"""
    private const val MULTIPLIER = """(thousand|lakhs?|crores?|हजार|हज़ार|लाख|करोड़?|कोटी)"""
    private const val CURRENCY = """(?:rupees?|rs\.?|inr|रुपये|रुपए|रुपया|रुपयांचा|रुपयांचे|रुपयां|रु\.?|₹)"""

    private val AMOUNT_PATTERNS = listOf(
        Regex("""₹\s?$NUMBER(?:\s*$MULTIPLIER)?"""),
        Regex("""\b(?:rs\.?|inr)\s?$NUMBER(?:\s*$MULTIPLIER)?"""),
        Regex("""$NUMBER\s*(?:$MULTIPLIER\s*)?$CURRENCY(?![\p{L}\p{M}])"""),
        Regex("""$NUMBER\s*$MULTIPLIER(?![\p{L}\p{M}])""")
    )

    private fun findAmount(text: String): Pair<String, IntRange>? {
        for (p in AMOUNT_PATTERNS) {
            val m = p.find(text) ?: continue
            val number = m.groupValues[1].trimEnd(',', '.')
            if (number.isEmpty()) continue
            val multiplier = m.groupValues.getOrNull(2).orEmpty()
            return (if (multiplier.isEmpty()) "₹$number" else "₹$number $multiplier") to m.range
        }
        return null
    }

    // ---- When --------------------------------------------------------------

    private class When(val text: String, val range: IntRange, val reminder: ReminderTime)

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "छे" to 6,
        "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12,
        "दोन" to 2, "पाच" to 5, "सहा" to 6, "नऊ" to 9, "दहा" to 10, "अकरा" to 11, "बारा" to 12
    )
    private val NUM = "(\\d{1,2}|" + NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"

    /** Minutes added to the hour that follows ("साढ़े पाँच" = 5:30, "पौने छह" = 5:45). */
    private val FRACTIONS = mapOf(
        "साढ़े" to 30, "साढे" to 30, "साडे" to 30, "सवा" to 15, "सव्वा" to 15, "पौने" to -15, "पावणे" to -15
    )
    private val FRACTION = "(" + FRACTIONS.keys.joinToString("|") + ")"

    /** Whole half-hours: "डेढ़ बजे" = 1:30, "अडीच वाजता" = 2:30. */
    private val HALVES = mapOf("डेढ़" to 90, "डेढ" to 90, "ढाई" to 150, "दीड" to 90, "अडीच" to 150)

    private const val AMPM = """(a\.?\s?m\.?|p\.?\s?m\.?)"""
    private const val INDIC_MARKER = """(?:बजे|बजकर|वाजता|वाजे|वाजून)"""

    private val TIME_PATTERNS = listOf(
        Regex("""\b(\d{1,2})[:.](\d{2})\s*$AMPM?(?![\d])"""),
        Regex("""\b$NUM\s*$AMPM(?![a-z])"""),
        Regex("""\b$NUM\s*o'?\s?clock\b"""),
        Regex("""(?:$FRACTION\s+)?$NUM\s*(?::(\d{2}))?\s*$INDIC_MARKER(?:\s*(\d{1,2})\s*(?:मिनट|मिनिट\S*))?"""),
        Regex("(" + HALVES.keys.joinToString("|") + ")\\s*$INDIC_MARKER"),
        Regex("""\bat\s+(\d{1,2})\b(?![:.\d%])(?!\s*(?:rupees|rs|minutes|mins|days|percent))""")
    )

    private enum class Period(val defaultHour: Int) { MORNING(9), AFTERNOON(14), EVENING(18), NIGHT(20) }

    private val PERIODS = listOf(
        Regex("""\b(?:morning)\b|${w("सुबह|सकाळी|सकाळ")}""") to Period.MORNING,
        Regex("""\b(?:afternoon|noon)\b|${w("दोपहर|दुपारी|दुपार")}""") to Period.AFTERNOON,
        Regex("""\b(?:evening)\b|${w("शाम|संध्याकाळी|संध्याकाळ")}""") to Period.EVENING,
        Regex("""\b(?:tonight|night)\b|${w("रात|रात्री|रात्र")}""") to Period.NIGHT
    )

    private val WEEKDAYS = listOf(
        Calendar.MONDAY to "monday|सोमवार",
        Calendar.TUESDAY to "tuesday|मंगलवार|मंगळवार",
        Calendar.WEDNESDAY to "wednesday|बुधवार",
        Calendar.THURSDAY to "thursday|गुरुवार|बृहस्पतिवार",
        Calendar.FRIDAY to "friday|शुक्रवार",
        Calendar.SATURDAY to "saturday|शनिवार",
        Calendar.SUNDAY to "sunday|रविवार"
    )

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""

    private val RELATIVE_DAYS = listOf(
        Regex("""\bday after tomorrow\b|${w("परसों|परवा", "(?:\\S{0,2})")}""") to 2,
        Regex("""\btomorrow\b|${w("कल")}|${w("उद्या", "(?:ला|च|पर्यंत)?")}""") to 1,
        Regex("""\b(?:today|tonight|this (?:morning|afternoon|evening))\b|${w("आज", "(?:च)?")}""") to 0
    )

    private class Day(val range: IntRange, val set: (Calendar) -> Unit)

    private fun findDay(text: String): Day? {
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$MONTH\b""").find(text)?.let { m ->
            return explicitDate(m.range, m.groupValues[1].toInt(), monthIndex(m.groupValues[2]))
        }
        Regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?\b""").find(text)?.let { m ->
            return explicitDate(m.range, m.groupValues[2].toInt(), monthIndex(m.groupValues[1]))
        }
        Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?![\d/])""").find(text)?.let { m ->
            val month = m.groupValues[2].toInt() - 1
            if (month in 0..11) return explicitDate(m.range, m.groupValues[1].toInt(), month)
        }
        for ((pattern, offset) in RELATIVE_DAYS) {
            val m = pattern.find(text) ?: continue
            return Day(m.range) { it.add(Calendar.DAY_OF_YEAR, offset) }
        }
        for ((dow, names) in WEEKDAYS) {
            val m = Regex("""(?<![\p{L}\p{M}])(?:$names)""").find(text) ?: continue
            return Day(m.range) { cal ->
                var delta = (dow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (delta == 0) delta = 7
                cal.add(Calendar.DAY_OF_YEAR, delta)
            }
        }
        return null
    }

    private fun monthIndex(name: String) = MONTHS.indexOf(name.take(3))

    private fun explicitDate(range: IntRange, day: Int, month: Int): Day? {
        if (day !in 1..31 || month !in 0..11) return null
        return Day(range) { cal ->
            val today = cal.clone() as Calendar
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) < today.get(Calendar.DAY_OF_YEAR)
            ) cal.add(Calendar.YEAR, 1)
        }
    }

    private class Clock(val range: IntRange, val hour: Int, val minute: Int, val amPm: Char?)

    private fun numberValue(token: String): Int? = token.toIntOrNull() ?: NUMBER_WORDS[token]

    private fun findClock(text: String): Clock? {
        for ((i, p) in TIME_PATTERNS.withIndex()) {
            for (m in p.findAll(text)) {
                val g = m.groupValues
                val clock = when (i) {
                    0 -> clock(m.range, g[1].toIntOrNull(), g[2].toIntOrNull() ?: 0, g[3])
                    1 -> clock(m.range, numberValue(g[1]), 0, g[2])
                    2 -> clock(m.range, numberValue(g[1]), 0, "")
                    3 -> {
                        val hour = numberValue(g[2]) ?: continue
                        val fraction = FRACTIONS[g[1]] ?: 0
                        val minute = g[3].toIntOrNull() ?: g[4].toIntOrNull() ?: 0
                        if (fraction < 0) clock(m.range, hour - 1, 60 + fraction, "")
                        else clock(m.range, hour, minute + fraction, "")
                    }
                    4 -> HALVES[g[1]]?.let { clock(m.range, it / 60, it % 60, "") }
                    else -> clock(m.range, g[1].toIntOrNull()?.takeIf { it in 1..12 }, 0, "")
                }
                if (clock != null) return clock
            }
        }
        return null
    }

    private fun clock(range: IntRange, hour: Int?, minute: Int, ampm: String): Clock? {
        if (hour == null || hour !in 0..23 || minute !in 0..59) return null
        val mark = ampm.firstOrNull { it == 'a' || it == 'p' }
        if (mark != null && hour !in 1..12) return null
        return Clock(range, hour, minute, mark)
    }

    private fun resolveHour(clock: Clock, period: Period?): Int {
        val h = clock.hour
        return when {
            clock.amPm == 'a' -> h % 12
            clock.amPm == 'p' -> h % 12 + 12
            h >= 13 || h == 0 -> h
            period == Period.MORNING -> h
            period != null -> if (h < 12) h + 12 else h
            h in 1..7 -> h + 12
            else -> h
        }
    }

    private fun findWhen(text: String, receivedAt: Long, timeZone: TimeZone): When? {
        val day = findDay(text)
        val clock = findClock(text)
        val periodMatch = PERIODS.firstNotNullOfOrNull { (p, period) -> p.find(text)?.let { it to period } }
        val period = periodMatch?.second
        // A period word alone ("in the evening") with no day or time isn't worth a reminder.
        if (day == null && clock == null) return null

        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = receivedAt }
        day?.set?.invoke(cal)
        val hour = clock?.let { resolveHour(it, period) } ?: period?.defaultHour
        val allDay = hour == null
        if (hour != null) {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, clock?.minute ?: 0)
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
        }
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // "at 5" with no day, said after 5: the next 5 o'clock.
        if (day == null && cal.timeInMillis < receivedAt) cal.add(Calendar.DAY_OF_YEAR, 1)

        // Show the words as spoken: one span when they sit together ("कल शाम 5 बजे"), else day + time.
        val ranges = listOfNotNull(day?.range, clock?.range, periodMatch?.first?.range?.takeIf { r ->
            listOfNotNull(day?.range, clock?.range).any { near(it, r) }
        }).sortedBy { it.first }
        val joined = ranges.zipWithNext().all { (a, b) -> b.first - a.last <= 12 }
        val spoken = if (joined) text.substring(ranges.first().first, ranges.last().last + 1)
        else ranges.joinToString(" ") { text.substring(it) }
        val span = ranges.first().first..ranges.last().last
        return When(spoken.trim().trimEnd(',', '.', '।'), span, ReminderTime(cal.timeInMillis, allDay))
    }

    /** True when the two spans overlap or have at most 12 characters between them. */
    private fun near(a: IntRange, b: IntRange) = maxOf(a.first, b.first) - minOf(a.last, b.last) <= 12

    // ---- What --------------------------------------------------------------

    private val TOPICS = listOf(
        // English
        "delivery", "parcel", "package", "courier", "order", "payment", "invoice", "bill", "salary", "loan", "emi",
        "insurance", "policy", "meeting", "appointment", "interview", "doctor", "hospital", "school", "exam",
        "booking", "ticket", "flight", "train", "refund", "cheque", "rent", "otp", "kyc", "account", "card",
        "birthday", "wedding", "party", "report", "repair", "installation", "service",
        // Hindi
        "डिलीवरी", "पार्सल", "कूरियर", "कुरियर", "ऑर्डर", "पेमेंट", "भुगतान", "बिल", "सैलरी", "तनख्वाह", "लोन",
        "ईएमआई", "बीमा", "पॉलिसी", "मीटिंग", "अपॉइंटमेंट", "इंटरव्यू", "डॉक्टर", "अस्पताल", "स्कूल", "परीक्षा",
        "बुकिंग", "टिकट", "रिफंड", "चेक", "किराया", "खाता", "कार्ड", "शादी", "जन्मदिन", "पार्टी", "रिपोर्ट",
        "सर्विस", "मरम्मत",
        // Marathi
        "डिलिव्हरी", "कुरिअर", "पगार", "कर्ज", "हप्ता", "विमा", "मुलाखत", "दवाखाना", "रुग्णालय", "शाळा",
        "तिकीट", "भाडे", "खाते", "लग्न", "वाढदिवस", "सर्व्हिस", "दुरुस्ती"
    )

    /** Case endings that may follow a Hindi/Marathi topic word ("पार्सलचे", "बिल को"); not "बिल्कुल". */
    private val SUFFIXES = listOf("", "का", "की", "के", "को", "में", "से", "चे", "चा", "ची", "ला", "ना", "त", "साठी")

    /** The first known topic word ("delivery", "डिलीवरी", "पगार"), also with a plural or case ending. */
    private fun findTopic(text: String): String? {
        for (word in WORD.findAll(text)) {
            val token = word.value
            val topic = TOPICS.firstOrNull { t ->
                if (t[0] in 'a'..'z') token == t || token == t + "s" else SUFFIXES.any { token == t + it }
            }
            if (topic != null) return topic
        }
        return null
    }

    private val FILLER = setOf(
        "hi", "hello", "hey", "namaste", "sir", "madam", "ma'am", "please", "plz", "okay", "ok", "so", "and",
        "um", "uh", "yes", "your", "my", "the", "i", "we", "from", "this", "is", "it", "that", "just", "to",
        "नमस्ते", "नमस्कार", "हॅलो", "हेलो", "हैलो", "सर", "मैडम", "मॅडम", "जी", "कृपया", "अरे", "आपका", "आपकी",
        "आपके", "मेरा", "मेरी", "तुमचा", "तुमची", "तुमचे", "माझा", "माझी", "माझे", "से", "कडून", "मधून", "है", "आहे"
    )

    /** A few words from the first sentence that isn't a greeting, cut before the time/amount/action. */
    private fun firstWords(text: String, skip: IntRange?, cuts: List<IntRange>): String? {
        val masked = StringBuilder(text)
        skip?.let { for (i in it) masked.setCharAt(i, ' ') }
        cuts.forEach { r -> if (r.first < masked.length) masked.setCharAt(r.first, '.') }
        for (sentence in masked.split(Regex("""[.!?।\n]+"""))) {
            val words = WORD.findAll(sentence).map { it.value }.dropWhile { it in FILLER }.toList()
            val kept = words.take(5).dropLastWhile { it in FILLER }
            if (kept.isNotEmpty()) return kept.joinToString(" ")
        }
        return null
    }
}
