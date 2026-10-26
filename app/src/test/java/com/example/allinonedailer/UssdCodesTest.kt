package com.example.allinonedailer

import com.example.allinonedailer.data.balance.UssdCode
import com.example.allinonedailer.data.balance.UssdCodes
import com.example.allinonedailer.data.balance.UssdKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdCodesTest {

    @Test
    fun validCodes() {
        assertTrue(UssdCodes.isValid("*123#"))
        assertTrue(UssdCodes.isValid("*121*2#"))
        assertTrue(UssdCodes.isValid(" *333 * 1 # "))
        assertTrue(UssdCodes.isValid("#31#"))
        assertFalse(UssdCodes.isValid("123"))
        assertFalse(UssdCodes.isValid("*123"))
        assertFalse(UssdCodes.isValid("*#"))
        assertFalse(UssdCodes.isValid("*12a#"))
        assertFalse(UssdCodes.isValid(""))
    }

    @Test
    fun builtInsForEachOperator() {
        val airtel = UssdCodes.defaults("Airtel")
        assertEquals("*123#", airtel.first { it.id == "airtel.balance" }.code)
        assertTrue(airtel.all { it.verified && it.builtIn && UssdCodes.isValid(it.code) })
        val jio = UssdCodes.defaults("Jio")
        assertEquals("*333#", jio.first { it.id == "jio.balance" }.code)
        // No official Jio page lists codes, so none is marked confirmed.
        assertTrue(jio.none { it.verified })
        assertTrue(UssdCodes.defaults("BSNL").isEmpty())
        assertTrue(UssdCodes.defaults(null).isEmpty())
    }

    @Test
    fun onlyInfoCodesAreOneTap() {
        val all = UssdCodes.defaults("Airtel") + UssdCodes.defaults("Jio")
        assertTrue(all.first { it.id == "airtel.balance" }.oneTap)
        assertFalse("menus can buy packs", all.first { it.id == "airtel.offers" }.oneTap)
        assertFalse(all.first { it.id == "jio.data" }.oneTap)
        assertTrue(all.filter { it.oneTap }.all { it.kind == UssdKind.INFO })
        // An info code that isn't a valid code is never sent.
        assertFalse(UssdCode("x", "", "123", UssdKind.INFO).oneTap)
    }

    @Test
    fun userCodesAskFirstUnlessMarkedInfo() {
        val recharge = UssdCodes.custom("c1", " Pack ", "*121 * 51#")
        assertEquals(UssdKind.ACTION, recharge.kind)
        assertFalse(recharge.oneTap)
        assertEquals("*121*51#", recharge.code)
        assertEquals("Pack", recharge.label)
        assertTrue(UssdCodes.custom("c2", "SMS left", "*123*7#", infoOnly = true).oneTap)
    }

    @Test
    fun chainsAMenuOptionIntoOneCode() {
        assertEquals("*121*2#", UssdCodes.chained("*121#", "2"))
        assertEquals("*333*1*3#", UssdCodes.chained("*333*1#", " 3 "))
        assertNull(UssdCodes.chained("*121#", ""))
        assertNull(UssdCodes.chained("*121#", "2a"))
        assertNull(UssdCodes.chained("*121#", "1234"))
        assertNull(UssdCodes.chained("#31#", "1"))
        assertNull(UssdCodes.chained("121", "1"))
    }

    @Test
    fun findsMenuOptionsInAReply() {
        val menu = "Airtel offers\n1. Data packs\n2) Talktime\n3 - Roaming\nReply with option"
        assertEquals(listOf("1", "2", "3"), UssdCodes.menuOptions(menu).map { it.first })
        assertTrue(UssdCodes.menuOptions("Main Bal: Rs 12.50, valid till 30-11-2026").isEmpty())
        // A single numbered line is not a menu.
        assertTrue(UssdCodes.menuOptions("1. Your balance is Rs 10").isEmpty())
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val codes = UssdCodes.defaults("Airtel") + UssdCodes.custom("c1", "Mine", "*999#")
        assertEquals(codes, UssdCodes.decode(UssdCodes.encode(codes)))
    }

    @Test
    fun decodeFallsBackSafely() {
        assertNull(UssdCodes.decode(null))
        assertNull(UssdCodes.decode(""))
        assertNull(UssdCodes.decode("not json"))
        val future = """[{"id":"x","label":"","code":"*1#","kind":"SOMETHING_NEW"}]"""
        assertEquals(UssdKind.ACTION, UssdCodes.decode(future)!!.single().kind)
    }
}
