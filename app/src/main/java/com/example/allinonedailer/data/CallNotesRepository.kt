package com.example.allinonedailer.data

import android.content.Context
import android.provider.CallLog
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID
import kotlin.math.abs

/**
 * A note on one call. [callId] is the call log's `_ID` once the note is tied
 * to its row; a note written as the call ends has no row yet, so until then
 * it is found by [number] and the call's [startTime] / [endTime].
 */
data class CallNote(
    val key: String,
    val callId: String?,
    val number: String,
    /** The call log's DATE once matched; before that, when the call started (0 if unknown). */
    val startTime: Long,
    /** When the call ended, or 0 for a note added from Recents. */
    val endTime: Long,
    val text: String,
    val editedAt: Long
) {
    val firstLine: String get() = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** Pure matching between notes and call-log rows (unit-tested on the JVM). */
object CallNoteMatcher {

    /** Telecom writes the call-log DATE from the call's creation time; allow for clock skew. */
    const val START_TOLERANCE_MS = 60_000L
    /** A row whose start we already know (e.g. after its id changed) must be this close. */
    const val SAME_CALL_MS = 2_000L

    fun numberKey(number: String): String =
        number.filter(Char::isDigit).takeLast(10).ifEmpty { number.trim() }

    /**
     * The call-log row for a call to or from [number] that started at [startTime]
     * and ended at [endTime]: same number, started no earlier than
     * [START_TOLERANCE_MS] before [startTime] and no later than it ended.
     * The closest start wins. Without a start time, the row whose start plus
     * duration lands nearest [endTime] wins.
     */
    fun findCall(entries: List<CallLogEntry>, number: String, startTime: Long, endTime: Long): CallLogEntry? {
        val key = numberKey(number)
        val from = (if (startTime > 0) startTime else endTime - 6 * 60 * 60 * 1000L) - START_TOLERANCE_MS
        val to = (if (endTime > 0) endTime else startTime) + 5_000L
        return entries
            .filter { numberKey(it.number) == key && it.date.time in from..to }
            .minByOrNull {
                if (startTime > 0) abs(it.date.time - startTime)
                else abs(it.date.time + it.durationSeconds * 1000 - endTime)
            }
    }

    /**
     * Which note belongs to which row: by call id first, then (for notes not
     * yet tied to a row, or whose row was renumbered) by number and time.
     */
    fun byEntryId(entries: List<CallLogEntry>, notes: List<CallNote>): Map<String, CallNote> {
        if (notes.isEmpty()) return emptyMap()
        val result = HashMap<String, CallNote>()
        val ids = entries.mapTo(HashSet()) { it.id }
        val loose = mutableListOf<CallNote>()
        for (note in notes) {
            if (note.callId != null && note.callId in ids) result[note.callId] = note else loose += note
        }
        for (note in loose) {
            val match = if (note.callId != null) {
                // Tied to a row that's gone: the same call under a new id, if any.
                entries.firstOrNull {
                    it.id !in result && numberKey(it.number) == numberKey(note.number) &&
                        abs(it.date.time - note.startTime) <= SAME_CALL_MS
                }
            } else {
                findCall(entries.filter { it.id !in result }, note.number, note.startTime, note.endTime)
            }
            if (match != null) result[match.id] = note
        }
        return result
    }

    /** The newest note for [number], for the incoming-call screen. */
    fun lastFor(notes: List<CallNote>, number: String): CallNote? {
        val key = numberKey(number)
        return notes.filter { numberKey(it.number) == key }
            .maxByOrNull { maxOf(it.startTime, it.endTime) }
    }
}

/**
 * Call notes, kept as a small JSON list in SharedPreferences (a few hundred
 * notes at most, so no database). Every read and write runs on Dispatchers.IO.
 */
object CallNotesRepository {

    private const val TAG = "CallNotes"
    private const val PREFS = "call_notes"
    private const val KEY_NOTES = "notes"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loaded = false
    private val _notes = MutableStateFlow<List<CallNote>>(emptyList())
    val notes: StateFlow<List<CallNote>> = _notes.asStateFlow()

    suspend fun load(context: Context): List<CallNote> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(context) }
    }

    /** Drops the in-memory list and reads the saved notes again, e.g. after a backup is restored. */
    suspend fun reload(context: Context): List<CallNote> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded = false
            ensureLoaded(context)
        }
    }

    /** Adds or replaces the note on a call-log row (from Recents). */
    suspend fun saveForCall(context: Context, entry: CallLogEntry, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = ensureLoaded(context)
            val existing = CallNoteMatcher.byEntryId(listOf(entry), list)[entry.id]
            val note = CallNote(
                key = existing?.key ?: newKey(),
                callId = entry.id,
                number = entry.number,
                startTime = entry.date.time,
                endTime = existing?.endTime ?: 0L,
                text = text.trim(),
                editedAt = System.currentTimeMillis()
            )
            write(context, list.filterNot { it.key == note.key } + note)
        }
    }

    /**
     * Saves a note for a call that just ended. Its call-log row may not exist
     * yet, so the note is stored by number and time and tied to the row once
     * it shows up (here, for a few seconds, or later when Recents loads).
     */
    suspend fun saveForEndedCall(
        context: Context,
        number: String,
        startTime: Long,
        endTime: Long,
        text: String
    ) = withContext(Dispatchers.IO) {
        val key = mutex.withLock {
            val list = ensureLoaded(context)
            // The same call noted twice (card and notification) edits one note.
            val existing = list.firstOrNull {
                it.endTime == endTime && CallNoteMatcher.numberKey(it.number) == CallNoteMatcher.numberKey(number)
            }
            val note = CallNote(
                key = existing?.key ?: newKey(),
                callId = existing?.callId,
                number = number,
                startTime = existing?.startTime ?: startTime,
                endTime = endTime,
                text = text.trim(),
                editedAt = System.currentTimeMillis()
            )
            write(context, list.filterNot { it.key == note.key } + note)
            note.key
        }
        // The note is saved; finding its row can take a few seconds, so don't wait for it.
        val app = context.applicationContext
        scope.launch {
            for (wait in longArrayOf(0, 1500, 3000, 6000)) {
                delay(wait)
                if (tieToCallLog(app, key)) break
            }
        }
    }

    suspend fun delete(context: Context, note: CallNote) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, ensureLoaded(context).filterNot { it.key == note.key }) }
    }

    /** Ties notes still waiting for their row to the rows Recents just loaded. */
    suspend fun resolvePending(context: Context, entries: List<CallLogEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = ensureLoaded(context)
            if (list.none { it.callId == null }) return@withLock
            val byId = CallNoteMatcher.byEntryId(entries, list)
            var changed = false
            val updated = list.map { note ->
                if (note.callId != null) return@map note
                val entry = byId.entries.firstOrNull { it.value.key == note.key } ?: return@map note
                changed = true
                note.copy(callId = entry.key, startTime = entries.first { it.id == entry.key }.date.time)
            }
            if (changed) write(context, updated)
        }
    }

    suspend fun lastNoteFor(context: Context, number: String): CallNote? =
        CallNoteMatcher.lastFor(load(context), number)

    /** Looks the call up in the call log; true once the note has its row. */
    private suspend fun tieToCallLog(context: Context, key: String): Boolean {
        val note = mutex.withLock { ensureLoaded(context).firstOrNull { it.key == key } } ?: return true
        if (note.callId != null) return true
        val since = (if (note.startTime > 0) note.startTime else note.endTime - 6 * 60 * 60 * 1000L) -
            CallNoteMatcher.START_TOLERANCE_MS
        val entry = CallNoteMatcher.findCall(queryCallsSince(context, since), note.number, note.startTime, note.endTime)
            ?: return false
        mutex.withLock {
            val list = ensureLoaded(context)
            write(context, list.map {
                if (it.key == key) it.copy(callId = entry.id, startTime = entry.date.time) else it
            })
        }
        Log.d(TAG, "Note tied to call ${entry.id}")
        return true
    }

    private fun queryCallsSince(context: Context, since: Long): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    list += CallLogEntry(
                        id = c.getString(0),
                        number = c.getString(1) ?: "",
                        callerName = null,
                        callType = c.getInt(2),
                        date = Date(c.getLong(3)),
                        durationSeconds = c.getLong(4)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the call log", e)
        }
        return list
    }

    private fun newKey() = UUID.randomUUID().toString()

    private fun ensureLoaded(context: Context): List<CallNote> {
        if (loaded) return _notes.value
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NOTES, null)
        _notes.value = try {
            raw?.let { parse(JSONArray(it)) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read saved notes", e)
            emptyList()
        }
        loaded = true
        return _notes.value
    }

    private fun write(context: Context, list: List<CallNote>) {
        val array = JSONArray()
        list.forEach { n ->
            array.put(
                JSONObject()
                    .put("key", n.key)
                    .put("callId", n.callId ?: JSONObject.NULL)
                    .put("number", n.number)
                    .put("start", n.startTime)
                    .put("end", n.endTime)
                    .put("text", n.text)
                    .put("edited", n.editedAt)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NOTES, array.toString()).commit()
        _notes.value = list
    }

    private fun parse(array: JSONArray): List<CallNote> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        CallNote(
            key = o.getString("key"),
            callId = if (o.isNull("callId")) null else o.getString("callId"),
            number = o.getString("number"),
            startTime = o.optLong("start"),
            endTime = o.optLong("end"),
            text = o.getString("text"),
            editedAt = o.optLong("edited")
        )
    }
}
