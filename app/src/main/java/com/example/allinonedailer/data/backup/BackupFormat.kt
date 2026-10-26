package com.example.allinonedailer.data.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.LocalDate

/*
 * Backup file format, kept free of Android calls so JVM unit tests can run it.
 *
 * A backup is a zip:
 *   backup.json                 the manifest below (always)
 *   files/custom_display_font   the user's own display font (when one is set)
 *   recordings/<name>           call recordings (only when the user asks for them)
 * A bare backup.json is accepted on import too.
 */

/** One SharedPreferences value, with its type kept so it restores exactly. */
sealed class PrefValue {
    data class Bool(val value: Boolean) : PrefValue()
    data class IntValue(val value: Int) : PrefValue()
    data class LongValue(val value: Long) : PrefValue()
    data class FloatValue(val value: Float) : PrefValue()
    data class Str(val value: String) : PrefValue()
    data class StrSet(val value: Set<String>) : PrefValue()

    companion object {
        /** From a value of SharedPreferences.getAll(); null for anything else. */
        fun of(raw: Any?): PrefValue? = when (raw) {
            is Boolean -> Bool(raw)
            is Int -> IntValue(raw)
            is Long -> LongValue(raw)
            is Float -> FloatValue(raw)
            is String -> Str(raw)
            is Set<*> -> StrSet(raw.filterIsInstance<String>().toSet())
            else -> null
        }
    }
}

data class BackupData(
    val version: Int = BackupCodec.VERSION,
    val createdAt: Long,
    /** The app that wrote it, e.g. com.maihuvikas.dialer or the old com.example.allinonedailer. */
    val sourcePackage: String,
    val appVersion: String,
    /** The app language tag ("" = follow the phone), or null when the backup has none. */
    val language: String?,
    /** SharedPreferences file name → key → value. */
    val prefs: Map<String, Map<String, PrefValue>>,
    val hasFont: Boolean,
    /** File names under recordings/ in the zip. */
    val recordings: List<String>,
    val secretsIncluded: Boolean
)

/** What is backed up, and what is deliberately left out. */
object BackupSpec {

    data class PrefsFile(
        val name: String,
        /** Never exported: only meaningful on this phone. */
        val deviceOnlyKeys: Set<String> = emptySet(),
        /** Exported only when the user opts in. */
        val secretKeys: Set<String> = emptySet(),
        /** Like [deviceOnlyKeys], for keys starting with one of these. */
        val deviceOnlyPrefixes: Set<String> = emptySet()
    ) {
        fun isDeviceOnly(key: String) = key in deviceOnlyKeys || deviceOnlyPrefixes.any { key.startsWith(it) }
    }

    /**
     * Every SharedPreferences file the app owns. New features that store
     * something must add their file here (and to res/xml/data_extraction_rules.xml).
     * Not listed on purpose:
     * - dialer_widget: a signature of what the widget last drew (a cache).
     * - voicemail_transcripts: keyed by this phone's voicemail URIs; the app transcribes again.
     * - app_language: the language is exported on its own (see BackupData.language),
     *   because Android 13+ keeps it in the system, not in this file.
     */
    val PREFS = listOf(
        // Lookup URL may carry an API key in its query.
        PrefsFile("caller_id_prefs", secretKeys = setOf("lookup_endpoint")),
        PrefsFile("spam_reports"),
        PrefsFile("network_caller_names"),
        PrefsFile("speed_dial"),
        PrefsFile("call_notes"),
        PrefsFile("display_font"),
        PrefsFile("haptics_prefs"),
        PrefsFile("recording_prefs"),
        // A folder URI and its permission don't move between phones or installs: the user
        // picks the folder again. Heard marks are keyed by those URIs, so they stay too.
        PrefsFile("voicemail", deviceOnlyKeys = setOf("folder_uri", "heard")),
        // Pinned SIMs per contact. SIM account ids differ between phones, so pins just
        // don't match there; "last used" is this phone's history only.
        PrefsFile("smart_sim", deviceOnlyPrefixes = setOf("last.")),
        // The overlay permission it needs is granted in system settings, not stored here.
        PrefsFile("call_pill"),
        // "recent_incoming" is short-lived repeat-caller tracking.
        PrefsFile("quiet_hours", deviceOnlyKeys = setOf("recent_incoming")),
        // Alarms are set again after a restore (BackupManager).
        PrefsFile("call_reminders"),
        // Glyph light patterns, including per-contact ones ("contact_<number key>").
        PrefsFile("glyph_prefs"),
        // Dark / light / follow the phone; re-applied after a restore (BackupManager).
        PrefsFile("app_theme"),
        PrefsFile("flip_to_silence"),
        // The user's own balance/plan USSD codes per operator or SIM slot.
        PrefsFile("ussd_codes")
    )

