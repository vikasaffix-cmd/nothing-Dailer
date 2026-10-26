package com.example.allinonedailer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.TimeUnit

class DialerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DialerWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdates.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdates.cancel(context)
    }
}

/**
 * When the widget refreshes: after every call (CallService), when starred
 * contacts or the call log change (a WorkManager content trigger), and every
 * 30 minutes so "Today"/"Yesterday" labels stay right.
 */
object WidgetUpdates {
    private const val PERIODIC = "dialer_widget_periodic"
    private const val CONTENT = "dialer_widget_content"
    private const val AFTER_CALL = "dialer_widget_after_call"

    fun hasWidgets(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, DialerWidgetReceiver::class.java)).isNotEmpty()

    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
        )
        work.enqueueUniqueWork(CONTENT, ExistingWorkPolicy.KEEP, contentTriggerRequest())
    }

    fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        listOf(PERIODIC, CONTENT, AFTER_CALL).forEach { work.cancelUniqueWork(it) }
    }

    /** A call just ended; Telecom writes its call-log row a moment later, so wait briefly. */
    fun onCallEnded(context: Context) {
        if (!hasWidgets(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            AFTER_CALL,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(2, TimeUnit.SECONDS).build()
        )
    }

    /** Content triggers fire once, so the worker queues the next one behind itself. */
    internal fun rearmContentTrigger(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CONTENT, ExistingWorkPolicy.APPEND_OR_REPLACE, contentTriggerRequest())
    }

    private fun contentTriggerRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WidgetContentWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(ContactsContract.Contacts.CONTENT_URI, true)
                    .addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(Duration.ofSeconds(3))
                    .setTriggerContentMaxDelay(Duration.ofSeconds(30))
                    .build()
            )
            .build()
}

/** Reloads the widget's data and redraws every placed widget. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!WidgetUpdates.hasWidgets(applicationContext)) return Result.success()
        WidgetData.refresh(applicationContext)
        DialerWidget().updateAll(applicationContext)
        // Self-heal the content trigger if it was dropped (e.g. after a force stop).
        WidgetUpdates.schedule(applicationContext)
        return Result.success()
    }
}

/**
 * Runs when contacts or the call log change. Contact syncs touch the provider
 * often, so it only redraws when the starred list or the newest call changed.
 */
class WidgetContentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!WidgetUpdates.hasWidgets(context)) return Result.success()
        try {
            if (withContext(Dispatchers.IO) { WidgetData.hasChanged(context) }) {
                WidgetData.refresh(context)
                DialerWidget().updateAll(context)
            }
        } finally {
            WidgetUpdates.rearmContentTrigger(context)
        }
        return Result.success()
    }
}
