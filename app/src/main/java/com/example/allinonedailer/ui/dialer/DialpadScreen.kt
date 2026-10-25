package com.example.allinonedailer.ui.dialer

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallLogRepository
import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.NumberInfo
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val KeyGrey = Color(0xFF262626)
private val NothingRed = Color(0xFFD71921)
private val Muted = Color.White.copy(alpha = 0.55f)

private data class Suggestion(val name: String?, val number: String, val photoUri: String?)

/** The latest distinct numbers from the call log, for the empty keypad. */
private fun recentSuggestions(log: List<CallLogEntry>): List<Suggestion> =
    log.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
        .take(8)
        .map { Suggestion(it.callerName, it.number, it.photoUri) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadScreen(
    onOpenRecents: () -> Unit,
    onOpenContacts: () -> Unit
) {
    val context = LocalContext.current
    var numberText by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var recents by remember { mutableStateOf(recentSuggestions(CallLogRepository.lastResult)) }

    LaunchedEffect(Unit) {
        recents = recentSuggestions(withContext(Dispatchers.IO) { CallLogRepository(context).getCallLogs() })
        contacts = withContext(Dispatchers.IO) {
            ContactsRepository(context).loadContacts(onePerContact = false)
        }
    }

    val digits = numberText.filter(Char::isDigit)
    val suggestions = if (digits.isEmpty()) {
        recents
    } else {
        contacts
            .filter { it.phoneNumber.filter(Char::isDigit).contains(digits) }
            .distinctBy { it.id }
            .take(20)
            .map { Suggestion(it.name, it.phoneNumber, it.photoUri) }
    }
    val isSavedNumber = digits.length >= 6 && contacts.any {
        it.phoneNumber.filter(Char::isDigit).takeLast(10) == digits.takeLast(10)
    }

    // Offline carrier / region for the number being typed (libphonenumber).
    var numberLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(digits) {
        numberLabel = if (digits.length < 7) null else withContext(Dispatchers.IO) {
            NumberInfo.describe(numberText, NumberInfo.defaultRegion(context))?.label
        }
    }

    val tones = rememberDialTones()

    Column(modifier = Modifier.fillMaxSize()) {
        // Matching contacts / recent calls
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(suggestions) { s ->
                SuggestionRow(
                    suggestion = s,
                    onPick = { numberText = s.number },
                    onCall = { placeCall(context, s.number) }
                )
            }
        }

        // Sheet handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.2f))
            )
        }

        // Entered number
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (numberText.isNotEmpty()) {
                DotMatrixText(
                    text = formatForDisplay(numberText),
                    pitch = 5.dp,
                    centered = true,
                    color = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (numberText.isNotEmpty() && !isSavedNumber) {
                numberLabel?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = Muted,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                AddContactPill { addContact(context, numberText) }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Keypad
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val press: (Char) -> Unit = { c ->
                    tones.play(c)
                    numberText += c
                }
                KeyRow {
                    DialKey("1", subIcon = Icons.Default.Voicemail) { press('1') }
                    DialKey("2", sub = "ABC") { press('2') }
                    DialKey("3", sub = "DEF") { press('3') }
                }
                KeyRow {
                    DialKey("4", sub = "GHI") { press('4') }
                    DialKey("5", sub = "JKL") { press('5') }
                    DialKey("6", sub = "MNO") { press('6') }
                }
                KeyRow {
                    DialKey("7", sub = "PQRS") { press('7') }
                    DialKey("8", sub = "TUV") { press('8') }
                    DialKey("9", sub = "WXYZ") { press('9') }
                }
                KeyRow {
                    DialKey("#", sub = "*", outlined = true, onLongPress = { press('*') }) { press('#') }
                    DialKey("0", sub = "+", onLongPress = { numberText += '+' }) { press('0') }
                    BackspaceKey(
                        onTap = { numberText = numberText.dropLast(1) },
                        onLongPress = { numberText = "" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Bottom bar: recents · call · contacts
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenRecents) {
                    Icon(Icons.Default.History, "Recents", tint = Color.White)
                }
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .combinedClickable(
                            onClick = {
                                when {
                                    numberText.isNotEmpty() -> placeCall(context, numberText)
                                    // Empty keypad: recall the last number, like stock dialers.
                                    recents.isNotEmpty() -> numberText = recents.first().number
                                }
                            },
                            // Long-press: start the call as a two-way video call.
                            onLongClick = {
                                if (numberText.isNotEmpty()) placeCall(context, numberText, video = true)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Call, "Call", tint = Color.Black, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onOpenContacts) {
                    Icon(Icons.Default.ContactPage, "Contacts", tint = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun KeyRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    label: String,
    sub: String? = null,
    subIcon: ImageVector? = null,
    outlined: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .then(
                if (outlined) Modifier.border(1.dp, Color(0xFF2E2E2E), CircleShape)
                else Modifier.background(KeyGrey)
            )
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onLongClick = onLongPress?.let { action ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        action()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DotMatrixText(text = label, pitch = 3.4.dp, color = Color.White)
            Spacer(modifier = Modifier.height(5.dp))
            when {
                subIcon != null -> Icon(subIcon, null, tint = Muted, modifier = Modifier.size(12.dp))
                sub != null -> Text(text = sub, fontSize = 10.sp, letterSpacing = 1.sp, color = Muted)
                else -> Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceKey(onTap: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .background(NothingRed)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onPick: () -> Unit, onCall: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallerAvatar(
                name = suggestion.name,
                number = suggestion.number,
                photoUri = suggestion.photoUri,
                size = 30.dp,
                pill = true
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (suggestion.name ?: suggestion.number).uppercase(),
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = Color.White,
                    maxLines = 1
                )
                if (suggestion.name != null) {
                    Text(text = suggestion.number, fontSize = 11.sp, color = Muted, maxLines = 1)
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onCall() }
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "CALL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
            }
        }
        HorizontalDivider(color = Color(0xFF161616))
    }
}

@Composable
private fun AddContactPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .drawBehind {
                drawRoundRect(
                    color = NothingRed,
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    )
                )
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.AddCircleOutline, null, tint = NothingRed, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = "Add contact", fontSize = 12.sp, color = NothingRed)
    }
}

/** Local key-press tones, honouring the system "dial pad tones" setting. */
private class DialTones(private val generator: ToneGenerator?, private val enabled: Boolean) {
    fun play(c: Char) {
        if (!enabled || generator == null) return
        val tone = when (c) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        generator.startTone(tone, 120)
    }

    fun release() = generator?.release()
}

@Composable
private fun rememberDialTones(): DialTones {
    val context = LocalContext.current
    val tones = remember {
        val enabled = Settings.System.getInt(
            context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1
        ) == 1
        val generator = try {
            ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (e: RuntimeException) {
            null
        }
        DialTones(generator, enabled)
    }
    DisposableEffect(Unit) { onDispose { tones.release() } }
    return tones
}

private fun formatForDisplay(raw: String): String {
    val digitCount = raw.count(Char::isDigit)
    if (digitCount < 7 || raw.any { it == '*' || it == '#' }) return raw
    return PhoneNumberUtils.formatNumber(raw, Locale.getDefault().country) ?: raw
}

private fun placeCall(context: Context, number: String, video: Boolean = false) {
    try {
        val uri = Uri.parse("tel:${Uri.encode(number)}")
        context.startActivity(Intent(Intent.ACTION_CALL, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (video) {
                putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
            }
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun addContact(context: Context, number: String) {
    try {
        context.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
