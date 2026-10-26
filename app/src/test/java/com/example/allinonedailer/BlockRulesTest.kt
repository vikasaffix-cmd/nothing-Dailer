package com.example.allinonedailer

import com.example.allinonedailer.data.BlockRule
import com.example.allinonedailer.data.BlockRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockRulesTest {

    private val promo = listOf(BlockRule("140"))

    @Test
    fun matchesWithCountryCode() {
        assertEquals("Blocked: starts with 140", BlockRules.match("+91 140 1234567", promo))
        assertEquals("Blocked: starts with 140", BlockRules.match("+911401234567", promo))
        assertEquals("Blocked: starts with 140", BlockRules.match("00911401234567", promo))
    }

    @Test
    fun matchesWithTrunkZero() {
        assertEquals("Blocked: starts with 140", BlockRules.match("01401234567", promo))
    }

    @Test
    fun matchesBareNationalNumber() {
        assertEquals("Blocked: starts with 140", BlockRules.match("1401234567", promo))
    }

    @Test
    fun doesNotMatchPrefixInsideNumber() {
        assertNull(BlockRules.match("+91 98140 12345", promo))
        assertNull(BlockRules.match("9814012345", promo))
    }

    @Test
    fun disabledRuleDoesNotMatch() {
        assertNull(BlockRules.match("+91 140 1234567", listOf(BlockRule("140", enabled = false))))
    }

    @Test
    fun bankSendersAreNotCaughtByPromoRule() {
        assertNull(BlockRules.match("+91 160 1234567", promo))
    }

    @Test
    fun nationalNumberStripsCodes() {
        assertEquals("9876543210", BlockRules.nationalNumber("+91 98765 43210"))
        assertEquals("9876543210", BlockRules.nationalNumber("09876543210"))
        assertEquals("9876543210", BlockRules.nationalNumber("919876543210"))
    }
}
