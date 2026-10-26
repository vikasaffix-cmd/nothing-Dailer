package com.example.allinonedailer.data

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.util.Log
import com.example.allinonedailer.service.SimOption
import com.example.allinonedailer.service.callCapableSims

/** Why a SIM was picked for a number, strongest first. */
enum class SimReason { PINNED, LAST_USED, SAME_NETWORK }

/** A SIM picked for a number; [network] is the number's network for SAME_NETWORK ("Jio"). */
data class SimPick(val simId: String, val reason: SimReason, val network: String? = null)

/** A call-capable SIM as the pure logic sees it: account id and its label ("Jio 4G"). */
data class SimRef(val id: String, val label: String)

/**
 * Smart SIM choice, kept free of Android types so it runs in JVM tests.
 *
 * Order: pinned SIM > last SIM used for the number > the SIM on the number's
 * own network > nothing (null: the system default, or "Ask every time").
 * Emergency numbers never get a pick.
 */
object SmartSimLogic {

    /**
     * The network family in an operator or SIM name, e.g. "Reliance Jio" and
     * "JIO 4G" are both "Jio"; "Vodafone" and "Idea" are both "Vi". Null when unknown.
     */
    fun network(name: String?): String? {
        val n = name?.lowercase()?.trim().orEmpty()
        if (n.isEmpty()) return null
        val words = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return when {
            "jio" in n -> "Jio"
            "airtel" in n -> "Airtel"
            "vodafone" in n || "idea" in n || "vi" in words -> "Vi"
            "bsnl" in n || "cellone" in n -> "BSNL"
            "mtnl" in n || "dolphin" in n -> "MTNL"
            else -> null
        }
    }

    /**
     * The SIM to preselect for a number. [numberCarrier] is libphonenumber's
     * original operator (can be wrong for ported numbers, so it ranks last).
     * A pinned or last-used SIM that is no longer in [sims] is skipped. Same
     * network only counts when exactly one SIM is on that network.
     */
    fun choose(
        sims: List<SimRef>,
        pinned: String?,
        lastUsed: String?,
        numberCarrier: String?,
        emergency: Boolean,
        enabled: Boolean = true
    ): SimPick? {
        if (!enabled || emergency || sims.size < 2) return null
        val ids = sims.map { it.id }.toSet()
        if (pinned != null && pinned in ids) return SimPick(pinned, SimReason.PINNED)
        if (lastUsed != null && lastUsed in ids) return SimPick(lastUsed, SimReason.LAST_USED)
        val net = network(numberCarrier) ?: return null
        val matching = sims.filter { network(it.label) == net }
        return if (matching.size == 1) SimPick(matching[0].id, SimReason.SAME_NETWORK, net) else null
    }

    /** Storage key for a number: +91 / 0091 / leading 0 spellings share one. */
    fun numberKey(number: String): String = PhoneNumbers.key(number)
}

/** A pinned number shown in Settings. */
data class SimPin(val key: String, val simId: String, val name: String, val simLabel: String)

/**
 * Smart SIM settings and memory. SharedPreferences file `smart_sim`:
 * - `enabled` (Boolean, default true)
 * - `last.<key>` SIM account id last used to call the number
 * - `pin.<key>` pinned SIM account id; `pin_name.<key>` contact name and
 *   `pin_sim.<key>` SIM label, both only for the Settings list
 * <key> is [SmartSimLogic.numberKey].
 */
object SmartSim {
    private const val TAG = "SmartSim"
    const val PREFS = "smart_sim"
    const val KEY_ENABLED = "enabled"
    private const val LAST = "last."
    private const val PIN = "pin."
    private const val PIN_NAME = "pin_name."
    private const val PIN_SIM = "pin_sim."

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun pinnedSimId(context: Context, number: String): String? {
        val key = SmartSimLogic.numberKey(number).ifEmpty { return null }
        return prefs(context).getString(PIN + key, null)
    }

    /** Pins [sim] for every number in [numbers], or clears the pin when [sim] is null. */
    fun setPinned(context: Context, numbers: Collection<String>, name: String, sim: SimOption?) {
        val edit = prefs(context).edit()
        for (number in numbers) {
            val key = SmartSimLogic.numberKey(number)
            if (key.isEmpty()) continue
            if (sim == null) {
                edit.remove(PIN + key).remove(PIN_NAME + key).remove(PIN_SIM + key)
            } else {
                edit.putString(PIN + key, sim.handle.id)
                    .putString(PIN_NAME + key, name)
                    .putString(PIN_SIM + key, sim.label)
            }
        }
        edit.apply()
    }

    fun clearPin(context: Context, key: String) {
        prefs(context).edit().remove(PIN + key).remove(PIN_NAME + key).remove(PIN_SIM + key).apply()
    }

    fun pins(context: Context): List<SimPin> {
        val all = prefs(context).all
        return all.keys.filter { it.startsWith(PIN) }.mapNotNull { k ->
            val key = k.removePrefix(PIN)
            val id = all[k] as? String ?: return@mapNotNull null
            SimPin(
                key = key,
                simId = id,
                name = (all[PIN_NAME + key] as? String).orEmpty().ifEmpty { key },
                simLabel = (all[PIN_SIM + key] as? String).orEmpty()
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Remembers the SIM an outgoing call to [number] went out on. Emergency calls are skipped. */
    fun recordUsed(context: Context, number: String, account: PhoneAccountHandle?) {
        if (account == null || number.isBlank()) return
        if (CallPlacer.isEmergency(context, number)) return
        val key = SmartSimLogic.numberKey(number).ifEmpty { return }
        prefs(context).edit().putString(LAST + key, account.id).apply()
    }

    /**
     * The smart pick for [number] among [sims] (default: the phone's
     * call-capable SIMs). Loads libphonenumber metadata on first use, so
     * call it off the main thread when possible.
     */
    fun suggest(context: Context, number: String, sims: List<SimOption> = callCapableSims(context)): SimPick? = try {
        val p = prefs(context)
        val key = SmartSimLogic.numberKey(number)
        val enabled = p.getBoolean(KEY_ENABLED, true)
        if (!enabled || key.isEmpty() || sims.size < 2) null
        else SmartSimLogic.choose(
            sims = sims.map { SimRef(it.handle.id, it.label) },
            pinned = p.getString(PIN + key, null),
            lastUsed = p.getString(LAST + key, null),
            numberCarrier = NumberInfo.describe(number, NumberInfo.defaultRegion(context))?.carrier,
            emergency = CallPlacer.isEmergency(context, number)
        )
    } catch (e: Exception) {
        Log.w(TAG, "suggest failed", e)
        null
    }

    /** The account for [suggest], for callers that place a call without a SIM toggle. */
    fun pick(context: Context, number: String): PhoneAccountHandle? {
        val sims = callCapableSims(context)
        val pick = suggest(context, number, sims) ?: return null
        return sims.firstOrNull { it.handle.id == pick.simId }?.handle
    }
}