    fun file(name: String): PrefsFile? = PREFS.firstOrNull { it.name == name }

    const val FONT_FILE = "custom_display_font"
    const val RECORDINGS_DIR = "recordings"
    const val MANIFEST = "backup.json"
    const val FONT_ENTRY = "files/$FONT_FILE"

    /** Lists kept as one JSON-array string, merged item by item on this id field. */
    val JSON_LISTS: Map<Pair<String, String>, String> = mapOf(
        ("call_notes" to "notes") to "key",
        ("quiet_hours" to "vip_numbers") to "number",
        ("call_reminders" to "items") to "id",
        ("recording_prefs" to "recordings_list_json") to "filePath"
    )

    const val RECORDINGS_INDEX_FILE = "recording_prefs"
    const val RECORDINGS_INDEX_KEY = "recordings_list_json"

    /** The values of [file] that go into a backup. */
    fun exportable(file: PrefsFile, values: Map<String, PrefValue>, includeSecrets: Boolean): Map<String, PrefValue> =
        values.filterKeys { key ->
            !file.isDeviceOnly(key) && (includeSecrets || key !in file.secretKeys)
        }

    /** e.g. NoDialer-backup-2026-10-26.zip */
    fun fileName(date: LocalDate): String = "NoDialer-backup-$date.zip"

    /** A zip entry name for a recording, or null when it could escape the folder. */
    fun safeRecordingName(entry: String): String? {
        val name = entry.removePrefix("$RECORDINGS_DIR/")
        if (name == entry || name.isEmpty() || name == "." || name == "..") return null
        if (name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 }) return null
        return name
    }
}

class BackupException(val reason: Reason, val version: Int? = null, cause: Throwable? = null) :
    Exception("Backup ${reason.name.lowercase()}" + (version?.let { " (version $it)" } ?: ""), cause) {
    enum class Reason { NOT_A_BACKUP, TOO_NEW, CORRUPT }
}

