package com.example.allinonedailer.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.allinonedailer.R

/** One audio file in the voicemail folder. */
data class VoicemailFile(
    val uri: Uri,
    val fileName: String,
    val mimeType: String?,
    /** From the file name when it has a date and time, else the file's last-modified time. */
    val timestamp: Long,
    val durationMs: Long,
    /** A phone number found in the file name, or null. */
    val phoneNumber: String?,
    /** The file's own last-modified time (0 when unknown); transcripts are cached against it. */
    val lastModified: Long = 0L
)

/**
 * Voicemail inbox backed by a folder the user picks with the Storage Access
 * Framework. The app never records calls for this: the files come from a
 * call-forwarding voicemail service or are saved by the user.
 */
class VoicemailRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("voicemail", Context.MODE_PRIVATE)

    fun getFolderUri(): Uri? = prefs.getString(KEY_FOLDER, null)?.let { Uri.parse(it) }

    /** True when a folder is chosen and the app still holds read access to it. */
    fun hasFolderAccess(): Boolean {
        val folder = getFolderUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isReadPermission }
    }

    fun setFolder(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        getFolderUri()?.takeIf { it != treeUri }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.edit().putString(KEY_FOLDER, treeUri.toString()).apply()
    }

    /** Readable folder name, e.g. "Download/Voicemail". */
    fun folderLabel(): String? {
        val folder = getFolderUri() ?: return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull() ?: return null
        return docId.substringAfter(':', docId).ifBlank { context.getString(R.string.internal_storage) }
    }

    fun getServiceNumber(): String = prefs.getString(KEY_SERVICE_NUMBER, "").orEmpty()
    fun setServiceNumber(number: String) = prefs.edit().putString(KEY_SERVICE_NUMBER, number).apply()

    fun isHeard(uri: Uri): Boolean = heardSet().contains(uri.toString())

    fun markHeard(uri: Uri) {
        val set = heardSet()
        if (set.add(uri.toString())) prefs.edit().putStringSet(KEY_HEARD, set).apply()
    }

    private fun heardSet(): MutableSet<String> = prefs.getStringSet(KEY_HEARD, emptySet()).orEmpty().toMutableSet()

    /** Audio files in the folder, newest first. Blocking: call off the main thread. */
    fun listVoicemails(): List<VoicemailFile> {
        if (!hasFolderAccess()) return emptyList()
        val tree = getFolderUri() ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<VoicemailFile>()
        try {
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue
                    val modified = if (c.isNull(3)) 0L else c.getLong(3)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    result += VoicemailFile(
                        uri = uri,
                        fileName = name,
                        mimeType = mime,
                        timestamp = timestampFromName(name) ?: modified,
                        durationMs = durationOf(uri, modified),
                        phoneNumber = numberFromName(name),
                        lastModified = modified
                    )
                }
            }
        } catch (e: Exception) {
            // Folder moved or access revoked: show it as empty.
        }
        return result.sortedByDescending { it.timestamp }
    }

    /** Files not yet played. Blocking. */
    fun unheardCount(): Int {
        val heard = heardSet()
        return listVoicemails().count { it.uri.toString() !in heard }
    }

    fun delete(file: VoicemailFile): Boolean {
        val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, file.uri) }.getOrDefault(false)
        if (ok) {
            val set = heardSet()
            if (set.remove(file.uri.toString())) prefs.edit().putStringSet(KEY_HEARD, set).apply()
            durationCache.remove(file.uri.toString())
        }
        return ok
    }

    private fun durationOf(uri: Uri, modified: Long): Long {
        val key = uri.toString()
        durationCache[key]?.let { (stamp, ms) -> if (stamp == modified) return ms }
        val ms = MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                runCatching { r.release() }
            }
        }
        durationCache[key] = modified to ms
        return ms
    }

    companion object {
        private const val KEY_FOLDER = "folder_uri"
        private const val KEY_HEARD = "heard"
        private const val KEY_SERVICE_NUMBER = "service_number"

        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "amr", "ogg", "opus")

        /** Duration per document URI, with the last-modified time it was read at. */
        private val durationCache = mutableMapOf<String, Pair<Long, Long>>()

        private val DATE_TIME = Regex("""(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})[-_ T]?(\d{2})[-_.:]?(\d{2})(?:[-_.:]?(\d{2}))?""")

        /**
         * A phone number in a file name such as "voicemail_+919876543210_20261025-1430.m4a"
         * or "9876543210.mp3". Date-like digit runs are skipped.
         */
        fun numberFromName(fileName: String): String? {
            val base = fileName.substringBeforeLast('.')
            val withoutDates = DATE_TIME.replace(base, " ")
            val candidates = Regex("""\+?\d[\d\- ]{6,16}\d""").findAll(withoutDates).map { it.value.trim() }
            for (raw in candidates) {
                val digits = raw.filter { it.isDigit() }
                val plus = raw.startsWith("+")
                val ok = when {
                    plus -> digits.length in 8..15
                    digits.length == 10 -> digits[0] in '6'..'9' || digits[0] == '0'
                    digits.length == 11 -> digits.startsWith("0")
                    digits.length == 12 -> digits.startsWith("91")
                    else -> false
                }
                if (ok) return if (plus) "+$digits" else digits
            }
            return null
        }

        /** Date and time in a file name (yyyyMMdd HHmm[ss], with optional separators). */
        fun timestampFromName(fileName: String): Long? {
            val m = DATE_TIME.find(fileName) ?: return null
            val (y, mo, d, h, mi) = m.destructured
            val s = m.groupValues[6].ifEmpty { "00" }
            return runCatching {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }
                    .parse("$y$mo$d$h$mi$s")?.time
            }.getOrNull()
        }
    }
}
