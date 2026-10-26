package com.example.allinonedailer

import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.QuietHoursConfig
import com.example.allinonedailer.data.TileAction
import com.example.allinonedailer.data.TileCallEntry
import com.example.allinonedailer.data.TileLogic
import com.example.allinonedailer.data.TileStatus
import com.example.allinonedailer.data.TileSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileLogicTest {

    private val incoming = TileLogic.INCOMING_TYPE
    private val missed = TileLogic.MISSED_TYPE
    private val outgoing = 2

    @Test
    fun quietHoursShowsStateAndSchedule() {
        val on = TileLogic.quietHours(QuietHoursConfig(enabled = true, startMinute = 22 * 60, endMinute = 6 * 60 + 30))
        assertEquals(TileStatus.ACTIVE, on.status)
        assertEquals(TileSubtitle.Schedule(22 * 60, 6 * 60 + 30), on.subtitle)
        assertEquals(TileAction.ToggleQuietHours, on.action)

        val off = TileLogic.quietHours(QuietHoursConfig(enabled = false))
        assertEquals(TileStatus.INACTIVE, off.status)
        assertEquals(TileSubtitle.Schedule(23 * 60, 7 * 60), off.subtitle)

        assertEquals(TileSubtitle.FollowsDnd, TileLogic.quietHours(QuietHoursConfig(enabled = true, followDnd = true)).subtitle)
    }

    private val mom = ContactItem("1", "Mom", "+91 98765 43210", starred = true)
    private val dad = ContactItem("2", "Dad", "98765 00000", starred = true)

    @Test
    fun favouriteTargetPrefersTheChosenOne() {
        assertEquals(TileCallEntry("12345 67890", "Office", 0), TileLogic.favouriteTarget("12345 67890", "Office", listOf(mom, dad)))
        // A blank name falls back to showing the number.
        assertEquals(TileCallEntry("12345 67890", null, 0), TileLogic.favouriteTarget("12345 67890", " ", emptyList()))
    }

    @Test
    fun favouriteTargetDefaultsToFirstCallableStarred() {
        assertEquals(TileCallEntry(mom.phoneNumber, "Mom", 0), TileLogic.favouriteTarget(null, null, listOf(mom, dad)))
        val noNumber = ContactItem("3", "Nobody", "", starred = true)
        assertEquals(TileCallEntry(dad.phoneNumber, "Dad", 0), TileLogic.favouriteTarget("", null, listOf(noNumber, dad)))
        assertNull(TileLogic.favouriteTarget(null, null, emptyList()))
    }

    @Test
    fun favouriteTileStates() {
        val target = TileCallEntry(mom.phoneNumber, "Mom", 0)
        val ready = TileLogic.favourite(target, hasContactsPermission = true, locked = false)
        assertEquals(TileStatus.ACTIVE, ready.status)
        assertEquals(TileSubtitle.Person("Mom"), ready.subtitle)
        assertEquals(TileAction.Call(mom.phoneNumber), ready.action)

        // Locked: still calls (after unlocking) but doesn't show who.
        val locked = TileLogic.favourite(target, hasContactsPermission = true, locked = true)
        assertEquals(TileSubtitle.Locked, locked.subtitle)
        assertEquals(TileAction.Call(mom.phoneNumber), locked.action)

        // A chosen favourite works without contacts access.
        assertEquals(TileStatus.ACTIVE, TileLogic.favourite(target, hasContactsPermission = false, locked = false).status)

        val noAccess = TileLogic.favourite(null, hasContactsPermission = false, locked = false)
        assertEquals(TileStatus.INACTIVE, noAccess.status)
        assertEquals(TileSubtitle.NeedsContacts, noAccess.subtitle)
        assertEquals(TileAction.OpenApp, noAccess.action)

        val none = TileLogic.favourite(null, hasContactsPermission = true, locked = false)
        assertEquals(TileSubtitle.NoFavourite, none.subtitle)
        assertEquals(TileAction.OpenApp, none.action)
    }

    @Test
    fun lastCallerSkipsOutgoingAndHiddenNumbers() {
        val entries = listOf(
            TileCallEntry("98765 11111", "Me", outgoing),
            TileCallEntry("", null, missed),
            TileCallEntry("-1", null, incoming),
            TileCallEntry("-2", null, missed),
            TileCallEntry("98765 22222", "Asha", missed),
            TileCallEntry("98765 33333", null, incoming)
        )
        assertEquals(TileCallEntry("98765 22222", "Asha", missed), TileLogic.lastCaller(entries))
        assertNull(TileLogic.lastCaller(listOf(TileCallEntry("98765 11111", null, outgoing))))
        assertNull(TileLogic.lastCaller(emptyList()))
    }

    @Test
    fun lastCallerTileStates() {
        val noAccess = TileLogic.lastCallerTile(null, hasCallLogPermission = false, locked = false)
        assertEquals(TileStatus.INACTIVE, noAccess.status)
        assertEquals(TileSubtitle.NeedsCallLog, noAccess.subtitle)
        assertEquals(TileAction.OpenApp, noAccess.action)

        val empty = TileLogic.lastCallerTile(null, hasCallLogPermission = true, locked = false)
        assertEquals(TileStatus.UNAVAILABLE, empty.status)
        assertEquals(TileSubtitle.NoCalls, empty.subtitle)

        val named = TileLogic.lastCallerTile(TileCallEntry("98765 22222", "Asha", missed), true, locked = false)
        assertEquals(TileStatus.ACTIVE, named.status)
        assertEquals(TileSubtitle.Person("Asha"), named.subtitle)
        assertEquals(TileAction.Call("98765 22222"), named.action)

        assertEquals(
            TileSubtitle.Person("98765 33333"),
            TileLogic.lastCallerTile(TileCallEntry("98765 33333", null, incoming), true, locked = false).subtitle
        )
        assertEquals(
            TileSubtitle.Locked,
            TileLogic.lastCallerTile(TileCallEntry("98765 33333", null, incoming), true, locked = true).subtitle
        )
    }
}
