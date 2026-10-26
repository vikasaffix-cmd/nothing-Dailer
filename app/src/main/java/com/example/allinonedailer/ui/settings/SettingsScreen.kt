package com.example.allinonedailer.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.AppFont
import com.example.allinonedailer.data.AppFontStyle
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.data.RecordingRepository
import com.example.allinonedailer.data.VoicemailRepository
import com.example.allinonedailer.data.VoicemailTranscription
import androidx.compose.foundation.background
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource
import com.example.allinonedailer.data.AppLanguage
import com.example.allinonedailer.data.AppLanguageSetting
import com.example.allinonedailer.data.AppTheme
import com.example.allinonedailer.data.ThemeMode
import android.app.Activity
import android.content.ContextWrapper

@Composable
fun SettingsScreen(
    onOpenRecordings: () -> Unit = {},
    onOpenBlockRules: () -> Unit = {},
    onOpenSpeedDial: () -> Unit = {},
    onOpenVoicemail: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenQuietHours: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onOpenBalance: () -> Unit = {}
) {
    val context = LocalContext.current
    val recordingRepo = remember { RecordingRepository(context) }
    val callerIdRepo = remember { CallerIdRepository(context) }
    val voicemailUnheard by produceState(initialValue = 0) {
        value = withContext(Dispatchers.IO) { VoicemailRepository(context).unheardCount() }
    }

    var autoRecordEnabled by remember { mutableStateOf(recordingRepo.isAutoRecordEnabled()) }
    var callerIdEnabled by remember { mutableStateOf(callerIdRepo.isCallerIdEnabled()) }
    var spamBlockEnabled by remember { mutableStateOf(callerIdRepo.isSpamBlockEnabled()) }
    var onlineLookupEnabled by remember { mutableStateOf(callerIdRepo.isOnlineLookupEnabled()) }
    var endpoint by remember { mutableStateOf(callerIdRepo.getLookupEndpoint()) }
    val blockSummary = remember {
        val numbers = callerIdRepo.getBlockedNumbers().size
        val rules = callerIdRepo.getPrefixRules().count { it.enabled }
        val res = context.resources
        listOfNotNull(
            if (numbers > 0) res.getQuantityString(R.plurals.settings_blocked_count, numbers, numbers) else null,
            if (rules > 0) res.getQuantityString(R.plurals.settings_rules_count, rules, rules) else null,
            if (callerIdRepo.isBlockHiddenEnabled()) res.getString(R.string.settings_hidden_numbers) else null
        ).joinToString(" · ").ifEmpty { res.getString(R.string.settings_block_summary_empty) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Honest feature banner
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🛡️ " + stringResource(R.string.settings_banner_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_banner_body),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        // App language: follows the phone unless one is picked here
        Text(text = stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            text = stringResource(R.string.settings_language_summary),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val currentLanguage = remember { AppLanguageSetting.current(context) }
        AppLanguage.entries.forEach { language ->
            fun pick() {
                if (language != currentLanguage) context.findActivity()?.let { AppLanguageSetting.set(it, language) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pick() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = language == currentLanguage, onClick = { pick() })
                Text(
                    text = when (language) {
                        AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
                        // Each language in its own script, so it's findable whatever is showing.
                        AppLanguage.ENGLISH -> "English"
                        AppLanguage.HINDI -> "हिन्दी"
                        AppLanguage.MARATHI -> "मराठी"
                    },
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Theme: dark (the Nothing look), light, or follow the phone. Switches at once.
        Text(text = stringResource(R.string.settings_theme), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            text = stringResource(R.string.settings_theme_summary),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val currentTheme = AppTheme.current(context)
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { AppTheme.set(context, mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == currentTheme, onClick = { AppTheme.set(context, mode) })
                Text(
                    text = stringResource(mode.label),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Display font for names, numbers, keypad digits and titles
        Text(text = stringResource(R.string.settings_font), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            text = stringResource(R.string.settings_font_summary),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DotMatrixText(
            text = "+91 98765 43210",
            pitch = 3.dp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        val fontScope = rememberCoroutineScope()
        val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            fontScope.launch {
                val ok = withContext(Dispatchers.IO) { AppFont.importCustomFont(context, uri) }
                if (!ok) {
                    Toast.makeText(context, R.string.settings_font_invalid, Toast.LENGTH_LONG).show()
                }
            }
        }
        fun choose(option: AppFontStyle) {
            if (option == AppFontStyle.CUSTOM && !AppFont.hasCustomFont(context)) {
                pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
            } else {
                AppFont.set(context, option)
            }
        }
        AppFontStyle.entries.forEach { option ->
            val selected = AppFont.current(context) == option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { choose(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = { choose(option) })
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(text = stringResource(option.label), fontSize = 15.sp)
                    Text(
                        text = stringResource(option.description),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (AppFont.current(context) == AppFontStyle.CUSTOM) {
            Text(
                text = stringResource(R.string.settings_font_choose_other),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .clickable {
                        pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
                    }
                    .padding(start = 48.dp, top = 4.dp, bottom = 4.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Vibration strength and which events buzz
        VibrationSettings()

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Smart SIM: pinned / last used / same-network SIM on the keypad
        SmartSimSettings()

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Incoming-call pill over other apps
        CallPillSettings()

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Face-down while ringing silences the ringer
        FlipToSilenceSettings()

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Glyph Matrix lights for calls (Nothing phones)
        GlyphLightsSettings()

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Auto call recording
        SettingSwitchRow(
            title = stringResource(R.string.settings_auto_record),
            subtitle = stringResource(R.string.settings_auto_record_summary),
            checked = autoRecordEnabled,
            onCheckedChange = {
                autoRecordEnabled = it
                recordingRepo.setAutoRecordEnabled(it)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Recordings list
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRecordings)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_recordings),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = stringResource(R.string.settings_recordings_summary),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Voicemail inbox (audio files from a call-forwarding voicemail service)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenVoicemail)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.voicemail),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = stringResource(R.string.settings_voicemail_summary),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (voicemailUnheard > 0) {
                Text(
                    text = voicemailUnheard.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.tertiary)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // On-device voicemail transcripts and summaries
        var transcribeEnabled by remember { mutableStateOf(VoicemailTranscription.isEnabled(context)) }
        val askMicForTranscripts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            VoicemailTranscription.enqueueAll(context)
        }
        SettingSwitchRow(
            title = stringResource(R.string.settings_vm_transcribe),
            subtitle = stringResource(R.string.settings_vm_transcribe_summary),
            checked = transcribeEnabled,
            onCheckedChange = {
                transcribeEnabled = it
                VoicemailTranscription.setEnabled(context, it)
                if (it && !VoicemailTranscription.hasMicPermission(context)) {
                    askMicForTranscripts.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Caller ID
        SettingSwitchRow(
            title = stringResource(R.string.settings_caller_id),
            subtitle = stringResource(R.string.settings_caller_id_summary),
            checked = callerIdEnabled,
            onCheckedChange = {
                callerIdEnabled = it
                callerIdRepo.setCallerIdEnabled(it)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Spam blocking
        SettingSwitchRow(
            title = stringResource(R.string.settings_spam_block),
            subtitle = stringResource(R.string.settings_spam_block_summary),
            checked = spamBlockEnabled,
            onCheckedChange = {
                spamBlockEnabled = it
                callerIdRepo.setSpamBlockEnabled(it)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Online lookup (opt-in; requires an endpoint)
        SettingSwitchRow(
            title = stringResource(R.string.settings_online_lookup),
            subtitle = stringResource(R.string.settings_online_lookup_summary),
            checked = onlineLookupEnabled,
            onCheckedChange = {
                onlineLookupEnabled = it
                callerIdRepo.setOnlineLookupEnabled(it)
            }
        )

        AnimatedVisibility(visible = onlineLookupEnabled) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    callerIdRepo.setLookupEndpoint(it)
                },
                label = { Text(stringResource(R.string.settings_lookup_endpoint)) },
                placeholder = { Text("https://your-backend.example.com/") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Speed dial keys 2–9 (1 is voicemail)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSpeedDial)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.speed_dial_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = stringResource(R.string.settings_speed_dial_summary),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Blocklist, "starts with" rules and spam reports
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenBlockRules)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_block_rules),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = blockSummary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Quiet hours: silence calls on a schedule or with Do Not Disturb
        SettingsLinkRow(
            title = stringResource(R.string.quiet_hours_title),
            subtitle = stringResource(R.string.settings_quiet_hours_summary),
            onClick = onOpenQuietHours
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Upcoming call-back reminders
        SettingsLinkRow(
            title = stringResource(R.string.reminders_title),
            subtitle = stringResource(R.string.settings_reminders_summary),
            onClick = onOpenReminders
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Quick Settings tiles: which favourite "Call favourite" calls, and adding the tiles
        QuickTilesSettingsRow()

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Talk time, top contacts and busiest hours from the call log
        SettingsLinkRow(
            title = stringResource(R.string.insights_open),
            subtitle = stringResource(R.string.settings_insights_summary),
            onClick = onOpenInsights
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Operator USSD codes for balance, validity and data, per SIM
        SettingsLinkRow(
            title = stringResource(R.string.balance_title),
            subtitle = stringResource(R.string.settings_balance_summary),
            onClick = onOpenBalance
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Export / import everything the app stores itself
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenBackup)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.backup_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = stringResource(R.string.settings_backup_summary),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** The activity behind a Compose context, for recreating it in a new language. */
private fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
