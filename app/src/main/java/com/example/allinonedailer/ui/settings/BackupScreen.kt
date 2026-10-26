package com.example.allinonedailer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.backup.BackupException
import com.example.allinonedailer.data.backup.BackupManager
import com.example.allinonedailer.data.backup.BackupSpec
import com.example.allinonedailer.data.backup.BackupSummary
import com.example.allinonedailer.data.backup.RestoreMode
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Date

/** Settings › Backup & restore: one file for everything the app keeps itself. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordings = remember { BackupManager.recordingFiles(context) }
    val recordingsSize = remember(recordings) { recordings.sumOf { it.length() } }

    var includeRecordings by remember { mutableStateOf(false) }
    var includeSecrets by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Uri, BackupManager.Loaded>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val message = try {
                val summary = BackupManager.export(context, uri, includeSecrets, includeRecordings)
                val lines = summaryLines(context, summary)
                if (lines.isEmpty()) context.getString(R.string.backup_saved_empty)
                else context.getString(R.string.backup_saved, lines.take(3).joinToString(", "))
            } catch (e: Exception) {
                context.getString(R.string.backup_failed)
            }
            busy = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                pending = uri to BackupManager.load(context, uri)
            } catch (e: BackupException) {
                Toast.makeText(context, errorText(context, e), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.backup_error_read, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }
    fun pickBackup() = importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            if (busy) {
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 16.dp).size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.backup_title), pitch = 4.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Hint(stringResource(R.string.backup_help))

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Export
            SectionTitle(stringResource(R.string.backup_export_title))
            Hint(stringResource(R.string.backup_export_includes))
            BackupSwitchRow(
                title = stringResource(R.string.backup_include_recordings),
                subtitle = if (recordings.isEmpty()) stringResource(R.string.backup_include_recordings_none)
                else stringResource(
                    R.string.backup_include_recordings_summary,
                    recordings.size,
                    Formatter.formatShortFileSize(context, recordingsSize)
                ),
                checked = includeRecordings,
                enabled = recordings.isNotEmpty()
            ) { includeRecordings = it }
            BackupSwitchRow(
                title = stringResource(R.string.backup_include_secrets),
                subtitle = stringResource(R.string.backup_include_secrets_summary),
                checked = includeSecrets
            ) { includeSecrets = it }
            PillButton(stringResource(R.string.backup_export_button), primary = true, enabled = !busy) {
                exportLauncher.launch(BackupSpec.fileName(LocalDate.now()))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Import
            SectionTitle(stringResource(R.string.backup_import_title))
            Hint(stringResource(R.string.backup_import_summary))
            PillButton(stringResource(R.string.backup_import_button), enabled = !busy) { pickBackup() }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // The old debug build has no Export of its own.
            SectionTitle(stringResource(R.string.backup_old_app_title))
            Hint(stringResource(R.string.backup_old_app_body))
            PillButton(stringResource(R.string.backup_old_app_button), enabled = !busy) { pickBackup() }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SectionTitle(stringResource(R.string.backup_auto_title))
            Hint(stringResource(R.string.backup_auto_body))
        }
    }

    pending?.let { (uri, loaded) ->
        RestoreDialog(
            loaded = loaded,
            onDismiss = { pending = null },
            onRestore = { mode, withRecordings ->
                pending = null
                busy = true
                scope.launch {
                    val result = try {
                        BackupManager.restore(context, uri, loaded, mode, withRecordings)
                    } catch (e: Exception) {
                        null
                    }
                    busy = false
                    Toast.makeText(
                        context,
                        if (result != null) R.string.backup_restored else R.string.backup_restore_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    if (result?.needsRecreate == true) context.findActivity()?.recreate()
                }
            }
        )
    }
}

@Composable
private fun RestoreDialog(
    loaded: BackupManager.Loaded,
    onDismiss: () -> Unit,
    onRestore: (RestoreMode, Boolean) -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var withRecordings by remember { mutableStateOf(true) }
    val lines = remember(loaded) { summaryLines(context, loaded.summary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val made = remember(loaded) {
                    val date = Date(loaded.data.createdAt)
                    "${DateFormat.getMediumDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
                }
                Hint(
                    if (loaded.fromOldApp) stringResource(R.string.backup_dialog_from_old)
                    else stringResource(R.string.backup_dialog_from, made)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.backup_dialog_empty), fontSize = 14.sp)
                } else {
                    lines.forEach { Text("· $it", fontSize = 14.sp) }
                }
                if (loaded.data.secretsIncluded) Hint(stringResource(R.string.backup_secrets_note))
                if (loaded.summary.voicemailSettings) Hint(stringResource(R.string.backup_voicemail_note))

                Spacer(modifier = Modifier.height(12.dp))
                ModeRow(RestoreMode.MERGE, mode, R.string.backup_mode_merge, R.string.backup_mode_merge_summary) { mode = it }
                ModeRow(RestoreMode.REPLACE, mode, R.string.backup_mode_replace, R.string.backup_mode_replace_summary) { mode = it }

                if (loaded.summary.recordingFiles > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { withRecordings = !withRecordings }
                    ) {
                        Checkbox(checked = withRecordings, onCheckedChange = { withRecordings = it })
                        Text(stringResource(R.string.backup_restore_recordings), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(mode, withRecordings) }, enabled = lines.isNotEmpty()) {
                Text(stringResource(R.string.backup_restore), color = MaterialTheme.colorScheme.tertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ModeRow(value: RestoreMode, selected: RestoreMode, title: Int, summary: Int, onSelect: (RestoreMode) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 2.dp)
    ) {
        RadioButton(selected = value == selected, onClick = { onSelect(value) })
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(summary), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun PillButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val background = if (primary) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = if (primary) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun BackupSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
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
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** "312 blocked numbers", "18 call notes"… for what the backup holds. */
private fun summaryLines(context: Context, s: BackupSummary): List<String> {
    val res = context.resources
    fun count(id: Int, n: Int) = if (n > 0) res.getQuantityString(id, n, n) else null
    return listOfNotNull(
        count(R.plurals.backup_count_blocked, s.blockedNumbers),
        count(R.plurals.backup_count_rules, s.blockRules),
        count(R.plurals.backup_count_spam, s.spamReports),
        count(R.plurals.backup_count_notes, s.notes),
        count(R.plurals.backup_count_speed_dial, s.speedDials),
        count(R.plurals.backup_count_names, s.networkNames),
        count(R.plurals.backup_count_recordings_index, s.recordingsIndexed),
        count(R.plurals.backup_count_recording_files, s.recordingFiles),
        if (s.hasFont) context.getString(R.string.backup_has_font) else null,
        s.language?.let { tag ->
            context.getString(
                R.string.backup_language,
                when (tag) {
                    "" -> context.getString(R.string.settings_language_system)
                    "en" -> "English"
                    "hi" -> "हिन्दी"
                    "mr" -> "मराठी"
                    else -> tag
                }
            )
        }
    )
}

private fun errorText(context: Context, e: BackupException): String = when (e.reason) {
    BackupException.Reason.NOT_A_BACKUP -> context.getString(R.string.backup_error_not_backup)
    BackupException.Reason.TOO_NEW -> context.getString(R.string.backup_error_too_new, e.version ?: 0)
    BackupException.Reason.CORRUPT -> context.getString(R.string.backup_error_corrupt)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
