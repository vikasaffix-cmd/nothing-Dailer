package com.example.allinonedailer.data

import android.content.Context
import org.json.JSONObject

/** A contact number assigned to a keypad key (2–9). */
data class SpeedDialEntry(
    val key: Int,
    val name: String,
    val number: String,
    val photoUri: String? = null
)

/**
 * Speed-dial assignments for keypad keys 2–9, kept in SharedPreferences.
 * Key 1 is always voicemail and is never stored here.
 */
class SpeedDialRepository(context: Context) {

    private val prefs = context.getSharedPreferences("speed_dial", Context.MODE_PRIVATE)

    fun get(key: Int): SpeedDialEntry? {
        val raw = prefs.getString(prefKey(key), null) ?: return null
        return try {
            val json = JSONObject(raw)
            SpeedDialEntry(
                key = key,
                name = json.optString("name"),
                number = json.getString("number"),
                photoUri = json.optString("photo").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getAll(): Map<Int, SpeedDialEntry> =
        KEYS.mapNotNull { key -> get(key)?.let { key to it } }.toMap()

    fun assign(key: Int, name: String, number: String, photoUri: String?) {
        require(key in KEYS) { "Speed dial keys are 2–9" }
        val json = JSONObject()
            .put("name", name)
            .put("number", number)
            .put("photo", photoUri.orEmpty())
        prefs.edit().putString(prefKey(key), json.toString()).apply()
    }

    fun clear(key: Int) {
        prefs.edit().remove(prefKey(key)).apply()
    }

    private fun prefKey(key: Int) = "key_$key"

    companion object {
        val KEYS = 2..9
    }
}
