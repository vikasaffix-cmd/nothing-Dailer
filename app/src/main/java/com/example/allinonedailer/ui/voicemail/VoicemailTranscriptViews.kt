package com.example.allinonedailer.ui.voicemail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.OnDeviceTranscriber
import com.example.allinonedailer.data.TranscriptStatus
import com.example.allinonedailer.data.VoicemailTranscript

/** Search box under the title; matches name, number, summary and transcript. */
@Composable
internal fun VoicemailSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.vm_ai_search_hint), fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
    )
}

@Composable
internal fun NoSearchMatches() {
    Text(
        text = stringResource(R.string.vm_ai_no_matches),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 32.dp, horizontal = 16.dp)
    )
}

/** Shown when "Transcribe voicemails" is on but can't run on this phone yet. */
@Composable
internal fun TranscribeNotice(micGranted: Boolean, onAllowMic: () -> Unit) {
    val context = LocalContext.current
    val supported = remember { OnDeviceTranscriber.isSupported(context) }
    if (supported && micGranted) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(if (!supported) R.string.vm_ai_unsupported else R.string.vm_ai_needs_mic),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (supported) {
            TextButton(onClick = onAllowMic, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.vm_ai_allow).uppercase(), color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, letterSpacing = 1.sp)
            }
        }
    }
}

/** The summary line, or "Transcribing…", under the number in a collapsed row. */
@Composable
internal fun TranscriptPreview(transcript: VoicemailTranscript?) {
    when {
        transcript == null -> Unit
        transcript.status == TranscriptStatus.PENDING -> Text(
            text = stringResource(R.string.vm_ai_transcribing).uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp)
        )
        transcript.summary != null -> Text(
            text = transcript.summary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Summary, collapsible transcript, "Add reminder" when a date or time was
 * said, and retry with a language choice. Never touches the player.
 */
@Composable
internal fun TranscriptPanel(
    rowKey: String,
    transcript: VoicemailTranscript?,
    onRetry: (String?) -> Unit,
    onAddReminder: (VoicemailTranscript) -> Unit
) {
    var showText by rememberSaveable(rowKey) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val status = transcript?.status
        when {
            transcript == null -> Text(
                text = stringResource(R.string.vm_ai_not_transcribed),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            transcript.status == TranscriptStatus.PENDING -> Text(
                text = stringResource(R.string.vm_ai_transcribing).uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.tertiary
            )
            transcript.status == TranscriptStatus.DONE -> {
                transcript.summary?.let {
                    Text(text = it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showText = !showText }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = listOfNotNull(stringResource(R.string.vm_ai_transcript).uppercase(), languageName(transcript.language)).joinToString("  ·  "),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showText) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (showText) R.string.collapse else R.string.expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showText) {
                    Text(
                        text = transcript.text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            else -> Text(
                text = when (transcript.status) {
                    TranscriptStatus.NO_SPEECH -> stringResource(R.string.vm_ai_no_speech)
                    TranscriptStatus.UNSUPPORTED -> stringResource(R.string.vm_ai_unsupported)
                    TranscriptStatus.NEEDS_PERMISSION -> stringResource(R.string.vm_ai_needs_mic)
                    TranscriptStatus.NO_LANGUAGE_PACK ->
                        stringResource(R.string.vm_ai_no_language_pack, languageName(transcript.language) ?: "")
                    else -> stringResource(R.string.vm_ai_failed)
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (status != TranscriptStatus.PENDING) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (transcript != null && status == TranscriptStatus.DONE && transcript.reminderStart != null) {
                    SmallPill(Icons.Default.Event, stringResource(R.string.vm_ai_add_reminder).uppercase(), accent = true) {
                        onAddReminder(transcript)
                    }
                }
                RetryPill(
                    label = stringResource(if (status == null) R.string.vm_ai_transcribe else R.string.vm_ai_retry).uppercase(),
                    onRetry = onRetry
                )
            }
        }
    }
}

/** Retry, picking the language when detection got it wrong. */
@Composable
private fun RetryPill(label: String, onRetry: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        SmallPill(Icons.Default.Refresh, label, accent = false) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(null, "en", "hi", "mr").forEach { language ->
                DropdownMenuItem(
                    text = { Text(languageName(language) ?: stringResource(R.string.vm_ai_language_auto)) },
                    onClick = {
                        menu = false
                        onRetry(language)
                    }
                )
            }
        }
    }
}

@Composable
private fun SmallPill(icon: ImageVector, label: String, accent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (accent) Modifier.background(MaterialTheme.colorScheme.tertiary)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (accent) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onBackground
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp, color = tint)
    }
}

/** Each language in its own script, as in Settings › Language. */
private fun languageName(code: String?): String? = when (code) {
    "en" -> "English"
    "hi" -> "हिन्दी"
    "mr" -> "मराठी"
    else -> null
}

/** Opens the calendar's new-event screen prefilled; the user saves it there. */
internal fun addReminder(context: Context, caller: String, transcript: VoicemailTranscript) {
    val start = transcript.reminderStart ?: return
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + if (transcript.reminderAllDay) 86_400_000L else 30 * 60_000L)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, transcript.reminderAllDay)
        .putExtra(CalendarContract.Events.TITLE, transcript.summary ?: caller)
        .putExtra(CalendarContract.Events.DESCRIPTION, context.getString(R.string.vm_ai_reminder_body, caller, transcript.text))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_can_handle, Toast.LENGTH_SHORT).show()
    }
}
