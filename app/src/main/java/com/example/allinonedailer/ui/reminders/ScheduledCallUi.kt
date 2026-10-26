package com.example.allinonedailer.ui.reminders

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.ReminderRepeat
import com.example.allinonedailer.data.ReminderScheduler
import com.example.allinonedailer.data.ReminderTimes
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.theme.NothingRed
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val Panel = Color(0xFF1C1C1C)
private val Pill = Color(0xFF2C2D30)

/** "Tomorrow 18:00" once, "Every day, 18:00" or "Every Tue, 18:00". */
fun scheduledSummary(context: Context, reminder: CallReminder): String {
    val time = DateFormat.getTimeFormat(context).format(reminder.at)
    return when (reminder.repeat) {
        ReminderRepeat.ONCE -> formatReminderTime(context, reminder.at)
        ReminderRepeat.DAILY -> context.getString(R.string.scheduled_repeat_daily_summary, time)
        ReminderRepeat.WEEKLY -> context.getString(
            R.string.scheduled_repeat_weekly_summary, DateFormat.format("EEE", reminder.at).toString(), time
        )
    }
}

fun toastScheduledCall(context: Context, at: Long) {
    val text = context.getString(R.string.scheduled_call_set_for, formatReminderTime(context, at)) +
        if (ReminderScheduler.canScheduleExact(context)) "" else "\n" + context.getString(R.string.reminder_may_be_late)
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/** Stores a new scheduled call, sets its alarm and says when it will come. */
fun scheduleCall(context: Context, number: String, name: String?, at: Long, repeat: ReminderRepeat): CallReminder {
    val reminder = CallReminders.addScheduled(context.applicationContext, number, name, at, repeat)
    toastScheduledCall(context, at)
    return reminder
}

@Composable
private fun repeatLabel(repeat: ReminderRepeat): String = stringResource(
    when (repeat) {
        ReminderRepeat.ONCE -> R.string.scheduled_repeat_once
        ReminderRepeat.DAILY -> R.string.scheduled_repeat_daily
        ReminderRepeat.WEEKLY -> R.string.scheduled_repeat_weekly
    }
)

/**
 * "Schedule call": a day (today, tomorrow or a date), a time and a repeat
 * (once, daily, weekly). [initial] edits an existing scheduled call. [onSave]
 * gets the first time it fires; a one-off time in the past is refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleCallDialog(
    title: String,
    subtitle: String?,
    initial: CallReminder? = null,
    onDismiss: () -> Unit,
    onSave: (at: Long, repeat: ReminderRepeat) -> Unit
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val start = remember {
        // A new call starts on the next full hour.
        val at = initial?.at ?: (System.currentTimeMillis() / 3_600_000L + 1) * 3_600_000L
        Instant.ofEpochMilli(at).atZone(zone)
    }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(LocalTime.of(start.hour, start.minute)) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: ReminderRepeat.ONCE) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    fun clock(t: LocalTime): String =
        DateFormat.getTimeFormat(context).format(today.atTime(t).atZone(zone).toInstant().toEpochMilli())

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Panel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.scheduled_call_schedule).uppercase(), pitch = 3.dp, color = Color.White)
            Text(text = title, fontSize = 15.sp, color = Color.White, maxLines = 1)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }

            SectionLabel(stringResource(R.string.scheduled_call_day))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip(stringResource(R.string.scheduled_call_today), date == today) { date = today }
                Chip(stringResource(R.string.scheduled_call_tomorrow), date == today.plusDays(1)) { date = today.plusDays(1) }
                val other = date.isAfter(today.plusDays(1))
                Chip(
                    if (other) DateFormat.format("EEE d MMM", date.atStartOfDay(zone).toInstant().toEpochMilli()).toString()
                    else stringResource(R.string.scheduled_call_other_date),
                    other
                ) { pickingDate = true }
            }

            SectionLabel(stringResource(R.string.scheduled_call_time))
            Text(
                text = clock(time),
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Pill)
                    .clickable { pickingTime = true }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )

            SectionLabel(stringResource(R.string.scheduled_call_repeat))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderRepeat.entries.forEach { option ->
                    Chip(repeatLabel(option), repeat == option) { repeat = option }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
                Text(
                    text = stringResource(if (initial == null) R.string.scheduled_call_save else R.string.scheduled_call_update),
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NothingRed)
                        .clickable {
                            val at = ReminderTimes.firstOccurrence(date, time, repeat, System.currentTimeMillis(), zone)
                            if (at == null) {
                                Toast.makeText(context, R.string.reminder_time_passed, Toast.LENGTH_SHORT).show()
                            } else {
                                onSave(at, repeat)
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }

    if (pickingDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.reminder_set)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (pickingTime) {
        val timeState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(timeState.hour, timeState.minute)
                    pickingTime = false
                }) { Text(stringResource(R.string.reminder_set)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = timeState) }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) NothingRed else Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/** The dialog for a new scheduled call to [number]; saves it and closes. */
@Composable
fun ScheduleCallHost(number: String, name: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    ScheduleCallDialog(
        title = name ?: number,
        subtitle = if (name != null) number else null,
        onDismiss = onDone,
        onSave = { at, repeat ->
            scheduleCall(context, number, name, at, repeat)
            onDone()
        }
    )
}

/** The contact name saved for [number], from the shared contacts cache. */
fun contactNameFor(number: String): String? =
    ContactsRepository.cachedByKey()?.get(PhoneNumbers.key(number))?.name

/** Calendar icon for Recents' expanded row: schedule a call to this number. */
@Composable
fun ScheduleCallButton(number: String) {
    if (!canRemind(number)) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Default.EditCalendar,
            contentDescription = stringResource(R.string.scheduled_call_schedule),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
    if (open) {
        val name = remember(number) { contactNameFor(number) }
        ScheduleCallHost(number, name) { open = false }
    }
}

/** The full-screen "Time to call Mom" page (over the lock screen too). */
@Composable
fun ScheduledCallAlert(
    title: String,
    subtitle: String?,
    at: Long,
    onCall: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.scheduled_call_alert_heading).uppercase(), pitch = 4.dp, color = NothingRed)
            Text(
                text = title,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 15.sp, color = Color.White.copy(alpha = 0.6f))
            }
            Text(
                text = stringResource(R.string.scheduled_call_due, DateFormat.getTimeFormat(context).format(at)),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(NothingRed)
                    .clickable(onClick = onCall),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Call, stringResource(R.string.call), tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AlertPill(stringResource(R.string.scheduled_call_snooze), onSnooze)
                AlertPill(stringResource(R.string.scheduled_call_dismiss), onDismiss)
            }
        }
    }
}

@Composable
private fun AlertPill(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 15.sp,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    )
}