object BackupCodec {
    const val FORMAT = "nodialer-backup"
    /** Bump when the manifest changes shape, and teach [upgrade] to read the old one. */
    const val VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun encode(data: BackupData): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("createdAt", data.createdAt)
        root.addProperty("sourcePackage", data.sourcePackage)
        root.addProperty("appVersion", data.appVersion)
        root.addProperty("secretsIncluded", data.secretsIncluded)
        data.language?.let { root.addProperty("language", it) }
        root.add("files", JsonObject().apply {
            addProperty("font", data.hasFont)
            add("recordings", JsonArray().apply { data.recordings.forEach { add(it) } })
        })
        val prefs = JsonObject()
        data.prefs.toSortedMap().forEach { (file, values) ->
            val obj = JsonObject()
            values.toSortedMap().forEach { (key, value) -> obj.add(key, encodeValue(value)) }
            prefs.add(file, obj)
        }
        root.add("prefs", prefs)
        return gson.toJson(root)
    }

    /** Reads a manifest. Throws [BackupException] when it isn't one, is newer than this app, or is damaged. */
    fun decode(text: String): BackupData {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP, cause = e)
        }
        if (!root.isJsonObject) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val obj = root.asJsonObject
        if (obj.stringOrNull("format") != FORMAT) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val version = try {
            obj.get("version")?.asInt
        } catch (e: Exception) {
            null
        } ?: throw BackupException(BackupException.Reason.CORRUPT)
        if (version > VERSION) throw BackupException(BackupException.Reason.TOO_NEW, version)
        if (version < 1) throw BackupException(BackupException.Reason.CORRUPT, version)
        return try {
            read(upgrade(obj, version))
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupException.Reason.CORRUPT, version, e)
        }
    }

    /** Brings an older manifest up to [VERSION]. Version 1 is the first, so nothing to do yet. */
    private fun upgrade(obj: JsonObject, from: Int): JsonObject = obj

    private fun read(obj: JsonObject): BackupData {
        val files = obj.getAsJsonObject("files")
        val prefs = obj.getAsJsonObject("prefs")?.entrySet().orEmpty().associate { (file, values) ->
            file to values.asJsonObject.entrySet().associate { (key, value) -> key to decodeValue(value.asJsonObject) }
        }
        return BackupData(
            version = VERSION,
            createdAt = obj.get("createdAt")?.asLong ?: 0L,
            sourcePackage = obj.stringOrNull("sourcePackage").orEmpty(),
            appVersion = obj.stringOrNull("appVersion").orEmpty(),
            language = obj.stringOrNull("language"),
            prefs = prefs,
            hasFont = files?.get("font")?.asBoolean ?: false,
            recordings = files?.getAsJsonArray("recordings")?.map { it.asString }.orEmpty(),
            secretsIncluded = obj.get("secretsIncluded")?.asBoolean ?: false
        )
    }

    private fun encodeValue(value: PrefValue): JsonObject = JsonObject().apply {
        when (value) {
            is PrefValue.Bool -> { addProperty("type", "boolean"); addProperty("value", value.value) }
            is PrefValue.IntValue -> { addProperty("type", "int"); addProperty("value", value.value) }
            is PrefValue.LongValue -> { addProperty("type", "long"); addProperty("value", value.value) }
            is PrefValue.FloatValue -> { addProperty("type", "float"); addProperty("value", value.value) }
            is PrefValue.Str -> { addProperty("type", "string"); addProperty("value", value.value) }
            is PrefValue.StrSet -> {
                addProperty("type", "set")
                add("value", JsonArray().apply { value.value.sorted().forEach { add(it) } })
            }
        }
    }

    private fun decodeValue(obj: JsonObject): PrefValue {
        val v = obj.get("value")
        return when (val type = obj.stringOrNull("type")) {
            "boolean" -> PrefValue.Bool(v.asBoolean)
            "int" -> PrefValue.IntValue(v.asInt)
            "long" -> PrefValue.LongValue(v.asLong)
            "float" -> PrefValue.FloatValue(v.asFloat)
            "string" -> PrefValue.Str(v.asString)
            "set" -> PrefValue.StrSet(v.asJsonArray.map { it.asString }.toSet())
            else -> throw IllegalArgumentException("Unknown value type $type")
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && (it as JsonPrimitive).isString }?.asString
}

enum class RestoreMode { MERGE, REPLACE }

object BackupMerge {

