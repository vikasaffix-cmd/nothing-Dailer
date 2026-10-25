package com.example.allinonedailer

import com.example.allinonedailer.data.NumberInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NumberInfoTest {

    @Test
    fun indianMobileHasTypeAndLabel() {
        val details = NumberInfo.describe("+91 98765 43210", "IN")
        assertNotNull(details)
        assertEquals("Mobile", details!!.type)
        assertNotNull(details.label)
        println("+91 98765 43210 -> ${details.label}")
    }

    @Test
    fun localNumberUsesDefaultRegion() {
        val details = NumberInfo.describe("022 2266 0000", "IN")
        assertNotNull(details)
        println("022 2266 0000 -> ${details!!.label}")
    }

    @Test
    fun shortCodesAreIgnored() {
        assertNull(NumberInfo.describe("121", "IN"))
    }
}
