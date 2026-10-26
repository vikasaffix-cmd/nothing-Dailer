package com.example.allinonedailer

import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.KeypadIndex
import com.example.allinonedailer.data.T9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {

    private fun match(name: String, digits: String) = T9.match(T9.key(name), digits)

    @Test
    fun lettersMapToKeypadDigits() {
        assertEquals('2', T9.digitFor('a'))
        assertEquals('2', T9.digitFor('C'))
        assertEquals('7', T9.digitFor('s'))
        assertEquals('9', T9.digitFor('Z'))
        assertEquals('5', T9.digitFor('5'))
        assertNull(T9.digitFor(' '))
    }

    @Test
    fun matchesStartOfFullName() {
        assertEquals(0 until 4, match("Aria Gupta", "2742"))
    }

    @Test
    fun matchesStartOfLaterWord() {
        assertEquals(0 until 2, match("Gunja Didi", "48"))
        assertEquals(5 until 7, match("Aria Gupta", "48"))
        assertEquals(6 until 9, match("Gunja Didi", "343"))
    }

    @Test
    fun matchRunsAcrossSpaces() {
        assertEquals(0 until 6, match("Aria Gupta", "27424"))
    }

    @Test
    fun doesNotMatchMidWord() {
        assertNull(match("Aria Gupta", "742"))
    }

    @Test
    fun ignoresCaseAndAccents() {
        assertEquals(0 until 4, match("JOSÉ", "5673"))
        assertEquals(0 until 3, match("zoë", "963"))
        assertEquals(4 until 6, match("Zoë Ångström", "26"))
    }

    @Test
    fun nonLatinNamesDoNotMatchByLetters() {
        assertNull(match("विकास", "2"))
        // A Latin word after a Devanagari one still matches.
        assertEquals(6 until 8, match("विकास Ji", "54"))
    }

    @Test
    fun emptyDigitsNeverMatch() {
        assertNull(match("Aria", ""))
    }

    @Test
    fun searchRanksNumberPrefixThenNameThenContains() {
        val contacts = listOf(
            ContactItem("1", "Aria Gupta", "+91 99999 00000"),
            ContactItem("2", "Bob", "2742 1111"),
            ContactItem("3", "Carl", "555 2742"),
            ContactItem("4", "Dan", "123")
        )
        val ids = KeypadIndex(contacts).search("2742").map { it.contact.id }
        assertEquals(listOf("2", "1", "3"), ids)
    }

    @Test
    fun searchMatchesNationalNumberAfterCountryCode() {
        val contacts = listOf(ContactItem("1", "X", "+91 98765 43210"))
        val m = KeypadIndex(contacts).search("98765").single()
        // Highlight lands on "98765" in the formatted number.
        assertEquals(4 until 9, m.numberHighlight)
    }

    @Test
    fun searchShowsEachContactOnceAtBestRank() {
        val contacts = listOf(
            ContactItem("1", "Gunja Didi", "555 4800"),
            ContactItem("1", "Gunja Didi", "4812 000")
        )
        val results = KeypadIndex(contacts).search("48")
        assertEquals(1, results.size)
        assertEquals("4812 000", results[0].contact.phoneNumber)
    }

    @Test
    fun searchCapsResults() {
        val contacts = (1..100).map { ContactItem("$it", "Name $it", "98$it") }
        assertEquals(20, KeypadIndex(contacts).search("9").size)
    }

    @Test
    fun indexOfNineThousandContactsSearchesQuickly() {
        val contacts = (1..9200).map { ContactItem("$it", "Person Number$it", "+91 9${it.toString().padStart(9, '0')}") }
        val index = KeypadIndex(contacts)
        val start = System.nanoTime()
        repeat(100) { index.search("7377") }
        val perSearchMs = (System.nanoTime() - start) / 1e6 / 100
        println("9200 contacts: ${"%.2f".format(perSearchMs)} ms per search")
        assertTrue(perSearchMs < 50)
    }
}
