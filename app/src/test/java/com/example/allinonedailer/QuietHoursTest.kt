package com.example.allinonedailer

import com.example.allinonedailer.data.QuietHoursConfig
import com.example.allinonedailer.data.QuietHoursRules
import com.example.allinonedailer.data.ScreenAction
import com.example.allinonedailer.data.ScreenReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    private val night = QuietHoursConfig(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60)
    private val now = 1_000_000_000L
    private fun min(h: Int, m: Int = 0) = h * 60 + m

    private fun decide(
        config: QuietHoursConfig = night,
        emergency: Boolean = false,
        blocked: Boolean = false,
        quietNow: Boolean = true,
        favourite: Boolean = false,
        vip: Boolean = false,
        previousCallAt: Long? = null
    ) = QuietHoursRules.decide(config, emergency, blocked, quietNow, favourite, vip, previousCallAt, now)

    @Test
    fun scheduleAcrossMidnight() {
        // Monday = 1, Tuesday = 2
        assertTrue(QuietHoursRules.inSchedule(night, 1, min(23, 30)))
        assertTrue(QuietHoursRules.inSchedule(night, 2, min(2)))
        assertTrue(QuietHoursRules.inSchedule(night, 2, min(6, 59)))
        assertFalse(QuietHoursRules.inSchedule(night, 2, min(7)))
        assertFalse(QuietHoursRules.inSchedule(night, 1, min(22, 59)))
        assertFalse(QuietHoursRules.inSchedule(night, 1, min(12)))
    }

    @Test
    fun earlyMorningBelongsToTheNightBefore() {
        // Quiet only on Friday and Saturday nights.
        val weekend = night.copy(days = setOf(5, 6))
        assertTrue(QuietHoursRules.inSchedule(weekend, 6, min(3)))   // Saturday 03:00 = Friday night
        assertTrue(QuietHoursRules.inSchedule(weekend, 7, min(3)))   // Sunday 03:00 = Saturday night
        assertFalse(QuietHoursRules.inSchedule(weekend, 5, min(3)))  // Friday 03:00 = Thursday night
        assertFalse(QuietHoursRules.inSchedule(weekend, 7, min(23))) // Sunday night
        // Sunday night carries into Monday morning.
        val sunday = night.copy(days = setOf(7))
        assertTrue(QuietHoursRules.inSchedule(sunday, 1, min(1)))
    }

    @Test
    fun sameDayWindowAndAllDay() {
        val afternoon = night.copy(startMinute = min(13), endMinute = min(15))
        assertTrue(QuietHoursRules.inSchedule(afternoon, 3, min(14)))
        assertFalse(QuietHoursRules.inSchedule(afternoon, 3, min(15)))
        val allDay = night.copy(startMinute = 0, endMinute = 0, days = setOf(7))
        assertTrue(QuietHoursRules.inSchedule(allDay, 7, min(12)))
        assertFalse(QuietHoursRules.inSchedule(allDay, 1, min(12)))
    }

    @Test
    fun offOrFollowingDnd() {
        assertFalse(QuietHoursRules.isQuiet(night.copy(enabled = false), 1, min(23, 30), dndActive = true))
        val dnd = night.copy(followDnd = true)
        assertTrue(QuietHoursRules.isQuiet(dnd, 1, min(12), dndActive = true))
        assertFalse(QuietHoursRules.isQuiet(dnd, 1, min(23, 30), dndActive = false))
    }

    @Test
    fun strangerIsSilencedNotBlocked() {
        val d = decide()
        assertEquals(ScreenAction.SILENCE, d.action)
        assertEquals(ScreenReason.SILENCED, d.reason)
    }

    @Test
    fun outsideQuietHoursRings() {
        assertEquals(ScreenAction.ALLOW, decide(quietNow = false).action)
    }

    @Test
    fun favouriteRings() {
        assertEquals(ScreenReason.FAVOURITE, decide(favourite = true).reason)
        // Unless favourites were switched off.
        assertEquals(ScreenAction.SILENCE, decide(config = night.copy(allowFavourites = false), favourite = true).action)
    }

    @Test
    fun vipRings() {
        assertEquals(ScreenReason.VIP, decide(vip = true).reason)
    }

    @Test
    fun repeatCallerWithinWindowRings() {
        assertEquals(ScreenReason.REPEAT_CALLER, decide(previousCallAt = now - 2 * 60_000L).reason)
        assertEquals(ScreenReason.REPEAT_CALLER, decide(previousCallAt = now - 3 * 60_000L).reason)
    }

    @Test
    fun repeatCallerOutsideWindowIsSilenced() {
        assertEquals(ScreenAction.SILENCE, decide(previousCallAt = now - 3 * 60_000L - 1).action)
        assertEquals(ScreenAction.SILENCE, decide(previousCallAt = now - 30 * 60_000L).action)
        val off = night.copy(repeatCallers = false)
        assertEquals(ScreenAction.SILENCE, decide(config = off, previousCallAt = now - 60_000L).action)
        val wide = night.copy(repeatWindowMinutes = 10)
        assertEquals(ScreenAction.ALLOW, decide(config = wide, previousCallAt = now - 9 * 60_000L).action)
    }

    @Test
    fun blockedNumberIsBlockedEvenIfFavouriteOrRepeat() {
        val d = decide(blocked = true, favourite = true, vip = true, previousCallAt = now - 60_000L)
        assertEquals(ScreenAction.BLOCK, d.action)
        assertEquals(ScreenAction.BLOCK, decide(blocked = true, quietNow = false).action)
    }

    @Test
    fun emergencyIsNeverBlockedOrSilenced() {
        val d = decide(emergency = true, blocked = true)
        assertEquals(ScreenAction.ALLOW, d.action)
        assertEquals(ScreenReason.EMERGENCY, d.reason)
    }
}
