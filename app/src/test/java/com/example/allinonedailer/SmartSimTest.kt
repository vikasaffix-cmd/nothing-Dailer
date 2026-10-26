package com.example.allinonedailer

import com.example.allinonedailer.data.SimPick
import com.example.allinonedailer.data.SimReason
import com.example.allinonedailer.data.SimRef
import com.example.allinonedailer.data.SmartSimLogic
import com.example.allinonedailer.data.WhatsApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartSimTest {

    private val jio = SimRef("sim-jio", "Jio 4G")
    private val airtel = SimRef("sim-airtel", "airtel")
    private val sims = listOf(jio, airtel)

    private fun choose(
        pinned: String? = null,
        lastUsed: String? = null,
        carrier: String? = null,
        emergency: Boolean = false,
        enabled: Boolean = true,
        available: List<SimRef> = sims
    ) = SmartSimLogic.choose(available, pinned, lastUsed, carrier, emergency, enabled)

    @Test
    fun pinnedBeatsEverything() {
        assertEquals(
            SimPick(airtel.id, SimReason.PINNED),
            choose(pinned = airtel.id, lastUsed = jio.id, carrier = "Reliance Jio")
        )
    }

    @Test
    fun lastUsedBeatsSameNetwork() {
        assertEquals(SimPick(airtel.id, SimReason.LAST_USED), choose(lastUsed = airtel.id, carrier = "Reliance Jio"))
    }

    @Test
    fun sameNetworkWhenNothingRemembered() {
        assertEquals(SimPick(jio.id, SimReason.SAME_NETWORK, "Jio"), choose(carrier = "Reliance Jio"))
        assertEquals(SimPick(airtel.id, SimReason.SAME_NETWORK, "Airtel"), choose(carrier = "Airtel"))
    }

    @Test
    fun systemDefaultWhenNothingFits() {
        assertNull(choose())
        assertNull(choose(carrier = "Vodafone"))
        assertNull(choose(carrier = "BSNL MOBILE"))
    }

    @Test
    fun emergencyAndSwitchedOffIgnoreEverything() {
        assertNull(choose(pinned = jio.id, lastUsed = jio.id, carrier = "Airtel", emergency = true))
        assertNull(choose(pinned = jio.id, lastUsed = jio.id, carrier = "Airtel", enabled = false))
    }

    @Test
    fun removedSimsAreSkipped() {
        // Pinned SIM was taken out: fall through to last used, then network.
        assertEquals(SimPick(jio.id, SimReason.LAST_USED), choose(pinned = "old-sim", lastUsed = jio.id))
        assertEquals(SimPick(airtel.id, SimReason.SAME_NETWORK, "Airtel"), choose(pinned = "old", lastUsed = "old", carrier = "Airtel"))
    }

    @Test
    fun singleSimOrAmbiguousNetworkGetsNoPick() {
        assertNull(choose(pinned = jio.id, available = listOf(jio)))
        assertNull(choose(carrier = "Jio", available = listOf(jio, SimRef("sim2", "JIO"))))
    }

    @Test
    fun networkFamilies() {
        assertEquals("Jio", SmartSimLogic.network("Reliance Jio"))
        assertEquals("Jio", SmartSimLogic.network("JIO 4G"))
        assertEquals("Airtel", SmartSimLogic.network("Bharti Airtel"))
        assertEquals("Vi", SmartSimLogic.network("Vodafone"))
        assertEquals("Vi", SmartSimLogic.network("Idea"))
        assertEquals("Vi", SmartSimLogic.network("Vi India"))
        assertNull(SmartSimLogic.network("Vivid Telecom"))
        assertNull(SmartSimLogic.network(null))
        assertNull(SmartSimLogic.network("SIM 1"))
    }

    @Test
    fun numberKeyIgnoresCountryCodeAndTrunkZero() {
        for (s in listOf("+91 98765 43210", "09876543210", "9876543210", "0091 98765 43210", "919876543210")) {
            assertEquals(s, "9876543210", SmartSimLogic.numberKey(s))
        }
    }

    @Test
    fun waNumberAddsIndianCountryCode() {
        assertEquals("919876543210", WhatsApp.waNumber("9876543210"))
        assertEquals("919876543210", WhatsApp.waNumber("098765 43210"))
        assertEquals("919876543210", WhatsApp.waNumber("+91 98765-43210"))
        assertEquals("919876543210", WhatsApp.waNumber("919876543210"))
        assertEquals("919876543210", WhatsApp.waNumber("0091 98765 43210"))
        assertEquals("442071234567", WhatsApp.waNumber("+44 20 7123 4567"))
        assertEquals("", WhatsApp.waNumber("121"))
    }
}
