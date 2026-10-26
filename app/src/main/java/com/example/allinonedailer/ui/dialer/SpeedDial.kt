package com.example.allinonedailer.ui.dialer

import android.content.Context
import android.content.Intent
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.SpeedDialEntry
import com.example.allinonedailer.data.SpeedDialRepository
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource
import com.example.allinonedailer.ui.theme.DialerTheme
import androidx.compose.material3.MaterialTheme


/** Speed-dial assignments plus the key currently being assigned (picker open). */
@Stable
class SpeedDialState(private val repo: SpeedDialRepository, assigning: MutableState<Int?>) {
    var entries by mutableStateOf(repo.getAll())
        private set
    var assigningKey by assigning

    fun isAssigned(key: Int): Boolean = entries.containsKey(key)

    fun assign(key: Int, contact: ContactItem) {
        repo.assign(key, contact.name, contact.phoneNumber, contact.photoUri)
        entries = repo.getAll()
    }

    fun clear(key: Int) {
        repo.clear(key)
        entries = repo.getAll()
    }

    /**
     * Long-press on keypad key [key]: 1 calls voicemail, an assigned key
     * calls its number, an unassigned one opens the contact picker.
     */
    fun longPress(context: Context, key: Int, sim: PhoneAccountHandle?, dial: (String) -> Unit) {
        when {
            key == 1 -> callVoicemail(context, sim, dial)
            key in SpeedDialRepository.KEYS -> {
                val entry = entries[key]
                if (entry != null) dial(entry.number) else assigningKey = key
            }
        }
    }
}

@Composable
fun rememberSpeedDialState(): SpeedDialState {
    val context = LocalContext.current
    // The key being assigned survives rotation, so the picker stays open.
    val assigning = rememberSaveable { mutableStateOf<Int?>(null) }
    return remember { SpeedDialState(SpeedDialRepository(context), assigning) }
}

/** Shows the contact picker while [state] is assigning a key. */
@Composable
fun SpeedDialDialogs(state: SpeedDialState) {
    val context = LocalContext.current
    val key = state.assigningKey ?: return
    SpeedDialPicker(
        key = key,
        onPick = { contact ->
            state.assign(key, contact)
            state.assigningKey = null
            Toast.makeText(context, context.getString(R.string.speed_dial_assigned, key, contact.name), Toast.LENGTH_SHORT).show()
        },
        onDismiss = { state.assigningKey = null }
    )
}

/** The carrier voicemail number for [sim] (or the default SIM), if the user has one set. */
fun voicemailNumber(context: Context, sim: PhoneAccountHandle? = null): String? = try {
    val tm = context.getSystemService(TelephonyManager::class.java)
    val forSim = sim?.let { tm?.createForPhoneAccountHandle(it) } ?: tm
    forSim?.voiceMailNumber?.takeIf { it.isNotBlank() }
} catch (e: SecurityException) {
    null
}

/** Calls voicemail via `tel:`, or opens the system voicemail settings when no number is set. */
fun callVoicemail(context: Context, sim: PhoneAccountHandle?, dial: (String) -> Unit) {
    val number = voicemailNumber(context, sim)
    if (number != null) dial(number) else openVoicemailSettings(context)
}

fun openVoicemailSettings(context: Context) {
    val intents = listOf(
        Intent(TelephonyManager.ACTION_CONFIGURE_VOICEMAIL),
        Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(context, R.string.no_voicemail_number, Toast.LENGTH_SHORT).show()
            return
        } catch (e: Exception) {
            // Try the next one.
        }
    }
    Toast.makeText(context, R.string.no_voicemail_number, Toast.LENGTH_SHORT).show()
}

/** Contact picker for assigning speed-dial key [key]. Lists every number of every contact. */
@Composable
fun SpeedDialPicker(key: Int, onPick: (ContactItem) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<ContactItem>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            ContactsRepository(context).loadContacts(onePerContact = false)
                .distinctBy { it.id to it.phoneNumber.filter(Char::isDigit).takeLast(10) }
        }
    }
    val q = query.trim()
    val qDigits = q.filter(Char::isDigit)
    val shown = remember(contacts, q) {
        contacts.orEmpty().filter {
            q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
                (qDigits.isNotEmpty() && it.phoneNumber.filter(Char::isDigit).contains(qDigits))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, DialerTheme.colors.keyOutline, RoundedCornerShape(20.dp))
                .padding(top = 18.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DotMatrixText(text = stringResource(R.string.speed_dial_key, key), pitch = 4.dp, color = DialerTheme.colors.ink)
            }
            Text(
                text = stringResource(R.string.speed_dial_choose, key),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = DialerTheme.colors.muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DialerTheme.colors.accent,
                    unfocusedBorderColor = DialerTheme.colors.keyOutline,
                    cursorColor = DialerTheme.colors.accent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                if (contacts == null) {
                    item { PickerNote(stringResource(R.string.loading)) }
                } else if (shown.isEmpty()) {
                    item { PickerNote(stringResource(R.string.no_contacts)) }
                }
                items(shown, key = { it.id + "|" + it.phoneNumber }) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(contact) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallerAvatar(
                            name = contact.name,
                            number = contact.phoneNumber,
                            photoUri = contact.photoUri,
                            size = 30.dp,
                            pill = true
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name.uppercase(),
                                fontSize = 12.sp,
                                letterSpacing = 1.5.sp,
                                color = DialerTheme.colors.ink,
                                maxLines = 1
                            )
                            Text(text = contact.phoneNumber, fontSize = 11.sp, color = DialerTheme.colors.muted, maxLines = 1)
                        }
                    }
                    HorizontalDivider(color = DialerTheme.colors.divider)
                }
            }
            Text(
                text = stringResource(R.string.cancel).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = DialerTheme.colors.accent,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(50))
                    .clickable { onDismiss() }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun PickerNote(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = DialerTheme.colors.muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    )
}

/** Small marker drawn on keypad keys that have a speed-dial number. */
@Composable
fun SpeedDialDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(DialerTheme.colors.accent)
    )
}

/** Label for a speed-dial entry in lists. */
fun SpeedDialEntry.label(): String = name.ifBlank { number }
