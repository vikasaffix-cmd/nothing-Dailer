package com.example.allinonedailer

import com.example.allinonedailer.data.VoicemailRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class VoicemailNameTest {

    @Test
    fun numberWithPlusAndDate() {
        assertEquals("+919876543210", VoicemailRepository.numberFromName("voicemail_+919876543210_20261025-1430.m4a"))
    }

    @Test
    fun bareTenDigitMobile() {
        assertEquals("9876543210", VoicemailRepository.numberFromName("9876543210.mp3"))
    }

    @Test
    fun spacedAndDashedNumber() {
        assertEquals("+919876543210", VoicemailRepository.numberFromName("Voicemail from +91 98765-43210.amr"))
    }

    @Test
    fun twelveDigitsWithCountryCode() {
        assertEquals("919876543210", VoicemailRepository.numberFromName("vm 919876543210 (1).ogg"))
    }

    @Test
    fun dateOnlyIsNotANumber() {
        assertNull(VoicemailRepository.numberFromName("VM_20261025_143000.wav"))
        assertNull(VoicemailRepository.numberFromName("message.opus"))
    }

    @Test
    fun timestampFromName() {
        val ts = VoicemailRepository.timestampFromName("9876543210_20261025-1430.m4a")
        assertNotNull(ts)
        val c = Calendar.getInstance().apply { timeInMillis = ts!! }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH))
        assertEquals(25, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
        assertNull(VoicemailRepository.timestampFromName("9876543210.mp3"))
    }
}
