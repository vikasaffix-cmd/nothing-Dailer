package com.example.allinonedailer.data

import android.app.NotificationManager
import android.content.Context
import android.provider.CallLog
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Quiet hours: during a schedule (or while the system's Do Not Disturb is on)
 * only favourites, VIPs and repeat callers ring; other calls are silenced, not
 * rejected, so they still land in Recents as missed calls.
 */
data class QuietHoursConfig(
    val enabled: Boolean = false,
    /** Quiet whenever system Do Not Disturb is on, instead of the schedule below. */
    val followDnd: Boolean = false,
    /** Minutes after midnight; start > end means the window crosses midnight. */
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    /** ISO days (1 = Monday … 7 = Sunday) on which the window starts. */
    val days: Set<Int> = (1..7).toSet(),
    val allowFavourites: Boolean = true,
    val repeatCallers: Boolean = true,
    val repeatWindowMinutes: Int = 3
)

data class VipEntry(val number: String, val name: String?)

/** What the screening service does with an incoming call. */
enum class ScreenAction { ALLOW, BLOCK, SILENCE }

/** Why a call rings or is silenced, for the log and tests. */
enum class ScreenReason {
    EMERGENCY, BLOCKED, NOT_QUIET, FAVOURITE, VIP, REPEAT_CALLER, SILENCED
}

data class ScreenDecision(val action: ScreenAction, val reason: ScreenReason)

/** Pure rules, kept free of Android types so they run in JVM tests. */
object QuietHoursRules {

    const val MIN_REPEAT_WINDOW = 1
    const val MAX_REPEAT_WINDOW = 15

    /**
     * True when [minute] on ISO [day] falls inside the schedule. A window that
     * crosses midnight (23:00–07:00) belongs to the day it starts on, so
     * Monday's window covers Tuesday 02:00. Equal start and end mean all day.
     */
    fun inSchedule(config: QuietHoursConfig, day: Int, minute: Int): Boolean {
        val start = config.startMinute
        val end = config.endMinute
        val yesterday = if (day == 1) 7 else day - 1
        return when {
            start == end -> day in config.days
            start < end -> day in config.days && minute >= start && minute < end
            else -> (minute >= start && day in config.days) || (minute < end && yesterday in config.days)
        }
    }

    /** Quiet hours apply right now. */
    fun isQuiet(config: QuietHoursConfig, day: Int, minute: Int, dndActive: Boolean): Boolean = when {
        !config.enabled -> false
        config.followDnd -> dndActive
        else -> inSchedule(config, day, minute)
    }

    /** The same number called within the window before this call. */
    fun isRepeat(previousCallAt: Long?, now: Long, windowMinutes: Int): Boolean =
        previousCallAt != null && now - previousCallAt in 0..windowMinutes * 60_000L

    /**
     * Emergency situations first (never blocked or silenced), then block rules,
     * then quiet hours.
     */
    fun decide(
        config: QuietHoursConfig,
        emergency: Boolean,
        blocked: Boolean,
        quietNow: Boolean,
        favourite: Boolean,
        vip: Boolean,
        previousCallAt: Long?,
        now: Long
    ): ScreenDecision = when {
        emergency -> ScreenDecision(ScreenAction.ALLOW, ScreenReason.EMERGENCY)
        blocked -> ScreenDecision(ScreenAction.BLOCK, ScreenReason.BLOCKED)
        !quietNow -> ScreenDecision(ScreenAction.ALLOW, ScreenReason.NOT_QUIET)
        config.allowFavourites && favourite -> ScreenDecision(ScreenAction.ALLOW, ScreenReason.FAVOURITE)
        vip -> ScreenDecision(ScreenAction.ALLOW, ScreenReason.VIP)
        config.repeatCallers && isRepeat(previousCallAt, now, config.repeatWindowMinutes) ->
            ScreenDecision(ScreenAction.ALLOW, ScreenReason.REPEAT_CALLER)
        else -> ScreenDecision(ScreenAction.SILENCE, ScreenReason.SILENCED)
    }
}

/**
 * Numbers the screening service just silenced, so the in-call service can keep
 * the screen, call-waiting buzz and lights quiet for them too. Both services
 * run in the app's one process.
 */
object QuietHoursGate {
    private const val VALID_MS = 3 * 60_000L
    private val silenced = ConcurrentHashMap<String, Long>()

    fun markSilenced(number: String) {
        silenced[PhoneNumbers.key(number).ifEmpty { HIDDEN }] = System.currentTimeMillis()
    }

    /** True for a few minutes after the screening service silenced [number]. */
    fun isSilenced(number: String?): Boolean {
        val key = PhoneNumbers.key(number.orEmpty()).ifEmpty { HIDDEN }
        val at = silenced[key] ?: return false
        return System.currentTimeMillis() - at < VALID_MS
    }

    private const val HIDDEN = "hidden"
}

class QuietHoursRepository(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "QuietHours"
        const val PREFS = "quiet_hours"
        const val KEY_ENABLED = "enabled"
        const val KEY_FOLLOW_DND = "follow_dnd"
        const val KEY_START = "start_minute"
        const val KEY_END = "end_minute"
        const val KEY_DAYS = "days"
        const val KEY_ALLOW_FAVOURITES = "allow_favourites"
        const val KEY_REPEAT = "repeat_callers"
        const val KEY_REPEAT_WINDOW = "repeat_window_minutes"
        const val KEY_VIP = "vip_numbers"
        /** Last incoming call time per number key, for repeat callers. */
        const val KEY_RECENT = "recent_incoming"

        /** A call from 112 & co. can come back this long after you dialled one. */
        private const val EMERGENCY_CALLBACK_MS = 30 * 60_000L
        private const val RECENT_KEEP_MS = 60 * 60_000L
    }

    fun config(): QuietHoursConfig {
        val d = QuietHoursConfig()
        return QuietHoursConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, d.enabled),
            followDnd = prefs.getBoolean(KEY_FOLLOW_DND, d.followDnd),
            startMinute = prefs.getInt(KEY_START, d.startMinute),
            endMinute = prefs.getInt(KEY_END, d.endMinute),
            days = prefs.getStringSet(KEY_DAYS, null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.days,
            allowFavourites = prefs.getBoolean(KEY_ALLOW_FAVOURITES, d.allowFavourites),
            repeatCallers = prefs.getBoolean(KEY_REPEAT, d.repeatCallers),
            repeatWindowMinutes = prefs.getInt(KEY_REPEAT_WINDOW, d.repeatWindowMinutes)
        )
    }

    fun save(config: QuietHoursConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putBoolean(KEY_FOLLOW_DND, config.followDnd)
            .putInt(KEY_START, config.startMinute)
            .putInt(KEY_END, config.endMinute)
            .putStringSet(KEY_DAYS, config.days.map { it.toString() }.toSet())
            .putBoolean(KEY_ALLOW_FAVOURITES, config.allowFavourites)
            .putBoolean(KEY_REPEAT, config.repeatCallers)
            .putInt(
                KEY_REPEAT_WINDOW,
                config.repeatWindowMinutes.coerceIn(QuietHoursRules.MIN_REPEAT_WINDOW, QuietHoursRules.MAX_REPEAT_WINDOW)
            )
            .apply()
    }

    fun vips(): List<VipEntry> = try {
        val array = JSONArray(prefs.getString(KEY_VIP, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            VipEntry(o.getString("number"), o.optString("name").ifBlank { null })
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun addVip(number: String, name: String?): Boolean {
        if (PhoneNumbers.key(number).isEmpty()) return false
        val current = vips()
        if (current.any { PhoneNumbers.sameNumber(it.number, number) }) return false
        saveVips(current + VipEntry(number.trim(), name?.trim()?.ifBlank { null }))
        return true
    }

    fun removeVip(number: String) = saveVips(vips().filterNot { PhoneNumbers.sameNumber(it.number, number) })

    private fun saveVips(list: List<VipEntry>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("number", it.number).put("name", it.name ?: "")) }
        prefs.edit().putString(KEY_VIP, array.toString()).apply()
    }

    fun isVip(number: String): Boolean = vips().any { PhoneNumbers.sameNumber(it.number, number) }

    fun isFavourite(number: String): Boolean = try {
        ContactsRepository(app).loadContacts(onePerContact = false)
            .any { it.starred && PhoneNumbers.sameNumber(it.phoneNumber, number) }
    } catch (e: Exception) {
        false
    }

    /** System Do Not Disturb is on (any filter other than "all"). */
    fun isDndActive(): Boolean = try {
        val filter = app.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Exception) {
        false
    }

    fun isQuietNow(now: Long = System.currentTimeMillis()): Boolean {
        val config = config()
        if (!config.enabled) return false
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        return QuietHoursRules.isQuiet(
            config, local.dayOfWeek.value, local.hour * 60 + local.minute,
            dndActive = config.followDnd && isDndActive()
        )
    }

    /** Previous incoming call from [number] that the screening service saw. */
    fun previousCallAt(number: String): Long? {
        val key = PhoneNumbers.key(number).ifEmpty { return null }
        return recent()[key]
    }

    /** Remembers this incoming call for the repeat-caller check; keeps the last hour. */
    fun recordIncoming(number: String, now: Long = System.currentTimeMillis()) {
        val key = PhoneNumbers.key(number).ifEmpty { return }
        val map = recent().filterValues { now - it < RECENT_KEEP_MS }.toMutableMap()
        map[key] = now
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_RECENT, json.toString()).apply()
    }

    private fun recent(): Map<String, Long> = try {
        val json = JSONObject(prefs.getString(KEY_RECENT, "{}"))
        json.keys().asSequence().associateWith { json.getLong(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    /** You dialled an emergency number in the last half hour, so they may be calling back. */
    fun dialledEmergencyRecently(now: Long = System.currentTimeMillis()): Boolean = try {
        app.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), (now - EMERGENCY_CALLBACK_MS).toString()),
            null
        )?.use { cursor ->
            var found = false
            while (!found && cursor.moveToNext()) {
                found = CallPlacer.isEmergency(app, cursor.getString(0).orEmpty())
            }
            found
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "Could not check recent emergency calls", e)
        false
    }
}
