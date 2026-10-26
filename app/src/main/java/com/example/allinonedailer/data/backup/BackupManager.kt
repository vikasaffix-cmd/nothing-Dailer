package com.example.allinonedailer.data.backup

import android.app.LocaleManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.util.Log
import com.example.allinonedailer.data.AppFont
import com.example.allinonedailer.data.AppFontStyle
import com.example.allinonedailer.data.AppLanguageSetting
import com.example.allinonedailer.data.AppTheme
import com.example.allinonedailer.data.CallNotesRepository
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.data.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Settings → Backup & restore: writes and reads the zip described in
 * BackupFormat.kt through Storage Access Framework URIs. Every call blocks on
 * IO and runs on Dispatchers.IO.
 */
object BackupManager {

    private const val TAG = "Backup"
    /** The debug build's package, before the app became com.maihuvikas.dialer. */
    const val LEGACY_PACKAGE = "com.example.allinonedailer"
    /** A manifest bigger than this isn't ours. */
    private const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024

    /** A backup read and checked, ready to show its summary. */
    data class Loaded(val data: BackupData, val summary: BackupSummary, val zipped: Boolean) {
        val fromOldApp get() = data.sourcePackage == LEGACY_PACKAGE
    }

    data class RestoreResult(
        val fontRestored: Boolean,
        val recordingsRestored: Int,
        /** Before Android 13 the activity must be recreated to show the restored language. */
        val needsRecreate: Boolean
    )

