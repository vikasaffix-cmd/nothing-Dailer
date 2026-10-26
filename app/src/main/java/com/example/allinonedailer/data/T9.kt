package com.example.allinonedailer.data

import java.text.Normalizer

/**
 * T9 name matching for the keypad: letters map to keypad digits
 * (2 = ABC … 9 = WXYZ) and typed digits match the start of the name or of
 * any word in it. Case and accents are ignored; letters outside A–Z
 * (e.g. Devanagari) have no key and never match.
 */
object T9 {

    private val LETTER_DIGITS = "22233344455566677778889999"

    /** Keypad digit for [c], or null when it has no key. Digits map to themselves. */
    fun digitFor(c: Char): Char? {
        if (c in '0'..'9') return c
        val base = baseLetter(c.lowercaseChar())
        return if (base in 'a'..'z') LETTER_DIGITS[base - 'a'] else null
    }

    /** Builds the key once per name; [match] is then cheap per keystroke. */
    fun key(name: String): Key {
        val keys = StringBuilder(name.length)
        val positions = IntArray(name.length)
        val wordStarts = ArrayList<Int>()
        var inWord = false
        name.forEachIndexed { i, c ->
            val d = digitFor(c)
            if (d == null) {
                // A letter we can't key (e.g. Devanagari) still belongs to its word.
                inWord = c.isLetter()
                return@forEachIndexed
            }
            if (!inWord) wordStarts += keys.length
            inWord = true
            positions[keys.length] = i
            keys.append(d)
        }
        return Key(keys.toString(), positions.copyOf(keys.length), wordStarts.toIntArray())
    }

    /**
     * The characters of the original name matched by [digits] (end exclusive),
     * or null. Tries the full name first, then each later word; a match may
     * run on across spaces ("27424" finds "Aria G…").
     */
    fun match(key: Key, digits: String): IntRange? {
        if (digits.isEmpty()) return null
        for (start in key.wordStarts) {
            if (key.keys.startsWith(digits, start)) {
                return key.positions[start] until key.positions[start + digits.length - 1] + 1
            }
        }
        return null
    }

    private fun baseLetter(c: Char): Char {
        if (c < 'À') return c
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return decomposed.firstOrNull() ?: c
    }

    class Key(
        /** Keypad digit of every keyable character, in order. */
        val keys: String,
        /** Index in the original name of each entry in [keys]. */
        val positions: IntArray,
        /** Indices into [keys] where a word starts; the first is the full name. */
        val wordStarts: IntArray
    )
}

/** A keypad suggestion with the matched part to highlight (end exclusive). */
data class KeypadMatch(
    val contact: ContactItem,
    val nameHighlight: IntRange? = null,
    val numberHighlight: IntRange? = null
)

/**
 * Contacts prepared for keypad search: number digits and T9 keys are computed
 * once (build it off the main thread), so [search] only compares strings.
 */
class KeypadIndex(contacts: List<ContactItem>) {

    private class Entry(val contact: ContactItem, val digits: String, val t9: T9.Key)

    private val entries = contacts.map {
        Entry(it, it.phoneNumber.filter(Char::isDigit), T9.key(it.name))
    }

    /**
     * Number-prefix matches first, then T9 name matches, then numbers that
     * contain [typed] elsewhere; each contact once, at its best rank.
     */
    fun search(typed: String, limit: Int = 20): List<KeypadMatch> {
        if (typed.isEmpty()) return emptyList()
        val prefix = ArrayList<KeypadMatch>()
        val name = ArrayList<KeypadMatch>()
        val contains = ArrayList<KeypadMatch>()
        val seen = HashSet<String>()
        val nameSeen = HashSet<String>()
        // Pass 1: number prefixes (with or without the country code).
        for (e in entries) {
            if (e.contact.id in seen) continue
            val at = when {
                e.digits.startsWith(typed) -> 0
                e.digits.length > 10 && e.digits.startsWith(typed, e.digits.length - 10) -> e.digits.length - 10
                else -> -1
            }
            if (at >= 0) {
                seen += e.contact.id
                prefix += KeypadMatch(e.contact, numberHighlight = numberRange(e.contact.phoneNumber, at, typed.length))
                if (prefix.size >= limit) break
            }
        }
        // Pass 2: T9 names, one row per contact (its first number).
        if (prefix.size < limit) for (e in entries) {
            if (e.contact.id in seen || e.contact.id in nameSeen) continue
            val range = T9.match(e.t9, typed) ?: continue
            nameSeen += e.contact.id
            name += KeypadMatch(e.contact, nameHighlight = range)
            if (prefix.size + name.size >= limit) break
        }
        seen += nameSeen
        // Pass 3: digits anywhere in the number.
        if (prefix.size + name.size < limit) for (e in entries) {
            if (e.contact.id in seen) continue
            val at = e.digits.indexOf(typed)
            if (at < 0) continue
            seen += e.contact.id
            contains += KeypadMatch(e.contact, numberHighlight = numberRange(e.contact.phoneNumber, at, typed.length))
            if (prefix.size + name.size + contains.size >= limit) break
        }
        return (prefix + name + contains).take(limit)
    }

    /** Maps a digit-only range back onto the formatted [number]. */
    private fun numberRange(number: String, digitStart: Int, length: Int): IntRange? {
        var digitIndex = 0
        var first = -1
        number.forEachIndexed { i, c ->
            if (!c.isDigit()) return@forEachIndexed
            if (digitIndex == digitStart) first = i
            if (digitIndex == digitStart + length - 1) return first until i + 1
            digitIndex++
        }
        return null
    }
}
