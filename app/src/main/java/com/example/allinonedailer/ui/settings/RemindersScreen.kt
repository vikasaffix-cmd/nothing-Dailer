package com.example.allinonedailer.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.data.ReminderScheduler
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.service.ScheduledCallNotifier
import com.example.allinonedailer.ui.reminders.ReminderPickerDialog
import com.example.allinonedailer.ui.reminders.ScheduleCallDialog
import com.example.allinonedailer.ui.reminders.scheduledSummary
import com.example.allinonedailer.ui.reminders.toastScheduledCall
import com.example.allinonedailer.ui.reminders.formatReminderTime
import com.example.allinonedailer.ui.reminders.toastScheduled
import com.example.allinonedailer.ui.theme.NothingRed

/** Settings → Reminders: upcoming call-back reminders; tap to change the time, × to cancel. */
@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CallReminders.load(context) }
    val reminders by CallReminders.reminders.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CallReminder?>(null) }
    var editingScheduled by remember { mutableStateOf<CallReminder?>(null) }

    // Re-checked on resume: the user may have just allowed exact alarms.
    var exact by remember { mutableStateOf(ReminderScheduler.canScheduleExact(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = ReminderScheduler.canScheduleExact(context)
                if (now && !exact) CallReminders.rescheduleAll(context)
                exact = now
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.reminders_title), pitch = 4.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.reminders_help),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = stringResource(R.string.reminders_inexact_note),
                        fontSize = 12.sp,
                        color = NothingRed
                    )
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }) {
                        Text(stringResource(R.string.reminders_allow_exact).uppercase(), letterSpacing = 1.sp, fontSize = 12.sp)
                    }
                }
            }

            // Scheduled calls: full-screen alert option, then the list.
            var fullScreen by remember { mutableStateOf(ScheduledCallNotifier.fullScreen(context)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(text = stringResource(R.string.scheduled_full_screen), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        text = stringResource(
                            if (fullScreen && !ScheduledCallNotifier.canUseFullScreen(context)) R.string.scheduled_full_screen_blocked
                            else R.string.scheduled_full_screen_summary
                        ),
                        fontSize = 13.sp,
                        color = if (fullScreen && !ScheduledCallNotifier.canUseFullScreen(context)) NothingRed
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = fullScreen,
                    onCheckedChange = {
                        fullScreen = it
                        ScheduledCallNotifier.setFullScreen(context, it)
                        if (it && !ScheduledCallNotifier.canUseFullScreen(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                )
            }

            val scheduled = reminders.filter { it.scheduled }
            val callBacks = reminders.filterNot { it.scheduled }

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            if (reminders.isEmpty()) {
                Text(
                    text = stringResource(R.string.reminders_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            if (scheduled.isNotEmpty()) {
                SectionHeader(stringResource(R.string.reminders_scheduled_section))
                scheduled.forEach { reminder ->
                    ReminderRow(
                        reminder = reminder,
                        detail = scheduledSummary(context, reminder),
                        cancelLabel = stringResource(R.string.scheduled_call_cancel),
                        onClick = { editingScheduled = reminder },
                        onCancel = { CallReminders.cancel(context, reminder.id) }
                    )
                }
            }
            if (callBacks.isNotEmpty()) {
                if (scheduled.isNotEmpty()) SectionHeader(stringResource(R.string.reminders_callback_section))
                callBacks.forEach { reminder ->
                    ReminderRow(
                        reminder = reminder,
                        detail = formatReminderTime(context, reminder.at),
                        cancelLabel = stringResource(R.string.reminders_cancel),
                        onClick = { editing = reminder },
                        onCancel = { CallReminders.cancel(context, reminder.id) }
                    )
                }
            }
        }
    }

    editingScheduled?.let { reminder ->
        ScheduleCallDialog(
            title = reminder.title(),
            subtitle = reminder.number.takeIf { reminder.name != null },
            initial = reminder,
            onDismiss = { editingScheduled = null },
            onSave = { at, repeat ->
                CallReminders.updateScheduled(context, reminder.id, at, repeat)
                toastScheduledCall(context, at)
                editingScheduled = null
            }
        )
    }

    editing?.let { reminder ->
        ReminderPickerDialog(
            title = reminder.title(),
            subtitle = formatReminderTime(context, reminder.at),
            onDismiss = { editing = null },
            onPick = { at ->
                CallReminders.reschedule(context, reminder.id, at)
                toastScheduled(context, at)
                editing = null
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/** One reminder: name, when (and the number under a saved name); tap to change, × to cancel. */
@Composable
private fun ReminderRow(
    reminder: CallReminder,
    detail: String,
    cancelLabel: String,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reminder.title().uppercase(),
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = listOfNotNull(detail, reminder.number.takeIf { reminder.name != null }).joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, cancelLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
