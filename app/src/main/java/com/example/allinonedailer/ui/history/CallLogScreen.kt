package com.example.allinonedailer.ui.history

import android.content.Context
import android.content.res.Configuration
import android.media.MediaPlayer
import android.provider.CallLog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.example.allinonedailer.ui.reminders.RemindMeButton
import com.example.allinonedailer.ui.reminders.ScheduleCallButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallFilter
import com.example.allinonedailer.data.CallGroup
import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.CallLogRepository
import com.example.allinonedailer.data.CallNote
import com.example.allinonedailer.data.CallNoteMatcher
import com.example.allinonedailer.data.CallNotesRepository
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.RecentsItem
import com.example.allinonedailer.data.formatCallDuration
import com.example.allinonedailer.data.groupCallLogs
import com.example.allinonedailer.data.showsDuration
import com.example.allinonedailer.service.MissedCallNotifier
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.notes.NoteEditorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.pluralStringResource
import androidx.annotation.StringRes

@Composable
fun CallLogScreen(
    /** Search to start with (Insights opens a contact's calls this way). */
    initialQuery: String? = null,
    onOpenInsights: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Start from the last loaded list so Recents opens instantly, then refresh.
    var callLogs by remember { mutableStateOf(CallLogRepository.lastResult) }
    var loaded by remember { mutableStateOf(CallLogRepository.lastResult.isNotEmpty()) }
    var filter by rememberSaveable { mutableStateOf(CallFilter.ALL) }
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.orEmpty()) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CallGroup?>(null) }
    // The call whose note is being written or edited.
    var editingNote by remember { mutableStateOf<CallLogEntry?>(null) }
    val listState = rememberLazyListState()

    // A new filter or search starts from the newest call.
    LaunchedEffect(filter, query) { listState.scrollToItem(0) }

    LaunchedEffect(Unit) {
        callLogs = withContext(Dispatchers.IO) {
            CallLogRepository(context).getCallLogs(CallLogRepository.FIRST_PAGE) { page ->
                // Nothing cached yet: show the newest calls while older ones load.
                if (!loaded) scope.launch {
                    if (!loaded) {
                        callLogs = page
                        loaded = true
                    }
                }
            }
        }
        loaded = true
    }

    // Seeing Recents counts as seeing the missed calls: clear their
    // notifications and mark them read, also when returning to the app here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Thread { MissedCallNotifier.clear(context.applicationContext) }.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Call notes: load them, tie notes written as a call ended to their new rows.
    val notes by CallNotesRepository.notes.collectAsState()
    LaunchedEffect(callLogs) {
        CallNotesRepository.load(context)
        if (callLogs.isNotEmpty()) CallNotesRepository.resolvePending(context, callLogs)
    }
    val noteById by produceState(initialValue = emptyMap<String, CallNote>(), callLogs, notes) {
        value = withContext(Dispatchers.Default) { CallNoteMatcher.byEntryId(callLogs, notes) }
    }

    val todayLabel = stringResource(R.string.today)
    val yesterdayLabel = stringResource(R.string.yesterday)
    val items by produceState(
        // Grouped in place only for the first frame; the argument would otherwise
        // regroup the whole log on the main thread at every recomposition.
        initialValue = remember { groupCallLogs(callLogs, filter, query, todayLabel = todayLabel, yesterdayLabel = yesterdayLabel) },
        callLogs, filter, query, noteById, todayLabel
    ) {
        value = withContext(Dispatchers.Default) {
            groupCallLogs(callLogs, filter, query, noteById.mapValues { it.value.text }, todayLabel, yesterdayLabel)
        }
    }

    // Deletes off the main thread; the repository drops the rows from lastResult.
    fun delete(ids: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) { CallLogRepository(context).deleteCalls(ids) }
            callLogs = CallLogRepository.lastResult
        }
    }

    val headerPane: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotMatrixText(
                    text = stringResource(R.string.recents_title),
                    pitch = 4.dp,
                    modifier = Modifier.weight(1f).padding(top = 8.dp)
                )
                if (onOpenInsights != null) {
                    IconButton(onClick = onOpenInsights) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = stringResource(R.string.insights_open),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search).uppercase(), letterSpacing = 1.sp, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallFilter.entries.forEach { f ->
                    FilterPill(text = stringResource(f.label).uppercase(), selected = f == filter) { filter = f }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    val listPane: @Composable () -> Unit = {
        when {
            !loaded -> Box(modifier = Modifier.fillMaxSize())
            items.isEmpty() -> EmptyRecents(
                text = when {
                    callLogs.isEmpty() -> stringResource(R.string.recents_empty)
                    query.isNotBlank() -> stringResource(R.string.no_matches)
                    else -> stringResource(filter.emptyLabel)
                }.uppercase()
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                items(items = items, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is RecentsItem.Day -> DotMatrixText(
                            text = item.label.uppercase(),
                            pitch = 2.4.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
                        )
                        is RecentsItem.Calls -> {
                            val group = item.group
                            CallGroupRow(
                                group = group,
                                expanded = expandedKey == group.key,
                                onToggle = { expandedKey = if (expandedKey == group.key) null else group.key },
                                onLongPress = { pendingDelete = group },
                                onDeleteCall = { entry -> delete(listOf(entry.id)) },
                                notes = noteById,
                                onEditNote = { entry -> editingNote = entry },
                                onDeleteNote = { note -> scope.launch { CallNotesRepository.delete(context, note) } }
                            )
                        }
                    }
                }
            }
        }
    }

    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        // Sideways: title, search and filters in a side column so the list keeps the full height.
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                headerPane()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                listPane()
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            headerPane()
            listPane()
        }
    }

    editingNote?.let { entry ->
        val existing = noteById[entry.id]
        NoteEditorDialog(
            initial = existing?.text.orEmpty(),
            subtitle = "${entry.callerName ?: entry.number} · ${shortWhen(entry.date)}",
            onSave = { text ->
                editingNote = null
                scope.launch { CallNotesRepository.saveForCall(context, entry, text) }
            },
            onDismiss = { editingNote = null },
            onDelete = if (existing == null) null else {
                {
                    editingNote = null
                    scope.launch { CallNotesRepository.delete(context, existing) }
                }
            }
        )
    }

    pendingDelete?.let { pending ->
        val who = pending.latest.callerName ?: pending.latest.number
        val n = pending.count
        val title = pluralStringResource(R.plurals.recents_delete_title, n, n)
        val body = pluralStringResource(R.plurals.recents_delete_body, n, n, who)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(title, fontFamily = FontFamily.Monospace) },
            text = { Text(body, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(pending.calls.map { it.id })
                }) {
                    Text(stringResource(R.string.delete).uppercase(), color = MaterialTheme.colorScheme.tertiary, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel).uppercase(), color = MaterialTheme.colorScheme.onSurface, letterSpacing = 1.sp)
                }
            }
        )
    }
}

