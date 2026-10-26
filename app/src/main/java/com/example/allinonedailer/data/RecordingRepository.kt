package com.example.allinonedailer.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class RecordingItem(
    val phoneNumber: String,
    val filePath: String,
    val timestamp: Long,
    val durationSeconds: Int
)

class RecordingRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("recording_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_AUTO_RECORD = "auto_record_enabled"
        private const val KEY_RECORDINGS_LIST = "recordings_list_json"

        @Volatile
        private var parsed: Pair<String, List<RecordingItem>>? = null
    }

    fun isAutoRecordEnabled(): Boolean {
        // Opt-in by default: recording calls can require consent in many regions.
        return prefs.getBoolean(KEY_AUTO_RECORD, false)
    }

    fun setAutoRecordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
    }

    fun saveRecording(item: RecordingItem) {
        val currentList = getRecordings().toMutableList()
        currentList.add(0, item)
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_RECORDINGS_LIST, json).apply()
    }

    fun getRecordings(): List<RecordingItem> {
        val json = prefs.getString(KEY_RECORDINGS_LIST, null) ?: return emptyList()
        // Parsed once per saved list; SharedPreferences hands back the same string until it changes.
        parsed?.let { (source, list) -> if (source === json || source == json) return list }
        val type = object : TypeToken<List<RecordingItem>>() {}.type
        val list: List<RecordingItem> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        parsed = json to list
        return list
    }

    fun getRecordingForCall(phoneNumber: String, timestampMs: Long): RecordingItem? {
        val list = getRecordings()
        // Find recording for same phone number within a 60 second window
        return list.firstOrNull { item ->
            val numMatches = item.phoneNumber.filter { it.isDigit() } == phoneNumber.filter { it.isDigit() }
            val timeDiff = Math.abs(item.timestamp - timestampMs)
            numMatches && timeDiff < 60_000
        }
    }

    fun deleteRecording(item: RecordingItem) {
        val file = File(item.filePath)
        if (file.exists()) {
            file.delete()
        }
        val currentList = getRecordings().toMutableList()
        currentList.removeAll { it.filePath == item.filePath }
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_RECORDINGS_LIST, json).apply()
    }
}
