package com.example.allinonedailer

import com.example.allinonedailer.data.VoicemailAction
import com.example.allinonedailer.data.VoicemailSummarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VoicemailSummaryTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    /** Monday 26 Oct 2026, 09:15 IST. */
    private val received = at(2026, Calendar.OCTOBER, 26, 9, 15)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(ist).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun summarize(text: String, language: String?, knownName: String? = null) =
        VoicemailSummarizer.summarize(text, language, knownName, received, ist)!!

    @Test
    fun englishDeliveryWithTimeAndCallBack() {
        val s = summarize("Hi, this is Ravi from Amazon. Your delivery will come tomorrow at 5 pm. Please call me back.", "en")
        assertEquals("Ravi", s.who)
        assertEquals("delivery", s.what)
        assertEquals("tomorrow at 5 pm", s.whenText)
        assertEquals(VoicemailAction.CALL_BACK, s.action)
        assertEquals("Ravi: delivery, tomorrow at 5 pm, call back", s.line)
        assertEquals(at(2026, Calendar.OCTOBER, 27, 17, 0), s.reminder!!.startMillis)
        assertFalse(s.reminder!!.allDay)
    }

    @Test
    fun hindiParcelWithEveningTimeAndAmount() {
        val s = summarize("नमस्ते, मैं रवि बोल रहा हूँ। कल शाम 5 बजे पार्सल की डिलीवरी है, 500 रुपये तैयार रखिए। कृपया कॉल बैक करें।", "hi")
        assertEquals("रवि", s.who)
        assertEquals("पार्सल", s.what)
        assertEquals("कल शाम 5 बजे", s.whenText)
        assertEquals("₹500", s.amount)
        assertEquals("रवि: पार्सल, कल शाम 5 बजे, ₹500, कॉल बैक करें", s.line)
        assertEquals(at(2026, Calendar.OCTOBER, 27, 17, 0), s.reminder!!.startMillis)
    }

    @Test
    fun marathiMeetingDetectsLanguage() {
        val s = summarize("नमस्कार, मी सुनील बोलतोय. उद्या सकाळी 10 वाजता मीटिंग आहे. मला परत फोन करा.", null)
        assertEquals("mr", s.language)
        assertEquals("सुनील", s.who)
        assertEquals("मीटिंग", s.what)
        assertEquals("उद्या सकाळी 10 वाजता", s.whenText)
        assertEquals("सुनील: मीटिंग, उद्या सकाळी 10 वाजता, परत फोन करा", s.line)
        assertEquals(at(2026, Calendar.OCTOBER, 27, 10, 0), s.reminder!!.startMillis)
    }

    @Test
    fun contactNameWinsAndDateWithoutTimeIsAllDay() {
        val s = summarize("Hello, it's about your loan EMI of Rs 2,500 due on 5th November.", "en", knownName = "HDFC Bank")
        assertEquals("HDFC Bank", s.who)
        assertEquals("loan", s.what)
        assertEquals("₹2,500", s.amount)
        assertTrue(s.reminder!!.allDay)
        assertEquals(at(2026, Calendar.NOVEMBER, 5, 0, 0), s.reminder!!.startMillis)
        assertEquals("HDFC Bank: loan, 5th november, ₹2,500", s.line)
    }

    @Test
    fun noDateOrTimeMeansNoReminder() {
        val s = summarize("Hi this is Priya, just checking in. Call me when free.", "en")
        assertEquals("Priya", s.who)
        assertNull(s.reminder)
        assertEquals(VoicemailAction.CALL_BACK, s.action)
        assertEquals("Priya: checking in, call back", s.line)
    }

    @Test
    fun timeAlreadyPassedMovesToNextDay() {
        val s = summarize("Can you call me at 8", "en")
        assertEquals(at(2026, Calendar.OCTOBER, 27, 8, 0), s.reminder!!.startMillis)
    }

    @Test
    fun devanagariDigitsAndAfternoonGuess() {
        val s = summarize("आज ५ वाजता या", "mr")
        assertEquals(at(2026, Calendar.OCTOBER, 26, 17, 0), s.reminder!!.startMillis)
    }

    @Test
    fun hindiHalfPastWithPrecomposedNukta() {
        // "साढ़े पाँच बजे" with the single-character ढ़ (U+095D).
        val s = summarize("साढ़े पाँच बजे आइए", "hi")
        assertEquals(at(2026, Calendar.OCTOBER, 26, 17, 30), s.reminder!!.startMillis)
    }

    @Test
    fun weekdayIsTheNextOne() {
        val s = summarize("Your appointment is on Friday", "en")
        assertEquals("appointment", s.what)
        assertTrue(s.reminder!!.allDay)
        assertEquals(at(2026, Calendar.OCTOBER, 30, 0, 0), s.reminder!!.startMillis)
        val mr = summarize("शुक्रवारी भेटू", "mr")
        assertEquals(at(2026, Calendar.OCTOBER, 30, 0, 0), mr.reminder!!.startMillis)
    }

    @Test
    fun amountWithMultiplierAndMessageAction() {
        val s = summarize("5 हजार रुपये भेजने हैं, व्हाट्सएप करें", "hi")
        assertEquals("₹5 हजार", s.amount)
        assertEquals(VoicemailAction.MESSAGE, s.action)
        assertTrue(s.line.endsWith("मैसेज करें"))
    }

    @Test
    fun commonWordIsNotATopic() {
        val s = summarize("बिल्कुल ठीक है, कल मिलते हैं", "hi")
        assertNotEquals("बिल", s.what)
        assertNotNull(s.reminder)
    }

    @Test
    fun detectsLanguageByScriptAndWords() {
        assertEquals("en", VoicemailSummarizer.detectLanguage("please call back"))
        assertEquals("hi", VoicemailSummarizer.detectLanguage("मैं कल आपको फोन करूंगा, ठीक है"))
        assertEquals("mr", VoicemailSummarizer.detectLanguage("मी उद्या तुम्हाला फोन करतो, ठीक आहे"))
    }

    @Test
    fun blankTranscriptHasNoSummary() {
        assertNull(VoicemailSummarizer.summarize("   ", "en", null, received, ist))
    }
}
