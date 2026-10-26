package com.example.allinonedailer.service.tiles

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import android.util.Log
import com.example.allinonedailer.R
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.QuietHoursRepository
import com.example.allinonedailer.data.TileAction
import com.example.allinonedailer.data.TileCallEntry
import com.example.allinonedailer.data.TileFavourite
import com.example.allinonedailer.data.TileLogic
import com.example.allinonedailer.data.TileModel
import com.example.allinonedailer.data.TileStatus
import com.example.allinonedailer.data.TileSubtitle
import com.example.allinonedailer.ui.MainActivity
import com.example.allinonedailer.widget.WidgetCallActivity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared tile behaviour: the state is worked out off the main thread whenever
 * the Quick Settings panel opens (contacts and the call log are queries), and
 * a tap acts on that state. Calls go through [WidgetCallActivity] (an activity,
 * so [CallPlacer][com.example.allinonedailer.data.CallPlacer] can fall back to
 * the keypad without the call permission); a locked phone unlocks first.
 */
abstract class DialerTileService : TileService() {

    @Volatile
    private var last: TileModel? = null
    private val main = Handler(Looper.getMainLooper())

    /** Works out the tile; runs on a background thread. */
    protected abstract fun model(): TileModel

    override fun onTileAdded() = refresh()

    override fun onStartListening() = refresh()

    protected fun refresh() {
        Thread {
            val model = try {
                model()
            } catch (e: Exception) {
                Log.w(TAG, "Tile state failed", e)
                null
            } ?: return@Thread
            last = model
            main.post { apply(model) }
        }.start()
    }

    private fun apply(model: TileModel) {
        val tile = qsTile ?: return
        tile.state = when (model.status) {
            TileStatus.ACTIVE -> Tile.STATE_ACTIVE
            TileStatus.INACTIVE -> Tile.STATE_INACTIVE
            TileStatus.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        val subtitle = subtitleText(model.subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = "${tile.label}, $subtitle"
        tile.updateTile()
    }

    private fun subtitleText(subtitle: TileSubtitle): String = when (subtitle) {
        is TileSubtitle.Schedule -> getString(R.string.tile_quiet_schedule, clock(subtitle.startMinute), clock(subtitle.endMinute))
        TileSubtitle.FollowsDnd -> getString(R.string.tile_quiet_follows_dnd)
        is TileSubtitle.Person -> subtitle.text
        TileSubtitle.Locked -> getString(R.string.tile_locked)
        TileSubtitle.NoFavourite -> getString(R.string.tile_no_favourite)
        TileSubtitle.NoCalls -> getString(R.string.tile_no_calls)
        TileSubtitle.NeedsContacts -> getString(R.string.tile_needs_contacts)
        TileSubtitle.NeedsCallLog -> getString(R.string.tile_needs_call_log)
    }

    private fun clock(minute: Int): String {
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        return DateFormat.getTimeFormat(this).format(at)
    }

    override fun onClick() {
        val model = last ?: return refresh()
        when (val action = model.action) {
            TileAction.ToggleQuietHours -> toggle()
            is TileAction.Call -> launch(
                Intent(this, WidgetCallActivity::class.java).setData(Uri.fromParts("tel", action.number, null))
            )
            TileAction.OpenApp -> launch(Intent(this, MainActivity::class.java))
        }
    }

    /** Only the quiet-hours tile toggles. */
    protected open fun toggle() = Unit

    /** Starts [intent] and closes the panel; on a locked phone only after unlocking. */
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val start = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ only takes a PendingIntent here.
                    startActivityAndCollapse(
                        PendingIntent.getActivity(
                            this, intent.data.hashCode(), intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tile could not start ${intent.component}", e)
            }
        }
        if (isLocked) unlockAndRun(start) else start.run()
    }

    protected fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Who to call stays off the lock screen when the phone has a secure lock. */
    protected val hideNames: Boolean get() = isLocked && isSecure

    private companion object {
        const val TAG = "DialerTile"
    }
}

/** "Quiet hours": on/off, with the schedule (or "With Do Not Disturb") underneath. */
class QuietHoursTileService : DialerTileService() {
    override fun model(): TileModel = TileLogic.quietHours(QuietHoursRepository(this).config())

    override fun toggle() {
        val repo = QuietHoursRepository(this)
        val config = repo.config()
        repo.save(config.copy(enabled = !config.enabled))
        refresh()
    }
}

/** "Call favourite": the favourite picked in Settings, else the first starred contact. */
class CallFavouriteTileService : DialerTileService() {
    override fun model(): TileModel {
        val canRead = granted(Manifest.permission.READ_CONTACTS)
        val starred = if (canRead && TileFavourite.chosenNumber(this) == null) {
            try {
                ContactsRepository(this).loadContacts().filter { it.starred }
            } catch (e: SecurityException) {
                emptyList()
            }
        } else emptyList()
        val target = TileLogic.favouriteTarget(TileFavourite.chosenNumber(this), TileFavourite.chosenName(this), starred)
        return TileLogic.favourite(target, canRead, hideNames)
    }
}

/** "Last caller": calls back the newest incoming or missed call; the name underneath. */
class LastCallerTileService : DialerTileService() {
    override fun model(): TileModel {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return TileLogic.lastCallerTile(null, false, hideNames)
        val entries = mutableListOf<TileCallEntry>()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE),
                "${CallLog.Calls.TYPE} IN (?, ?)",
                arrayOf(CallLog.Calls.INCOMING_TYPE.toString(), CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                // Hidden numbers are skipped, so look a little further than the newest row.
                while (entries.size < 20 && cursor.moveToNext()) {
                    entries += TileCallEntry(cursor.getString(0).orEmpty(), cursor.getString(1)?.ifBlank { null }, cursor.getInt(2))
                }
            }
        } catch (e: SecurityException) {
            return TileLogic.lastCallerTile(null, false, hideNames)
        }
        val entry = TileLogic.lastCaller(entries)?.let { e ->
            e.copy(name = e.name ?: ContactsRepository.cachedByKey()?.get(PhoneNumbers.key(e.number))?.name)
        }
        return TileLogic.lastCallerTile(entry, true, hideNames)
    }
}