@Composable
private fun EmptyRecents(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Call,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun callTypeIcon(type: Int): Pair<ImageVector, Color> = when (type) {
    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE ->
        Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
    CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.tertiary
    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE ->
        Icons.Default.Block to MaterialTheme.colorScheme.tertiary
    else -> Icons.Default.CallEnd to MaterialTheme.colorScheme.onSurfaceVariant
}

@StringRes
private fun callTypeLabel(type: Int): Int = when (type) {
    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> R.string.call_type_incoming
    CallLog.Calls.OUTGOING_TYPE -> R.string.call_type_outgoing
    CallLog.Calls.MISSED_TYPE -> R.string.call_type_missed
    CallLog.Calls.REJECTED_TYPE -> R.string.call_type_rejected
    CallLog.Calls.BLOCKED_TYPE -> R.string.call_type_blocked
    CallLog.Calls.VOICEMAIL_TYPE -> R.string.voicemail
    else -> R.string.call
}

// Each row shows its date and time (e.g. "24 Oct, 14:02") as well as the day headers.
// Built per call so month names follow a language change in Settings.
private fun shortWhen(date: Date): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(date)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallGroupRow(
    group: CallGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onDeleteCall: (CallLogEntry) -> Unit,
    notes: Map<String, CallNote> = emptyMap(),
    onEditNote: (CallLogEntry) -> Unit = {},
    onDeleteNote: (CallNote) -> Unit = {}
) {
    val context = LocalContext.current
    val latest = group.latest
    val missed = latest.callType == CallLog.Calls.MISSED_TYPE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (expanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CallerAvatar(
                name = latest.callerName,
                number = latest.number,
                size = 40.dp,
                spam = group.isSpam,
                photoUri = latest.photoUri,
                pill = true
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = latest.callerName ?: latest.number,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (missed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (group.count > 1) {
                        Text(
                            text = pluralStringResource(R.plurals.recents_group_count, group.count, group.count),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    group.calls.take(3).forEach { call ->
                        val (icon, tint) = callTypeIcon(call.callType)
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    if (group.isSpam) {
                        Text(
                            text = stringResource(R.string.spam).uppercase() + " · ",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    val whenStr = shortWhen(latest.date)
                    Text(
                        text = latest.numberLabel?.let { "$whenStr · $it" } ?: whenStr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // The newest note in the group, first line only.
                group.calls.firstNotNullOfOrNull { notes[it.id] }?.let { note ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = stringResource(R.string.call_note_has_note),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = note.firstLine,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (group.hasRecording) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.recording_available),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(onClick = { placeCall(context, latest.number) }) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = stringResource(R.string.call),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (latest.callerName != null) {
                    Text(
                        text = latest.number,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
                group.calls.forEach { call ->
                    CallDetailLine(
                        call = call,
                        onDelete = { onDeleteCall(call) },
                        note = notes[call.id],
                        onEditNote = { onEditNote(call) },
                        onDeleteNote = onDeleteNote
                    )
                }
                // WhatsApp chat / calls for a contact on it, else "Open chat" (wa.me) on tap.
                com.example.allinonedailer.ui.components.WhatsAppActionRow(
                    number = latest.number,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                )
                BlockActionRow(number = latest.number)
            }
        }
    }
}

@Composable
private fun CallDetailLine(
    call: CallLogEntry,
    onDelete: () -> Unit,
    note: CallNote? = null,
    onEditNote: () -> Unit = {},
    onDeleteNote: (CallNote) -> Unit = {}
) {
    val (icon, tint) = callTypeIcon(call.callType)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(callTypeLabel(call.callType)).uppercase(),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = tint
                )
                Text(
                    text = buildString {
                        append(shortWhen(call.date))
                        if (call.showsDuration()) append(" · ").append(
                            formatCallDuration(
                                call.durationSeconds,
                                stringResource(R.string.duration_hm),
                                stringResource(R.string.duration_ms),
                                stringResource(R.string.duration_s)
                            )
                        )
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (note == null) {
                TextButton(onClick = onEditNote) {
                    Text(
                        text = stringResource(R.string.call_note_add),
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_call),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        call.recordingPath?.let { path -> AudioPlayerWidget(filePath = path) }
        note?.let { CallNoteBlock(it, onEdit = onEditNote, onDelete = { onDeleteNote(it) }) }
    }
}

/** The full note under its call, with Edit / Delete. */
@Composable
private fun CallNoteBlock(note: CallNote, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onEdit)
            .padding(start = 14.dp, top = 10.dp, end = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Default.EditNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = note.text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit) {
                Text(
                    stringResource(R.string.call_note_edit),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.call_note_delete),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun BlockActionRow(number: String) {
    val context = LocalContext.current
    val repo = remember { CallerIdRepository(context) }
    // Only the exact-number blocklist can be undone here; prefix rules live in Block rules.
    var blocked by remember(number) { mutableStateOf(repo.isNumberBlocked(number)) }
    var reported by remember(number) { mutableStateOf(repo.isReportedSpam(number)) }
    val rule = remember(number) { repo.isBlockedByRule(number) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemindMeButton(number)
        ScheduleCallButton(number)
        if (!reported) {
            TextButton(onClick = {
                repo.reportSpam(number)
                reported = true
                blocked = true
            }) {
                Text(
                    text = stringResource(R.string.report_spam).uppercase(),
                    letterSpacing = 1.sp,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        if (rule != null && !blocked) {
            Text(
                text = rule.uppercase(),
                letterSpacing = 1.sp,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        } else TextButton(
            onClick = {
                if (blocked) {
                    repo.unblockNumber(number) // also drops a spam report
                    blocked = false
                    reported = false
                } else {
                    repo.blockNumber(number)
                    blocked = true
                }
            }
        ) {
            Icon(
                imageVector = if (blocked) Icons.Default.CheckCircle else Icons.Default.Block,
                contentDescription = null,
                tint = if (blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(if (blocked) R.string.unblock else R.string.block_number).uppercase(),
                letterSpacing = 1.sp,
                fontSize = 12.sp,
                color = if (blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun AudioPlayerWidget(filePath: String) {
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(filePath) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.recording).uppercase(), fontSize = 11.sp, letterSpacing = 1.sp)
        }

        IconButton(onClick = {
            if (isPlaying) {
                mediaPlayer?.pause()
                isPlaying = false
            } else {
                try {
                    if (mediaPlayer == null) {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(filePath)
                            prepare()
                            setOnCompletionListener {
                                isPlaying = false
                            }
                        }
                    }
                    mediaPlayer?.start()
                    isPlaying = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun placeCall(context: Context, number: String) {
    if (CallPlacer.placeCall(context, number) == CallPlacer.Outcome.FAILED) {
        Toast.makeText(context, R.string.call_failed, Toast.LENGTH_SHORT).show()
    }
}
