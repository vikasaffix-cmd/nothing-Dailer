package com.example.allinonedailer

import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.CallPlacer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisions only: nothing here dials. */
class CallPlacerTest {

    private val noSystem: (String) -> Boolean = { false }
    private val sim = Any()

    @Test
    fun indianEmergencyNumbersAreEmergencyWithoutTheSystemList() {
        for (n in listOf("112", "100", "101", "102", "108", "911", " 1 1 2 ", "10-8")) {
            assertTrue(n, CallPlacer.isEmergency(n, noSystem))
        }
    }

    @Test
    fun ordinaryNumbersAreNotEmergency() {
        for (n in listOf("1000", "1121", "9876543210", "+91 98765 43210", "1800123456", "", "  ", "*#06#")) {
            assertFalse(n, CallPlacer.isEmergency(n, noSystem))
        }
    }

    @Test
    fun systemListAddsNumbersAndGetsCleanedInput() {
        val seen = mutableListOf<String>()
        val system: (String) -> Boolean = { seen += it; it == "999" }
        assertTrue(CallPlacer.isEmergency("9 9 9", system))
        assertEquals(listOf("999"), seen)
    }

    @Test
    fun failingSystemCheckFallsBackToTheList() {
        val throws: (String) -> Boolean = { throw IllegalStateException("no telephony") }
        assertTrue(CallPlacer.isEmergency("112", throws))
        assertFalse(CallPlacer.isEmergency("999", throws))
    }

    @Test
    fun emergencyGetsNoSimVideoOrSpeakerExtras() {
        val d = CallPlacer.decide("112", video = true, account = sim, speaker = true,
            hasCallPermission = true, systemCheck = noSystem)
        assertEquals(Decision.Place("112", emergency = true, extras = emptyMap()), d)
    }

    @Test
    fun systemRecognisedEmergencyAlsoDropsTheSim() {
        val d = CallPlacer.decide("999", account = sim, hasCallPermission = true, systemCheck = { it == "999" })
        assertEquals(Decision.Place("999", emergency = true, extras = emptyMap()), d)
    }

    @Test
    fun normalCallCarriesSimVideoAndSpeaker() {
        val d = CallPlacer.decide("+91 98765-43210", video = true, account = sim, speaker = true,
            hasCallPermission = true, systemCheck = noSystem)
        assertEquals(
            Decision.Place(
                "+919876543210", emergency = false,
                extras = mapOf(
                    TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE to VideoProfile.STATE_BIDIRECTIONAL,
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE to sim,
                    TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE to true
                )
            ),
            d
        )
    }

    @Test
    fun plainCallHasNoExtras() {
        val d = CallPlacer.decide("9876543210", hasCallPermission = true, systemCheck = noSystem)
        assertEquals(Decision.Place("9876543210", emergency = false, extras = emptyMap()), d)
    }

    @Test
    fun ussdCodesKeepStarAndHash() {
        val d = CallPlacer.decide("*121#", hasCallPermission = true, systemCheck = noSystem)
        assertEquals("*121#", d.number)
    }

    @Test
    fun noPermissionFallsBackToShowingTheNumber() {
        assertEquals(
            Decision.Fallback("9876543210", emergency = false),
            CallPlacer.decide("98765 43210", video = true, account = sim, hasCallPermission = false, systemCheck = noSystem)
        )
        assertEquals(
            Decision.Fallback("112", emergency = true),
            CallPlacer.decide("112", hasCallPermission = false, systemCheck = noSystem)
        )
    }
}
