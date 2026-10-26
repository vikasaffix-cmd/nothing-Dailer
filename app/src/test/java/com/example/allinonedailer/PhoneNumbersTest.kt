package com.example.allinonedailer

import com.example.allinonedailer.data.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun indianMobileSpellingsMatch() {
        val spellings = listOf(
            "9876543210", "+91 98765 43210", "+919876543210", "09876543210",
            "0091 98765 43210", "919876543210", "(+91) 98765-43210"
        )
        for (s in spellings) assertEquals(s, "9876543210", PhoneNumbers.key(s))
        assertTrue(PhoneNumbers.sameNumber("+91 98765 43210", "098765 43210"))
    }

    @Test
    fun landlineWithStdCode() {
        assertTrue(PhoneNumbers.sameNumber("022 2266 0000", "+91 22 2266 0000"))
        assertFalse(PhoneNumbers.sameNumber("022 2266 0000", "022 2266 0001"))
    }

    @Test
    fun shortCodesMatchOnlyThemselves() {
        assertEquals("121", PhoneNumbers.key("121"))
        assertTrue(PhoneNumbers.sameNumber("121", "121"))
        assertFalse(PhoneNumbers.sameNumber("121", "1121"))
        assertFalse(PhoneNumbers.sameNumber("198", "199"))
    }

    @Test
    fun tollFreeKeepsAllDigits() {
        assertEquals("18001234567", PhoneNumbers.key("1800-123-4567"))
        assertTrue(PhoneNumbers.sameNumber("1800 123 4567", "+91 1800 123 4567"))
        assertTrue(PhoneNumbers.sameNumber("1860 500 1234", "18605001234"))
    }

    @Test
    fun tollFreeDoesNotMatchLookalikeMobile() {
        // The last 10 digits of 1800-123-4567 are a valid mobile number, 80012 34567.
        assertFalse(PhoneNumbers.sameNumber("1800 123 4567", "+91 80012 34567"))
        assertFalse(PhoneNumbers.sameNumber("18605001234", "8605001234"))
    }

    @Test
    fun noDigitsNeverMatches() {
        assertEquals("", PhoneNumbers.key("Unknown"))
        assertFalse(PhoneNumbers.sameNumber("Unknown", "Unknown"))
        assertFalse(PhoneNumbers.sameNumber("", ""))
    }

    @Test
    fun foreignNumbersUseLastTenDigits() {
        assertTrue(PhoneNumbers.sameNumber("+1 650 555 1234", "6505551234"))
        assertFalse(PhoneNumbers.sameNumber("+1 650 555 1234", "+1 650 555 1235"))
    }
}
