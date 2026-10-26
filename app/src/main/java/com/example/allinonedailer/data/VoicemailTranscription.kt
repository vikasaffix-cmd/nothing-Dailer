package com.example.allinonedailer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Transcribe voicemails": the Settings switch, and the background work that
 * fills [TranscriptCache] with an on-device transcript and summary per file.
 */
object VoicemailTranscription {
    private const val PREFS = "voicemail"
    private const val KEY_ENABLED = "transcribe_enabled"
    private const val WORK_ALL = "voicemail-transcribe"
    internal const val KEY_URI = "uri"
    internal const val KEY_LANGUAGE = "language"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) enqueueAll(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK_ALL)
    }

    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Switch on, an on-device recogniser present, and the permission Android's recogniser asks for. */
    fun canRun(context: Context): Boolean =
        isEnabled(context) && OnDeviceTranscriber.isSupported(context) && hasMicPermission(context)

    /** Transcribes files that have no transcript yet, when the switch is on. Safe to call often. */
    fun enqueueAll(context: Context) {
        if (!canRun(context)) return
        val request = OneTimeWorkRequestBuilder<VoicemailTranscribeWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ALL, ExistingWorkPolicy.KEEP, request)
    }

    /** Transcribes one file now (switch on or off), optionally in a chosen language ("en", "hi", "mr"). */
    fun retry(context: Context, file: VoicemailFile, language: String?) {
        val key = file.uri.toString()
        TranscriptCache.create(context).markPending(key, file.lastModified, language)
        val request = OneTimeWorkRequestBuilder<VoicemailTranscribeWorker>()
            .setInputData(workDataOf(KEY_URI to key, KEY_LANGUAGE to language))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("$WORK_ALL:${key.hashCode()}", ExistingWorkPolicy.REPLACE, request)
    }

    /** Transcribes and summarises one file into a cache entry. */
    internal suspend fun process(
        transcriber: OnDeviceTranscriber,
        file: VoicemailFile,
        language: String?,
        knownName: String?
    ): VoicemailTranscript {
        val base = VoicemailTranscript(
            status = TranscriptStatus.FAILED,
            sourceModified = file.lastModified,
            updatedAt = System.currentTimeMillis(),
            requestedLanguage = language
        )
        return when (val r = transcriber.transcribe(file.uri, language)) {
            is OnDeviceTranscriber.Outcome.Text -> {
                val s = VoicemailSummarizer.summarize(r.text, r.language, knownName, file.timestamp)
                base.copy(
                    status = TranscriptStatus.DONE,
                    language = r.language,
                    text = r.text,
                    summary = s?.line,
                    reminderStart = s?.reminder?.startMillis,
                    reminderAllDay = s?.reminder?.allDay == true,
                    updatedAt = System.currentTimeMillis()
                )
            }
            OnDeviceTranscriber.Outcome.NoSpeech -> base.copy(status = TranscriptStatus.NO_SPEECH)
            OnDeviceTranscriber.Outcome.Unsupported -> base.copy(status = TranscriptStatus.UNSUPPORTED)
            OnDeviceTranscriber.Outcome.NeedsPermission -> base.copy(status = TranscriptStatus.NEEDS_PERMISSION)
            is OnDeviceTranscriber.Outcome.NoLanguagePack -> base.copy(status = TranscriptStatus.NO_LANGUAGE_PACK, language = r.language)
            OnDeviceTranscriber.Outcome.Failed -> base
        }
    }
}

/** Runs [VoicemailTranscription]: every untranscribed file, or the one file in its input. */
class VoicemailTranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val only = inputData.getString(VoicemailTranscription.KEY_URI)
        // The batch waits until the phone can do it; a retry runs anyway so the row shows why not.
        if (only == null && !VoicemailTranscription.canRun(context)) return Result.success()

        val repo = VoicemailRepository(context)
        val cache = TranscriptCache.create(context)
        val files = withContext(Dispatchers.IO) {
            if (!repo.hasFolderAccess()) return@withContext null
            repo.listVoicemails()
        } ?: return Result.success()
        if (only == null) cache.prune(files.map { it.uri.toString() })

        val transcriber = OnDeviceTranscriber(context)
        val callers = CallerIdRepository(context)
        for (file in files) {
            if (isStopped) break
            val key = file.uri.toString()
            val language = if (only != null) {
                if (key != only) continue
                inputData.getString(VoicemailTranscription.KEY_LANGUAGE)
            } else {
                // A retry may have finished it while this batch was running.
                if (!cache.needsWork(key, file.lastModified)) continue
                cache.get(key, file.lastModified)?.requestedLanguage
            }
            cache.markPending(key, file.lastModified, language)
            val name = withContext(Dispatchers.IO) {
                file.phoneNumber?.let { runCatching { callers.lookupCallerInfo(it).name }.getOrNull() }
            }
            cache.put(key, VoicemailTranscription.process(transcriber, file, language, name))
        }
        return Result.success()
    }
}
