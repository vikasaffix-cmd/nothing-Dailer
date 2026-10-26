package com.example.allinonedailer.data.balance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a code does, which decides how it may be sent:
 * [INFO] only reads (balance, validity, own number) and is sent with one tap;
 * [MENU] opens an operator menu whose options can buy packs, and
 * [ACTION] may change a plan or spend money: both need the user to confirm first.
 */
enum class UssdKind { INFO, MENU, ACTION }

data class UssdCode(
    val id: String,
    /** Blank for a built-in code the user hasn't renamed: the UI shows its translated name. */
    val label: String,
    val code: String,
    val kind: UssdKind,
    /** False when no official or consistent recent source confirmed the code. */
    val verified: Boolean = true,
    val builtIn: Boolean = false
) {
    val oneTap: Boolean get() = kind == UssdKind.INFO && UssdCodes.isValid(code)
}

/** USSD codes per operator: defaults, checks, menu replies and storage. Pure Kotlin for JVM tests. */
object UssdCodes {

    private val VALID = Regex("^[*#][0-9*#]{0,40}#$")

    /** Removes spaces and dashes people type or paste ("*121 * 2 #"). */
    fun normalize(code: String): String = code.filterNot { it.isWhitespace() || it == '-' }

    /** Starts with * or #, ends with #, only digits * # between, at least one digit. */
    fun isValid(code: String): Boolean {
        val c = normalize(code)
        return c.length in 3..42 && VALID.matches(c) && c.any(Char::isDigit)
    }

    /**
     * Built-in codes, checked 26 Oct 2026.
     * Airtel: airtel.in's own help blog (updated May 2026) lists *123# talktime,
     * *121*2# plan validity and balance, *121# offers, *282# own number.
     * Jio: no jio.com page lists USSD codes; *333# and *1# are what recent
     * third-party guides agree on, and they disagree on a data code, so all
     * Jio codes are marked unconfirmed (and the data menu path needs confirming).
     */
    fun defaults(network: String?): List<UssdCode> = when (network) {
        "Jio" -> listOf(
            UssdCode("jio.balance", "", "*333#", UssdKind.INFO, verified = false, builtIn = true),
            UssdCode("jio.number", "", "*1#", UssdKind.INFO, verified = false, builtIn = true),
            UssdCode("jio.data", "", "*333*1*3*2#", UssdKind.MENU, verified = false, builtIn = true)
        )
        "Airtel" -> listOf(
            UssdCode("airtel.balance", "", "*123#", UssdKind.INFO, builtIn = true),
            UssdCode("airtel.plan", "", "*121*2#", UssdKind.INFO, builtIn = true),
            UssdCode("airtel.number", "", "*282#", UssdKind.INFO, builtIn = true),
            UssdCode("airtel.offers", "", "*121#", UssdKind.MENU, builtIn = true)
        )
        else -> emptyList()
    }

    /**
     * The code that picks [option] in the menu [base] opened, as one string
     * ("*121#" + "2" → "*121*2#"), since Android's USSD API can't answer a
     * menu in the same session. Null when either part isn't usable.
     */
    fun chained(base: String, option: String): String? {
        val b = normalize(base)
        val o = option.trim()
        if (!isValid(b) || !b.startsWith("*") || o.isEmpty() || !o.all(Char::isDigit) || o.length > 3) return null
        return b.dropLast(1) + "*" + o + "#"
    }

    private val MENU_LINE = Regex("^\\s*(\\d{1,2})\\s*[.):\\-]\\s*\\S.*")

    /** The numbered options in a network reply ("1. Data balance"), empty when it isn't a menu. */
    fun menuOptions(reply: String): List<Pair<String, String>> {
        val options = reply.lines().mapNotNull { line ->
            MENU_LINE.matchEntire(line)?.let { m -> m.groupValues[1] to line.trim() }
        }
        return if (options.size >= 2) options else emptyList()
    }

    /** A new user code: always asks before sending unless the user says it only reads info. */
    fun custom(id: String, label: String, code: String, infoOnly: Boolean = false) =
        UssdCode(id, label.trim(), normalize(code), if (infoOnly) UssdKind.INFO else UssdKind.ACTION, verified = true, builtIn = false)

    fun encode(codes: List<UssdCode>): String {
        val array = JsonArray()
        for (c in codes) {
            array.add(JsonObject().apply {
                addProperty("id", c.id)
                addProperty("label", c.label)
                addProperty("code", c.code)
                addProperty("kind", c.kind.name)
                addProperty("verified", c.verified)
                addProperty("builtIn", c.builtIn)
            })
        }
        return array.toString()
    }

    /** Null when [json] is missing or unreadable, so the caller falls back to [defaults]. */
    fun decode(json: String?): List<UssdCode>? {
        if (json.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(json).asJsonArray.mapNotNull { e ->
                val o = e.asJsonObject
                val code = o.get("code")?.asString ?: return@mapNotNull null
                UssdCode(
                    id = o.get("id")?.asString ?: code,
                    label = o.get("label")?.asString.orEmpty(),
                    code = code,
                    // Unknown kinds from a newer version are treated as the safest one.
                    kind = runCatching { UssdKind.valueOf(o.get("kind").asString) }.getOrDefault(UssdKind.ACTION),
                    verified = o.get("verified")?.asBoolean ?: true,
                    builtIn = o.get("builtIn")?.asBoolean ?: false
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
