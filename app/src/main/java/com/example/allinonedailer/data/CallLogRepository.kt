package com.example.allinonedailer.data

import android.content.Context
import android.os.SystemClock
import android.provider.CallLog
import android.util.Log
import java.util.Date
import kotlin.math.abs

data class CallLogEntry(
    val id: String,
    val number: String,
    val callerName: String?,
    val callType: Int, // CallLog.Calls.INCOMING_TYPE, OUTGOING_TYPE, MISSED_TYPE, REJECTED_TYPE
    val date: Date,
    val durationSeconds: Long,
    val recordingPath: String? = null,
    val isSpam: Boolean = false,
    val spamCategory: String? = null,
    val photoUri: String? = null,
    val numberLabel: String? = null
)

class CallLogRepository(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 100

        /**
         * The last list loaded, so Recents and the keypad can show it instantly
         * while a fresh load runs in the background.
         */
        @Volatile
        var lastResult: List<CallLogEntry> = emptyList()
            private set
    }

    /** Call off the main thread. */
    fun getCallLogs(): List<CallLogEntry> {
        val startedAt = SystemClock.elapsedRealtime()
        val list = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val callerIdRepo = CallerIdRepository(context)
        // Parse the saved recordings once, not once per row.
        val recordings = RecordingRepository(context).getRecordings()
        // The same few numbers repeat through the log; resolve each one once.
        val infoByNumber = HashMap<String, CallerInfo>()

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val photoIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_PHOTO_URI)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

                while (list.size < MAX_ENTRIES && cursor.moveToNext()) {
                    val number = cursor.getString(numberIdx) ?: "Unknown"
                    // Unknown numbers come back with an empty name, not null.
                    val cachedName = (if (nameIdx != -1) cursor.getString(nameIdx) else null)
                        ?.takeIf { it.isNotBlank() }
                    val cachedPhoto = (if (photoIdx != -1) cursor.getString(photoIdx) else null)
                        ?.takeIf { it.isNotBlank() }
                    val dateMs = cursor.getLong(dateIdx)
                    val digits = number.filter(Char::isDigit)

                    val info = infoByNumber.getOrPut(digits.takeLast(10).ifEmpty { number }) {
                        if (cachedName != null) {
                            // Saved contact: the call log already cached its name and
                            // photo, so skip the contacts query and number lookup.
                            val blocked = callerIdRepo.isBlocked(number)
                            CallerInfo(
                                phoneNumber = number,
                                name = cachedName,
                                inContacts = true,
                                isSpam = blocked,
                                spamCategory = if (blocked) "Blocked by you" else null,
                                source = if (blocked) "blocklist" else "contacts"
                            )
                        } else {
                            callerIdRepo.lookupCallerInfo(number)
                        }
                    }

                    val recording = recordings.firstOrNull { item ->
                        item.phoneNumber.filter(Char::isDigit) == digits &&
                            abs(item.timestamp - dateMs) < 60_000
                    }

                    list.add(
                        CallLogEntry(
                            id = cursor.getString(idIdx),
                            number = number,
                            callerName = cachedName ?: info.name,
                            callType = cursor.getInt(typeIdx),
                            date = Date(dateMs),
                            durationSeconds = cursor.getLong(durationIdx),
                            recordingPath = recording?.filePath,
                            isSpam = info.isSpam,
                            spamCategory = info.spamCategory,
                            photoUri = cachedPhoto ?: info.photoUri?.toString(),
                            numberLabel = info.numberLabel
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        lastResult = list
        Log.d("CallLogRepository", "Loaded ${list.size} calls in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return list
    }
}
