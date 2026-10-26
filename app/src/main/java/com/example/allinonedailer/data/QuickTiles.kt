package com.example.allinonedailer.data

import android.content.Context

/**
 * Quick Settings tiles (service/tiles): pure state rules, kept free of
 * Android types so they run in JVM tests, plus the "Call favourite" choice.
 */
enum class TileStatus { ACTIVE, INACTIVE, UNAVAILABLE }

/** What the tile's second line says; the service turns it into text. */
sealed interface TileSubtitle {
    /** Quiet hours window, minutes after midnight. */
    data class Schedule(val startMinute: Int, val endMinute: Int) : TileSubtitle
    data object FollowsDnd : TileSubtitle
    /** A contact name, or the number when there's no name. */
    data class Person(val text: String) : TileSubtitle
    /** Locked phone: who it would call stays hidden on the lock screen. */
    data object Locked : TileSubtitle
    data object NoFavourite : TileSubtitle
    data object NoCalls : TileSubtitle
    data object NeedsContacts : TileSubtitle
    data object NeedsCallLog : TileSubtitle
}

/** What a tap does. */
sealed interface TileAction {
    data object ToggleQuietHours : TileAction
    data class Call(val number: String) : TileAction
    /** Open the app (Settings) so the user can fix what's missing. */
    data object OpenApp : TileAction
}

data class TileModel(val status: TileStatus, val subtitle: TileSubtitle, val action: TileAction)

/** One call-log row, as the "Last caller" tile sees it (newest first). */
data class TileCallEntry(val number: String, val name: String?, val type: Int)

object TileLogic {
    /** CallLog.Calls.INCOMING_TYPE / MISSED_TYPE (constants, so tests need no Android). */
    const val INCOMING_TYPE = 1
    const val MISSED_TYPE = 3

    fun quietHours(config: QuietHoursConfig): TileModel = TileModel(
        status = if (config.enabled) TileStatus.ACTIVE else TileStatus.INACTIVE,
        subtitle = if (config.followDnd) TileSubtitle.FollowsDnd
        else TileSubtitle.Schedule(config.startMinute, config.endMinute),
        action = TileAction.ToggleQuietHours
    )

    /** Who "Call favourite" calls: the one picked in Settings, else the first starred contact. */
    fun favouriteTarget(chosenNumber: String?, chosenName: String?, starred: List<ContactItem>): TileCallEntry? {
        if (!chosenNumber.isNullOrBlank()) return TileCallEntry(chosenNumber, chosenName?.ifBlank { null }, 0)
        val first = starred.firstOrNull { callable(it.phoneNumber) } ?: return null
        return TileCallEntry(first.phoneNumber, first.name.ifBlank { null }, 0)
    }

    /**
     * "Call favourite": callable when there's a target. Without one, a tap opens
     * the app; the subtitle says whether contacts access or a favourite is missing.
     */
    fun favourite(target: TileCallEntry?, hasContactsPermission: Boolean, locked: Boolean): TileModel = when {
        target != null -> TileModel(TileStatus.ACTIVE, personOrLocked(target, locked), TileAction.Call(target.number))
        !hasContactsPermission -> TileModel(TileStatus.INACTIVE, TileSubtitle.NeedsContacts, TileAction.OpenApp)
        else -> TileModel(TileStatus.INACTIVE, TileSubtitle.NoFavourite, TileAction.OpenApp)
    }

    /** The newest incoming or missed call with a number we can call back (hidden numbers are skipped). */
    fun lastCaller(entries: List<TileCallEntry>): TileCallEntry? = entries.firstOrNull {
        (it.type == INCOMING_TYPE || it.type == MISSED_TYPE) && callable(it.number)
    }

    /** The call log stores private / unknown / payphone callers as "", "-1", "-2", "-3". */
    fun callable(number: String): Boolean = !number.trim().startsWith("-") && PhoneNumbers.key(number).isNotEmpty()

    fun lastCallerTile(entry: TileCallEntry?, hasCallLogPermission: Boolean, locked: Boolean): TileModel = when {
        !hasCallLogPermission -> TileModel(TileStatus.INACTIVE, TileSubtitle.NeedsCallLog, TileAction.OpenApp)
        entry == null -> TileModel(TileStatus.UNAVAILABLE, TileSubtitle.NoCalls, TileAction.OpenApp)
        else -> TileModel(TileStatus.ACTIVE, personOrLocked(entry, locked), TileAction.Call(entry.number))
    }

    private fun personOrLocked(entry: TileCallEntry, locked: Boolean): TileSubtitle =
        if (locked) TileSubtitle.Locked else TileSubtitle.Person(entry.name ?: entry.number)
}

/** The favourite the "Call favourite" tile calls (prefs "quick_tiles"); none = first starred contact. */
object TileFavourite {
    const val PREFS = "quick_tiles"
    const val KEY_NUMBER = "favourite_number"
    const val KEY_NAME = "favourite_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chosenNumber(context: Context): String? = prefs(context).getString(KEY_NUMBER, null)?.ifBlank { null }
    fun chosenName(context: Context): String? = prefs(context).getString(KEY_NAME, null)?.ifBlank { null }

    /** Null clears the choice (back to the first starred contact). */
    fun choose(context: Context, contact: ContactItem?) {
        prefs(context).edit()
            .putString(KEY_NUMBER, contact?.phoneNumber)
            .putString(KEY_NAME, contact?.name)
            .apply()
    }
}
