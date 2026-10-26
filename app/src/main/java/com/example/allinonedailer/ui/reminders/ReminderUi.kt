package com.example.allinonedailer.ui.reminders

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallReminder
import com.example.allinonedailer.data.CallReminders
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.ReminderPreset
import com.example.allinonedailer.data.ReminderScheduler
import com.example.allinonedailer.data.ReminderTimes
import com.example.allinonedailer.ui.components.DotMatrixText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import com.example.allinonedailer.ui.theme.DialerTheme


/** "Remind me" can only be offered for a number we could call back. */
fun canRemind(number: String?): Boolean = number != null && PhoneNumbers.key(number).isNotEmpty()

/** Stores the reminder, sets its alarm and says when it will come. */
fun scheduleReminder(context: Context, number: String, name: String?, at: Long): CallReminder {
    val reminder = CallReminders.add(context.applicationContext, number, name, at)
    toastScheduled(context, at)
    return reminder
}

fun toastScheduled(context: Context, at: Long) {
    val text = context.getString(R.string.reminder_set_for, formatReminderTime(context, at)) +
        if (ReminderScheduler.canScheduleExact(context)) "" else "\n" + context.getString(R.string.reminder_may_be_late)
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/** "21:30" today, "Tue 21:30" within a week, else "3 Nov, 21:30". */
fun formatReminderTime(context: Context, at: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = DateFormat.getTimeFormat(context).format(at)
    return when {
        day == today -> time
        day == today.plusDays(1) -> context.getString(R.string.reminder_tomorrow_at, time)
        day.isBefore(today.plusDays(7)) -> DateFormat.format("EEE", at).toString() + " " + time
        else -> DateFormat.format("d MMM", at).toString() + ", " + time
    }
}

@Composable
fun presetLabel(preset: ReminderPreset): String {
    val context = LocalContext.current
    fun clock(time: LocalTime): String = DateFormat.getTimeFormat(context).format(
        LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    return when (preset) {
        ReminderPreset.MINUTES_15 -> stringResource(R.string.reminder_in_15_min)
        ReminderPreset.HOUR_1 -> stringResource(R.string.reminder_in_1_hour)
        ReminderPreset.TONIGHT -> stringResource(R.string.reminder_tonight, clock(ReminderTimes.TONIGHT))
        ReminderPreset.MORNING -> stringResource(R.string.reminder_morning, clock(ReminderTimes.MORNING))
    }
}

/**
 * The "Remind me to call back" picker: 15 min, 1 hour, tonight (or tomorrow
 * morning late in the day), or a date and time of your own. [onPick] gets
 * the chosen time; the caller stores it.
 */
@Composable
fun ReminderPickerDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onPick: (at: Long) -> Unit
) {
    var custom by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }

    if (custom) {
        CustomTimeDialog(onDismiss = onDismiss, onPick = onPick)
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(DialerTheme.colors.panel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.reminder_remind_me).uppercase(), pitch = 3.dp, color = DialerTheme.colors.ink)
            Text(text = title, fontSize = 15.sp, color = DialerTheme.colors.ink, maxLines = 1)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = DialerTheme.colors.ink.copy(alpha = 0.6f), maxLines = 1)
            }
            Spacer(modifier = Modifier.height(4.dp))
            ReminderTimes.presets(now, zone).forEach { preset ->
                PillRow(presetLabel(preset)) { onPick(ReminderTimes.at(preset, System.currentTimeMillis(), zone)) }
            }
            PillRow(stringResource(R.string.reminder_pick_time)) { custom = true }
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 14.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun PillRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 15.sp,
        color = DialerTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DialerTheme.colors.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}

/** A date, then a time; refuses a time that has already passed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTimeDialog(
    initial: Long = System.currentTimeMillis() + 60 * 60_000L,
    onDismiss: () -> Unit,
    onPick: (at: Long) -> Unit
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val start = remember { Instant.ofEpochMilli(initial).atZone(zone) }
    var date by remember { mutableStateOf<LocalDate?>(null) }

    if (date == null) {
        val today = remember { LocalDate.now(zone) }
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            }
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                }) { Text(stringResource(R.string.reminder_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        ) {
            DatePicker(state = dateState)
        }
    } else {
        val timeState = rememberTimePickerState(
            initialHour = start.hour,
            initialMinute = start.minute,
            is24Hour = DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val at = date!!.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli()
                    if (at <= System.currentTimeMillis()) {
                        Toast.makeText(context, R.string.reminder_time_passed, Toast.LENGTH_SHORT).show()
                    } else {
                        onPick(at)
                    }
                }) { Text(stringResource(R.string.reminder_set)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = timeState) }
        )
    }
}

/** "REMIND ME" for Recents' expanded row. */
@Composable
fun RemindMeButton(number: String, contactName: String? = null) {
    if (!canRemind(number)) return
    val context = LocalContext.current
    val name = contactName ?: remember(number) {
        ContactsRepository.cachedByKey()?.get(PhoneNumbers.key(number))?.name
    }
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Icon(Icons.Default.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.reminder_remind_me).uppercase(),
            letterSpacing = 1.sp,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    if (open) {
        ReminderPickerDialog(
            title = name ?: number,
            subtitle = if (name != null) number else null,
            onDismiss = { open = false },
            onPick = { at ->
                open = false
                scheduleReminder(context, number, name, at)
            }
        )
    }
}

/**
 * In the quick-reply sheet: decline now and get a reminder later. [onDecline]
 * runs after the reminder is stored.
 */
@Composable
fun DeclineAndRemindRow(number: String, name: String?, onDecline: () -> Unit) {
    if (!canRemind(number)) return
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val presets = remember { ReminderTimes.presets(System.currentTimeMillis(), zone) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.reminder_decline_and_remind),
            fontSize = 12.sp,
            color = DialerTheme.colors.ink.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp)
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                Text(
                    text = presetLabel(preset),
                    fontSize = 13.sp,
                    color = DialerTheme.colors.onAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(DialerTheme.colors.accent)
                        .clickable {
                            scheduleReminder(context, number, name, ReminderTimes.at(preset, System.currentTimeMillis(), zone))
                            onDecline()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