    /**
     * The new contents of one prefs file.
     * REPLACE: the backup's values, plus this phone's device-only and secret
     * keys when the backup doesn't carry them.
     * MERGE: keeps everything here; adds what's missing; joins sets (blocked
     * numbers, rules) and JSON lists (notes, recordings) item by item.
     */
    fun merge(
        file: String,
        local: Map<String, PrefValue>,
        incoming: Map<String, PrefValue>,
        mode: RestoreMode
    ): Map<String, PrefValue> {
        val spec = BackupSpec.file(file)
        fun keepLocal(key: String) = spec != null && (spec.isDeviceOnly(key) || key in spec.secretKeys)
        if (mode == RestoreMode.REPLACE) {
            return incoming + local.filterKeys { keepLocal(it) && it !in incoming }
        }
        val result = local.toMutableMap()
        incoming.forEach { (key, value) ->
            val mine = result[key]
            result[key] = when {
                mine == null -> value
                mine is PrefValue.StrSet && value is PrefValue.StrSet -> PrefValue.StrSet(mine.value + value.value)
                mine is PrefValue.Str && value is PrefValue.Str ->
                    BackupSpec.JSON_LISTS[file to key]?.let { id -> PrefValue.Str(mergeJsonList(mine.value, value.value, id)) }
                        ?: mine
                else -> mine
            }
        }
        return result
    }

    /** Local items first, then backup items whose [idField] isn't here yet. Bad JSON on either side is skipped. */
    fun mergeJsonList(localJson: String, incomingJson: String, idField: String): String {
        val local = parseArray(localJson)
        val incoming = parseArray(incomingJson)
        val ids = local.mapNotNull { idOf(it, idField) }.toMutableSet()
        val out = JsonArray()
        local.forEach { out.add(it) }
        incoming.forEach { item ->
            val id = idOf(item, idField)
            if (id == null || ids.add(id)) out.add(item)
        }
        return out.toString()
    }

    /**
     * Points every recording in the index at [dir] (recordings from another
     * install live under another package's folder) and drops entries whose
     * audio file isn't on this phone.
     */
    fun rewriteRecordingPaths(json: String, dir: String, exists: (String) -> Boolean): String {
        val out = JsonArray()
        parseArray(json).forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: return@forEach
            val path = obj.get("filePath")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            if (name.isEmpty()) return@forEach
            val newPath = "${dir.trimEnd('/')}/$name"
            if (!exists(newPath)) return@forEach
            obj.addProperty("filePath", newPath)
            out.add(obj)
        }
        return out.toString()
    }

    private fun parseArray(json: String): List<JsonElement> = try {
        JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun idOf(item: JsonElement, field: String): String? =
        item.takeIf { it.isJsonObject }?.asJsonObject?.get(field)?.takeIf { it.isJsonPrimitive }?.asString
}

/** Counts shown before an import, e.g. "312 blocked numbers, 18 notes…". */
data class BackupSummary(
    val blockedNumbers: Int,
    val blockRules: Int,
    val spamReports: Int,
    val networkNames: Int,
    val speedDials: Int,
    val notes: Int,
    val recordingsIndexed: Int,
    val recordingFiles: Int,
    val hasFont: Boolean,
    val language: String?,
    val voicemailSettings: Boolean
) {
    companion object {
        fun of(data: BackupData): BackupSummary {
            fun file(name: String) = data.prefs[name].orEmpty()
            fun setSize(file: String, key: String) = (file(file)[key] as? PrefValue.StrSet)?.value?.size ?: 0
            fun listSize(file: String, key: String) = (file(file)[key] as? PrefValue.Str)?.value?.let { json ->
                try {
                    JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray?.size()
                } catch (e: Exception) {
                    null
                }
            } ?: 0
            return BackupSummary(
                blockedNumbers = setSize("caller_id_prefs", "blocked_numbers"),
                blockRules = setSize("caller_id_prefs", "prefix_rules"),
                spamReports = file("spam_reports").size,
                networkNames = file("network_caller_names").size,
                speedDials = file("speed_dial").keys.count { it.startsWith("key_") },
                notes = listSize("call_notes", "notes"),
                recordingsIndexed = listSize("recording_prefs", "recordings_list_json"),
                recordingFiles = data.recordings.size,
                hasFont = data.hasFont,
                language = data.language,
                voicemailSettings = file("voicemail").isNotEmpty()
            )
        }
    }
}
