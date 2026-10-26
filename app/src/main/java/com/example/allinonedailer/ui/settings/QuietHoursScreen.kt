package com.example.allinonedailer.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.QuietHoursConfig
import com.example.allinonedailer.data.QuietHoursRepository
import com.example.allinonedailer.data.QuietHoursRules
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.theme.NothingRed
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Settings → Quiet hours: when calls are silenced and who still rings. */
@Composable
fun QuietHoursScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { QuietHoursRepository(context) }
    var config by remember { mutableStateOf(repo.config()) }
    var vips by remember { mutableStateOf(repo.vips()) }
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var typingVip by remember { mutableStateOf(false) }

    fun update(change: (QuietHoursConfig) -> QuietHoursConfig) {
        config = change(config)
        repo.save(config)
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null, null, null
            )?.use {
                if (it.moveToFirst()) repo.addVip(it.getString(0).orEmpty(), it.getString(1))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vips = repo.vips()
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
            DotMatrixText(text = stringResource(R.string.quiet_hours_title), pitch = 4.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.quiet_hours_help),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Text(
                    text = stringResource(R.string.quiet_hours_needs_android_10),
                    fontSize = 13.sp,
                    color = NothingRed,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            SwitchRow(
                title = stringResource(R.string.quiet_hours_title),
                subtitle = statusLine(repo, config),
                checked = config.enabled,
                onChange = { on -> update { it.copy(enabled = on) } }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.quiet_hours_when))
            ChoiceRow(stringResource(R.string.quiet_hours_schedule), !config.followDnd) { update { it.copy(followDnd = false) } }
            ChoiceRow(stringResource(R.string.quiet_hours_follow_dnd), config.followDnd) { update { it.copy(followDnd = true) } }

            if (config.followDnd) {
                Text(
                    text = stringResource(R.string.quiet_hours_dnd_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeBox(stringResource(R.string.quiet_hours_from), config.startMinute) { editing = true }
                    TimeBox(stringResource(R.string.quiet_hours_to), config.endMinute) { editing = false }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DayOfWeek.values().forEach { day ->
                        val on = day.value in config.days
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    update { c -> c.copy(days = if (on) c.days - day.value else c.days + day.value) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.quiet_hours_days_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel(stringResource(R.string.quiet_hours_who_rings))
            SwitchRow(
                title = stringResource(R.string.quiet_hours_favourites),
                subtitle = stringResource(R.string.quiet_hours_favourites_summary),
                checked = config.allowFavourites,
                onChange = { on -> update { it.copy(allowFavourites = on) } }
            )
            SwitchRow(
                title = stringResource(R.string.quiet_hours_repeat),
                subtitle = stringResource(R.string.quiet_hours_repeat_summary, config.repeatWindowMinutes),
                checked = config.repeatCallers,
                onChange = { on -> update { it.copy(repeatCallers = on) } }
            )
            if (config.repeatCallers) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    IconButton(
                        onClick = { update { it.copy(repeatWindowMinutes = (it.repeatWindowMinutes - 1).coerceAtLeast(QuietHoursRules.MIN_REPEAT_WINDOW)) } }
                    ) { Icon(Icons.Default.Remove, stringResource(R.string.quiet_hours_shorter)) }
                    DotMatrixText(
                        text = stringResource(R.string.quiet_hours_minutes, config.repeatWindowMinutes).uppercase(),
                        pitch = 3.dp
                    )
                    IconButton(
                        onClick = { update { it.copy(repeatWindowMinutes = (it.repeatWindowMinutes + 1).coerceAtMost(QuietHoursRules.MAX_REPEAT_WINDOW)) } }
                    ) { Icon(Icons.Default.Add, stringResource(R.string.quiet_hours_longer)) }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel(stringResource(R.string.quiet_hours_vip))
            Text(
                text = stringResource(R.string.quiet_hours_vip_summary),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            vips.forEach { vip ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (vip.name ?: vip.number).uppercase(),
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        if (vip.name != null) {
                            Text(vip.number, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = {
                        repo.removeVip(vip.number)
                        vips = repo.vips()
                    }) {
                        Icon(Icons.Default.Close, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = {
                    try {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    } catch (e: Exception) {
                        typingVip = true
                    }
                }) {
                    Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.quiet_hours_add_contact).uppercase(), letterSpacing = 1.sp, fontSize = 12.sp)
                }
                TextButton(onClick = { typingVip = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.quiet_hours_add_number).uppercase(), letterSpacing = 1.sp, fontSize = 12.sp)
                }
            }
        }
    }

    editing?.let { start ->
        TimeDialog(
            initialMinute = if (start) config.startMinute else config.endMinute,
            onDismiss = { editing = null },
            onPick = { minute ->
                update { if (start) it.copy(startMinute = minute) else it.copy(endMinute = minute) }
                editing = null
            }
        )
    }

    if (typingVip) {
        var number by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typingVip = false },
            title = { Text(stringResource(R.string.quiet_hours_add_number)) },
            text = {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.addVip(number, null)
                    vips = repo.vips()
                    typingVip = false
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { typingVip = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun statusLine(repo: QuietHoursRepository, config: QuietHoursConfig): String = when {
    !config.enabled -> stringResource(R.string.quiet_hours_off)
    repo.isQuietNow() -> stringResource(R.string.quiet_hours_on_now)
    config.followDnd -> stringResource(R.string.quiet_hours_waiting_dnd)
    else -> stringResource(R.string.quiet_hours_starts_at, clock(config.startMinute))
}

@Composable
private fun clock(minute: Int): String {
    val context = LocalContext.current
    val time = LocalTime.of(minute / 60, minute % 60)
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return time.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Composable
private fun TimeBox(label: String, minute: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        DotMatrixText(text = clock(minute), pitch = 3.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.reminder_set)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state = state) }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        color = NothingRed,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
