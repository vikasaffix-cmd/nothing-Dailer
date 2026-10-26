package com.example.allinonedailer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TranscriptStatus {
    /** Queued or being transcribed. */
    PENDING,
    DONE,
    /** The recogniser heard nothing it could write down. */
    NO_SPEECH,
    FAILED,
    /** Android 12 or older, or no on-device recogniser on this phone. */
    UNSUPPORTED,
    /** The offline speech pack for the language isn't installed (a download may have started). */
    NO_LANGUAGE_PACK,
    /** Android's recogniser wants the microphone permission, even for files. */
    NEEDS_PERMISSION
}

/** The transcript and summary of one voicemail file, cached by document URI. */
data class VoicemailTranscript(
    val status: TranscriptStatus,
    /** The file's last-modified time when it was transcribed; a different time means the file changed. */
    val sourceModified: Long,
    val updatedAt: Long,
    /** "en", "hi" or "mr". */
    val language: String? = null,
    val text: String = "",
    val summary: String? = null,
    val reminderStart: Long? = null,
    val reminderAllDay: Boolean = false,
    /** The language picked on a retry, or null to detect it. */
    val requestedLanguage: String? = null
)

/** One line per transcript, fields split by the ASCII unit separator (never in speech). */
object TranscriptCodec {
    private const val VERSION = "1"
    private const val SEP = '\u001F'

    fun encode(t: VoicemailTranscript): String = listOf(
        VERSION,
        t.status.name,
        t.sourceModified.toString(),
        t.updatedAt.toString(),
        t.language.orEmpty(),
        t.requestedLanguage.orEmpty(),
        t.reminderStart?.toString().orEmpty(),
        if (t.reminderAllDay) "1" else "0",
        clean(t.summary.orEmpty()),
        clean(t.text)
    ).joinToString(SEP.toString())

    fun decode(s: String): VoicemailTranscript? {
        val f = s.split(SEP)
        if (f.size != 10 || f[0] != VERSION) return null
        val status = TranscriptStatus.entries.firstOrNull { it.name == f[1] } ?: return null
        return VoicemailTranscript(
            status = status,
            sourceModified = f[2].toLongOrNull() ?: return null,
            updatedAt = f[3].toLongOrNull() ?: 0L,
            language = f[4].ifEmpty { null },
            requestedLanguage = f[5].ifEmpty { null },
            reminderStart = f[6].toLongOrNull(),
            reminderAllDay = f[7] == "1",
            summary = f[8].ifEmpty { null },
            text = f[9]
        )
    }

    private fun clean(s: String) = s.replace(SEP, ' ')
}

/** Where the cache keeps its lines; SharedPreferences on the phone, a map in tests. */
interface TranscriptStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/**
 * Transcripts keyed by document URI and checked against the file's
 * last-modified time, so an edited or replaced file is transcribed again.
 */
class TranscriptCache(
    private val store: TranscriptStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val onChange: () -> Unit = {}
) {
    /** The transcript for this version of the file, or null. */
    fun get(uri: String, lastModified: Long): VoicemailTranscript? =
        store.read(uri)?.let(TranscriptCodec::decode)?.takeIf { it.sourceModified == lastModified }

    /**
     * True when the file has no transcript for its current version, or one
     * was started so long ago that the work must have died. Failures wait
     * for a manual retry.
     */
    fun needsWork(uri: String, lastModified: Long): Boolean {
        val t = get(uri, lastModified) ?: return true
        return t.status == TranscriptStatus.PENDING && now() - t.updatedAt > STALE_PENDING_MS
    }

    fun markPending(uri: String, lastModified: Long, requestedLanguage: String? = null) {
        put(
            uri,
            VoicemailTranscript(
                status = TranscriptStatus.PENDING,
                sourceModified = lastModified,
                updatedAt = now(),
                requestedLanguage = requestedLanguage
            )
        )
    }

    fun put(uri: String, transcript: VoicemailTranscript) {
        store.write(uri, TranscriptCodec.encode(transcript))
        onChange()
    }

    fun remove(uri: String) {
        store.remove(uri)
        onChange()
    }

    /** Drops transcripts of files no longer in the folder; returns how many. */
    fun prune(keep: Collection<String>): Int {
        val gone = store.keys() - keep.toSet()
        gone.forEach(store::remove)
        if (gone.isNotEmpty()) onChange()
        return gone.size
    }

    companion object {
        const val STALE_PENDING_MS = 15 * 60_000L

        private val _version = MutableStateFlow(0)

        /** Bumped on every write, so an open Voicemail screen re-reads. */
        val version: StateFlow<Int> = _version

        fun create(context: Context): TranscriptCache {
            val prefs = context.applicationContext.getSharedPreferences("voicemail_transcripts", Context.MODE_PRIVATE)
            val store = object : TranscriptStore {
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun remove(key: String) = prefs.edit().remove(key).apply()
                override fun keys(): Set<String> = prefs.all.keys
            }
            return TranscriptCache(store, onChange = { _version.value++ })
        }
    }
}

/** Voicemail search over name, number, file name, summary and transcript. */
object VoicemailSearch {
    private val SPACES = Regex("""\s+""")

    /**
     * True when every word of [query] is in one of [fields]. Case, Devanagari
     * digits and nukta spellings are ignored; a run of 3+ digits also matches
     * a number written with spaces or dashes.
     */
    fun matches(query: String, vararg fields: String?): Boolean {
        val terms = VoicemailSummarizer.normalize(query).replace("़", "").trim().split(SPACES).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString("\n") { VoicemailSummarizer.normalize(it).replace("़", "") }
        val digits = haystack.filter { it.isDigit() }
        return terms.all { term ->
            term in haystack || (term.length >= 3 && term.all { it.isDigit() } && term in digits)
        }
    }
}