    fun recordingsDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, BackupSpec.RECORDINGS_DIR) }

    /** Recording files on this phone, for the export option's size hint. */
    fun recordingFiles(context: Context): List<File> =
        recordingsDir(context)?.listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty()

    suspend fun export(
        context: Context,
        target: Uri,
        includeSecrets: Boolean,
        includeRecordings: Boolean
    ): BackupSummary = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val font = AppFont.customFontFile(app).takeIf { it.length() > 0 }
        val recordings = if (includeRecordings) recordingFiles(app) else emptyList()
        val data = BackupData(
            createdAt = System.currentTimeMillis(),
            sourcePackage = app.packageName,
            appVersion = versionName(app),
            language = AppLanguageSetting.current(app).tag,
            prefs = BackupSpec.PREFS
                .associate { spec -> spec.name to BackupSpec.exportable(spec, readPrefs(app, spec.name), includeSecrets) }
                .filterValues { it.isNotEmpty() },
            hasFont = font != null,
            recordings = recordings.map { it.name },
            secretsIncluded = includeSecrets
        )
        val out = app.contentResolver.openOutputStream(target, "w") ?: throw IOException("Can't open $target")
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupSpec.MANIFEST))
            zip.write(BackupCodec.encode(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            font?.let { file ->
                zip.putNextEntry(ZipEntry(BackupSpec.FONT_ENTRY))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            // Audio is already compressed; deflating it again only costs time.
            zip.setLevel(Deflater.NO_COMPRESSION)
            recordings.forEach { file ->
                zip.putNextEntry(ZipEntry("${BackupSpec.RECORDINGS_DIR}/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        BackupSummary.of(data)
    }

    /** Reads and checks a backup without changing anything. Throws [BackupException]. */
    suspend fun load(context: Context, source: Uri): Loaded = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val input = resolver.openInputStream(source) ?: throw IOException("Can't open $source")
        BufferedInputStream(input).use { stream ->
            stream.mark(4)
            val magic = ByteArray(2).also { stream.read(it) }
            stream.reset()
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                var manifest: String? = null
                val names = mutableSetOf<String>()
                ZipInputStream(stream).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        names += entry.name
                        if (entry.name == BackupSpec.MANIFEST) manifest = readText(zip)
                    }
                }
                val data = BackupCodec.decode(manifest ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP))
                // Trust only what is really in the zip.
                val checked = data.copy(
                    hasFont = data.hasFont && BackupSpec.FONT_ENTRY in names,
                    recordings = data.recordings.filter { "${BackupSpec.RECORDINGS_DIR}/$it" in names }
                )
                Loaded(checked, BackupSummary.of(checked), zipped = true)
            } else {
                val data = BackupCodec.decode(readText(stream)).copy(hasFont = false, recordings = emptyList())
                Loaded(data, BackupSummary.of(data), zipped = false)
            }
        }
    }

    suspend fun restore(
        context: Context,
        source: Uri,
        loaded: Loaded,
        mode: RestoreMode,
        restoreRecordings: Boolean
    ): RestoreResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val data = loaded.data

        // 1. Files first, so the recordings index below can see which audio is here.
        var fontRestored = false
        var recordingsRestored = 0
        val wantFont = data.hasFont && (mode == RestoreMode.REPLACE || !AppFont.hasCustomFont(app))
        val wantRecordings = restoreRecordings && data.recordings.isNotEmpty()
        if (loaded.zipped && (wantFont || wantRecordings)) {
            val input = app.contentResolver.openInputStream(source) ?: throw IOException("Can't open $source")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    if (entry.name == BackupSpec.FONT_ENTRY && wantFont) {
                        fontRestored = restoreFont(app, zip)
                    } else if (wantRecordings) {
                        val name = BackupSpec.safeRecordingName(entry.name) ?: return@forEach
                        if (restoreRecording(app, name, zip)) recordingsRestored++
                    }
                }
            }
        }

        // 2. Preferences. Files the backup lacks are empty in it, which matters for REPLACE.
        val remindersBefore = CallReminders.load(app)
        val recDir = recordingsDir(app)
        BackupSpec.PREFS.forEach { spec ->
            var incoming = data.prefs[spec.name].orEmpty()
            if (mode == RestoreMode.MERGE && incoming.isEmpty()) return@forEach
            if (spec.name == BackupSpec.RECORDINGS_INDEX_FILE) {
                val index = incoming[BackupSpec.RECORDINGS_INDEX_KEY] as? PrefValue.Str
                if (index != null) {
                    val fixed = if (recDir == null) "[]"
                    else BackupMerge.rewriteRecordingPaths(index.value, recDir.path) { File(it).exists() }
                    incoming = incoming + (BackupSpec.RECORDINGS_INDEX_KEY to PrefValue.Str(fixed))
                }
            }
            val merged = BackupMerge.merge(spec.name, readPrefs(app, spec.name), incoming, mode)
            writePrefs(app, spec.name, merged)
        }

        // A custom font chosen without its file would draw nothing useful.
        val fontStyle = (readPrefs(app, "display_font")["style"] as? PrefValue.Str)?.value
        if (fontStyle == AppFontStyle.CUSTOM.name && !AppFont.hasCustomFont(app)) {
            writePrefs(app, "display_font", mapOf("style" to PrefValue.Str(AppFontStyle.SYCHIOV_DOT.name)))
        }

        // 3. Reload what's held in memory, so no restart is needed.
        CallNotesRepository.reload(app)
        // Alarms live outside prefs: drop ones for reminders that are gone, set the rest.
        val reminderIds = CallReminders.load(app).map { it.id }.toSet()
        remindersBefore.filter { it.id !in reminderIds }.forEach { ReminderScheduler.cancel(app, it.id) }
        CallReminders.rescheduleAll(app)
        val needsRecreate = withContext(Dispatchers.Main) {
            AppFont.reload(app)
            AppTheme.reload(app)
            restoreLanguage(app, data.language, mode)
        }
        RestoreResult(fontRestored, recordingsRestored, needsRecreate)
    }

    /** Applies the backup's language. Returns true when the caller must recreate the activity. */
    private fun restoreLanguage(context: Context, tag: String?, mode: RestoreMode): Boolean {
        if (tag == null) return false
        val current = AppLanguageSetting.current(context).tag
        if (tag == current) return false
        // Merge keeps a language picked on this phone.
        if (mode == RestoreMode.MERGE && current.isNotEmpty()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity itself.
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            false
        } else {
            context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("tag", tag).commit()
            true
        }
    }

    private fun restoreFont(context: Context, input: InputStream): Boolean {
        val temp = File(context.filesDir, "${BackupSpec.FONT_FILE}.restore")
        return try {
            temp.outputStream().use { input.copyTo(it) }
            Typeface.createFromFile(temp) // throws if it isn't a font
            val target = AppFont.customFontFile(context)
            target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Backup font isn't usable", e)
            temp.delete()
            false
        }
    }

    /** Copies one recording in; never overwrites a file already here. */
    private fun restoreRecording(context: Context, name: String, input: InputStream): Boolean {
        val dir = recordingsDir(context) ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = File(dir, name)
        if (target.exists()) return false
        val temp = File(dir, "$name.part")
        return try {
            temp.outputStream().use { input.copyTo(it) }
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't restore recording $name", e)
            temp.delete()
            false
        }
    }

    private fun readPrefs(context: Context, name: String): Map<String, PrefValue> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            .mapNotNull { (key, raw) -> PrefValue.of(raw)?.let { key to it } }
            .toMap()

    private fun writePrefs(context: Context, name: String, values: Map<String, PrefValue>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is PrefValue.Bool -> editor.putBoolean(key, value.value)
                is PrefValue.IntValue -> editor.putInt(key, value.value)
                is PrefValue.LongValue -> editor.putLong(key, value.value)
                is PrefValue.FloatValue -> editor.putFloat(key, value.value)
                is PrefValue.Str -> editor.putString(key, value.value)
                is PrefValue.StrSet -> editor.putStringSet(key, value.value)
            }
        }
        editor.commit()
    }

    private fun readText(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_MANIFEST_BYTES) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
            bytes.write(buffer, 0, n)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
