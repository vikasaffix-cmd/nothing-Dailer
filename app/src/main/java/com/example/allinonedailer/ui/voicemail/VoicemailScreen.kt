package com.example.allinonedailer.ui.voicemail

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.CallerInfo
import com.example.allinonedailer.data.VoicemailFile
import com.example.allinonedailer.data.VoicemailRepository
import com.example.allinonedailer.data.TranscriptCache
import com.example.allinonedailer.data.VoicemailSearch
import com.example.allinonedailer.data.VoicemailTranscript
import com.example.allinonedailer.data.VoicemailTranscription
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.collectAsState
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.example.allinonedailer.ui.theme.DialerTheme

/** A voicemail file with the contact it came from, when the file name has a number we know. */
private data class VoicemailRow(val file: VoicemailFile, val caller: CallerInfo?, val heard: Boolean) {
    val key: String get() = file.uri.toString()
    val label: String get() = caller?.name ?: file.phoneNumber ?: file.fileName
}

/**
 * Voicemail inbox: audio files in a folder the user chose, saved from a
 * call-forwarding voicemail service. The app doesn't answer or record calls
 * for this; it only plays the files.
 *
 * [onOpenKeypad] puts a forwarding code on the keypad (the user presses call).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailScreen(onBack: () -> Unit, onOpenKeypad: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VoicemailRepository(context) }
    val callerIdRepo = remember { CallerIdRepository(context) }

    var hasFolder by remember { mutableStateOf(repo.hasFolderAccess()) }
    var folderLabel by remember { mutableStateOf(repo.folderLabel()) }
    var rows by remember { mutableStateOf<List<VoicemailRow>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<VoicemailRow?>(null) }

    val player = remember { VoicemailPlayer(context) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var totalMs by remember { mutableIntStateOf(0) }
    var earpiece by rememberSaveable { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }

    // On-device transcripts (data/VoicemailTranscription.kt), filled in the background.
    val transcriptCache = remember { TranscriptCache.create(context) }
    val transcriptVersion by TranscriptCache.version.collectAsState()
    var transcripts by remember { mutableStateOf<Map<String, VoicemailTranscript>>(emptyMap()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var micGranted by remember { mutableStateOf(VoicemailTranscription.hasMicPermission(context)) }
    var pendingRetry by remember { mutableStateOf<Pair<VoicemailRow, String?>?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) {
            pendingRetry?.let { (row, language) -> VoicemailTranscription.retry(context, row.file, language) }
            VoicemailTranscription.enqueueAll(context)
        }
        pendingRetry = null
    }
    fun retryTranscript(row: VoicemailRow, language: String?) {
        if (micGranted) {
            VoicemailTranscription.retry(context, row.file, language)
        } else {
            pendingRetry = row to language
            askMic.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopPlayer() {
        player.release()
        playingKey = null
        isPlaying = false
        positionMs = 0
        totalMs = 0
    }

    fun markHeard(row: VoicemailRow) {
        if (row.heard) return
        repo.markHeard(row.file.uri)
        rows = rows?.map { if (it.key == row.key) it.copy(heard = true) else it }
    }

    fun togglePlay(row: VoicemailRow) {
        if (playingKey == row.key && player.isLoaded) {
            isPlaying = player.toggle()
            return
        }
        stopPlayer()
        val ok = player.load(row.file.uri, earpiece, onComplete = {
            isPlaying = false
            positionMs = 0
        })
        if (!ok) {
            Toast.makeText(context, R.string.voicemail_cant_play, Toast.LENGTH_SHORT).show()
            return
        }
        playingKey = row.key
        totalMs = player.duration
        isPlaying = player.toggle()
        markHeard(row)
    }

    fun setRoute(toEarpiece: Boolean) {
        earpiece = toEarpiece
        if (player.isLoaded) {
            isPlaying = player.switchRoute(toEarpiece)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            repo.setFolder(uri)
            stopPlayer()
            hasFolder = repo.hasFolderAccess()
            folderLabel = repo.folderLabel()
            rows = null
            reloadKey++
        } catch (e: SecurityException) {
            Toast.makeText(context, R.string.voicemail_folder_access_lost, Toast.LENGTH_LONG).show()
        }
    }
    val chooseFolder = { pickFolder.launch(suggestedFolderUri()) }

    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Load when the screen opens, after a folder is picked, and on pull-to-refresh.
    LaunchedEffect(hasFolder, reloadKey) {
        if (!hasFolder) {
            rows = emptyList()
            refreshing = false
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            val callers = mutableMapOf<String, CallerInfo?>()
            repo.listVoicemails().map { file ->
                val caller = file.phoneNumber?.let { number ->
                    callers.getOrPut(number) {
                        runCatching { callerIdRepo.lookupCallerInfo(number) }.getOrNull()
                    }
                }
                VoicemailRow(file, caller, repo.isHeard(file.uri))
            }
        }
        rows = loaded
        refreshing = false
        if (playingKey != null && loaded.none { it.key == playingKey }) stopPlayer()
        VoicemailTranscription.enqueueAll(context)
    }

    // Re-read transcripts when the list changes or the worker saves one.
    LaunchedEffect(rows, transcriptVersion) {
        val list = rows ?: return@LaunchedEffect
        transcripts = withContext(Dispatchers.IO) {
            list.mapNotNull { row -> transcriptCache.get(row.key, row.file.lastModified)?.let { row.key to it } }.toMap()
        }
    }

    // Tick the elapsed time while something is playing.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!seeking) positionMs = player.position
            delay(200)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (hasFolder) {
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) {
                    Icon(
                        imageVector = if (searching) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = stringResource(if (searching) R.string.vm_ai_close_search else R.string.search),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DotMatrixText(text = stringResource(R.string.voicemail), pitch = 4.dp)
            val unheard = rows?.count { !it.heard } ?: 0
            Text(
                text = if (unheard > 0) pluralStringResource(R.plurals.voicemail_unheard, unheard, unheard).uppercase() else " ",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
            if (searching && hasFolder) {
                VoicemailSearchField(query = query, onQueryChange = { query = it })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (!hasFolder) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                ChooseFolderCard(onChoose = chooseFolder)
                Spacer(modifier = Modifier.height(12.dp))
                SetupCard(repo = repo, initiallyExpanded = true, onOpenCode = { openCode(context, it, onOpenKeypad) })
                Spacer(modifier = Modifier.height(24.dp))
            }
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                reloadKey++
            },
            modifier = Modifier.fillMaxSize()
        ) {
            val list = rows?.let { all ->
                if (query.isBlank()) all
                else all.filter { row ->
                    val t = transcripts[row.key]
                    VoicemailSearch.matches(query, row.label, row.file.phoneNumber, row.file.fileName, t?.summary, t?.text)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                item(key = "folder") {
                    FolderLine(label = folderLabel ?: stringResource(R.string.voicemail_chosen_folder), onChange = chooseFolder)
                }
                if (VoicemailTranscription.isEnabled(context) && rows?.isNotEmpty() == true) {
                    item(key = "transcribe_notice") {
                        TranscribeNotice(micGranted = micGranted, onAllowMic = { askMic.launch(android.Manifest.permission.RECORD_AUDIO) })
                    }
                }
                when {
                    list == null -> Unit
                    list.isEmpty() && query.isNotBlank() -> item(key = "no_matches") {
                        NoSearchMatches()
                    }
                    list.isEmpty() -> item(key = "empty") {
                        EmptyState(folderLabel = folderLabel)
                    }
                    else -> items(items = list, key = { it.key }) { row ->
                        val active = playingKey == row.key
                        VoicemailRowView(
                            row = row,
                            expanded = expandedKey == row.key,
                            playing = active && isPlaying,
                            positionMs = if (active) {
                                if (seeking) (seekFraction * totalMs).toInt() else positionMs
                            } else 0,
                            totalMs = if (active && totalMs > 0) totalMs else row.file.durationMs.toInt(),
                            earpiece = earpiece,
                            onToggleExpand = {
                                if (expandedKey == row.key) {
                                    expandedKey = null
                                    if (active) stopPlayer()
                                } else {
                                    if (playingKey != null) stopPlayer()
                                    expandedKey = row.key
                                }
                            },
                            onPlayPause = { togglePlay(row) },
                            onSeek = { fraction ->
                                seeking = true
                                seekFraction = fraction
                            },
                            onSeekFinished = {
                                if (active) {
                                    val target = (seekFraction * totalMs).toInt()
                                    player.seekTo(target)
                                    positionMs = target
                                }
                                seeking = false
                            },
                            onToggleRoute = { setRoute(!earpiece) },
                            onCallBack = { number ->
                                stopPlayer()
                                if (CallPlacer.placeCall(context, number) == CallPlacer.Outcome.FAILED) {
                                    Toast.makeText(context, R.string.no_app_can_handle, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onMessage = { number ->
                                startSafely(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
                            },
                            onShare = { share(context, row) },
                            onDelete = { pendingDelete = row },
                            transcript = transcripts[row.key],
                            onRetryTranscript = { language -> retryTranscript(row, language) },
                            onAddReminder = { t -> addReminder(context, row.label, t) }
                        )
                    }
                }
                item(key = "setup") {
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupCard(
                        repo = repo,
                        initiallyExpanded = list?.isEmpty() == true,
                        onOpenCode = { openCode(context, it, onOpenKeypad) }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.voicemail_delete_title), fontFamily = FontFamily.Monospace) },
            text = {
                Text(
                    stringResource(R.string.voicemail_delete_body, row.label, formatDate(row.file.timestamp), folderLabel ?: stringResource(R.string.voicemail_the_folder)),
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (playingKey == row.key) stopPlayer()
                    if (expandedKey == row.key) expandedKey = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { repo.delete(row.file) }
                        if (ok) {
                            transcriptCache.remove(row.key)
                            rows = rows?.filterNot { it.key == row.key }
                        } else {
                            Toast.makeText(context, R.string.voicemail_cant_delete, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete).uppercase(), color = MaterialTheme.colorScheme.tertiary, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel).uppercase(), color = MaterialTheme.colorScheme.onBackground, letterSpacing = 1.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun VoicemailRowView(
    row: VoicemailRow,
    expanded: Boolean,
    playing: Boolean,
    positionMs: Int,
    totalMs: Int,
    earpiece: Boolean,
    onToggleExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onToggleRoute: () -> Unit,
    onCallBack: (String) -> Unit,
    onMessage: (String) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    transcript: VoicemailTranscript?,
    onRetryTranscript: (String?) -> Unit,
    onAddReminder: (VoicemailTranscript) -> Unit
) {
    val number = row.file.phoneNumber
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Unheard dot, as in the iPhone inbox.
            Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.CenterStart) {
                if (!row.heard) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }
            CallerAvatar(
                name = row.caller?.name,
                number = number ?: row.file.fileName,
                size = 40.dp,
                spam = row.caller?.isSpam == true,
                photoUri = row.caller?.photoUri?.toString(),
                pill = true
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (row.heard) FontWeight.Medium else FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val second = if (row.caller?.name != null) number else row.caller?.numberLabel
                Text(
                    text = listOfNotNull(second, formatTime(row.file.durationMs.toInt())).joinToString("  ·  "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TranscriptPreview(transcript)
            }
            Text(
                text = formatShortDate(row.file.timestamp, stringResource(R.string.yesterday)),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                Text(
                    text = formatDate(row.file.timestamp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val fraction = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
                Slider(
                    value = fraction,
                    onValueChange = onSeek,
                    onValueChangeFinished = onSeekFinished,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.tertiary,
                        activeTrackColor = MaterialTheme.colorScheme.tertiary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = formatTime(positionMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "-" + formatTime((totalMs - positionMs).coerceAtLeast(0)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                            .clickable(onClick = onPlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                            tint = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    PillButton(
                        icon = if (earpiece) Icons.Default.PhoneInTalk else Icons.AutoMirrored.Filled.VolumeUp,
                        label = stringResource(if (earpiece) R.string.audio_earpiece else R.string.audio_speaker).uppercase(),
                        onClick = onToggleRoute
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (number != null) {
                        ActionIcon(Icons.Default.Call, stringResource(R.string.call_back)) { onCallBack(number) }
                        ActionIcon(Icons.AutoMirrored.Filled.Message, stringResource(R.string.message)) { onMessage(number) }
                    }
                    ActionIcon(Icons.Default.Share, stringResource(R.string.share), onShare)
                    ActionIcon(Icons.Default.Delete, stringResource(R.string.delete), onDelete)
                }
                TranscriptPanel(
                    rowKey = row.key,
                    transcript = transcript,
                    onRetry = onRetryTranscript,
                    onAddReminder = onAddReminder
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = DialerTheme.colors.hairline)
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 40.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FolderLine(label: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onChange) {
            Text(stringResource(R.string.change).uppercase(), color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ChooseFolderCard(onChoose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.voicemail_choose_folder_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.voicemail_choose_folder_body),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.tertiary)
                .clickable(onClick = onChoose)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(stringResource(R.string.voicemail_choose_folder).uppercase(), color = MaterialTheme.colorScheme.onTertiary, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        }
    }
}

/** Call-forwarding help: GSM codes filled in with the voicemail service's number. */
@Composable
private fun SetupCard(repo: VoicemailRepository, initiallyExpanded: Boolean, onOpenCode: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var serviceNumber by remember { mutableStateOf(repo.getServiceNumber()) }
    val target = serviceNumber.filter { it.isDigit() || it == '+' }
    val shown = target.ifEmpty { stringResource(R.string.voicemail_number_placeholder) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Voicemail, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.voicemail_setup_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!expanded) return@Column

        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.voicemail_setup_body),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = serviceNumber,
                onValueChange = {
                    serviceNumber = it
                    repo.setServiceNumber(it)
                },
                placeholder = { Text(stringResource(R.string.voicemail_service_number).uppercase(), fontSize = 12.sp, letterSpacing = 1.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            val needsNumber = target.isEmpty()
            CodeRow(stringResource(R.string.forward_no_answer), "**61*$shown#", enabled = !needsNumber) { onOpenCode("**61*$target#") }
            CodeRow(stringResource(R.string.forward_busy), "**67*$shown#", enabled = !needsNumber) { onOpenCode("**67*$target#") }
            CodeRow(stringResource(R.string.forward_unreachable), "**62*$shown#", enabled = !needsNumber) { onOpenCode("**62*$target#") }
            CodeRow(stringResource(R.string.forward_cancel_all), "##002#", enabled = true) { onOpenCode("##002#") }
        }
    }
}

@Composable
private fun CodeRow(title: String, code: String, enabled: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onOpen, enabled = enabled) {
            Text(
                stringResource(R.string.keypad).uppercase(),
                color = if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun EmptyState(folderLabel: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Voicemail,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        DotMatrixText(text = stringResource(R.string.voicemail_empty_title), pitch = 3.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, centered = true)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.voicemail_empty_body, folderLabel ?: stringResource(R.string.voicemail_the_folder)),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One MediaPlayer for the screen, playing through the speaker (media) or the
 * earpiece (voice-communication audio routed to the built-in earpiece).
 */
private class VoicemailPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var mp: MediaPlayer? = null
    private var uri: Uri? = null
    private var onComplete: (() -> Unit)? = null
    private var routedToEarpiece = false

    val isLoaded: Boolean get() = mp != null
    val duration: Int get() = runCatching { mp?.duration ?: 0 }.getOrDefault(0).coerceAtLeast(0)
    val position: Int get() = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0)

    fun load(uri: Uri, earpiece: Boolean, onComplete: () -> Unit): Boolean {
        release()
        this.uri = uri
        this.onComplete = onComplete
        return prepare(earpiece, 0)
    }

    private fun prepare(earpiece: Boolean, startAt: Int): Boolean {
        val source = uri ?: return false
        val player = MediaPlayer()
        return try {
            applyRoute(earpiece)
            mp = player.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, source)
                prepare()
                if (startAt > 0) seekTo(startAt)
                setOnCompletionListener {
                    it.seekTo(0)
                    onComplete?.invoke()
                }
            }
            true
        } catch (e: Exception) {
            player.release()
            mp = null
            applyRoute(false)
            false
        }
    }

    /** Plays or pauses; returns whether it is now playing. */
    fun toggle(): Boolean {
        val p = mp ?: return false
        if (p.isPlaying) p.pause() else p.start()
        return p.isPlaying
    }

    fun seekTo(ms: Int) {
        runCatching { mp?.seekTo(ms) }
    }

    /** Audio attributes can't change after prepare, so rebuild the player at the same spot. */
    fun switchRoute(earpiece: Boolean): Boolean {
        val p = mp ?: return false
        val wasPlaying = p.isPlaying
        val at = p.currentPosition
        p.release()
        mp = null
        if (!prepare(earpiece, at)) return false
        if (wasPlaying) mp?.start()
        return mp?.isPlaying == true
    }

    fun release() {
        mp?.release()
        mp = null
        applyRoute(false)
    }

    private fun applyRoute(earpiece: Boolean) {
        val am = audioManager ?: return
        // Never touch routing during a real call.
        if (am.mode == AudioManager.MODE_IN_CALL) return
        if (earpiece) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            routedToEarpiece = true
        } else if (routedToEarpiece) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
            routedToEarpiece = false
        }
    }
}

/** Opens the system folder picker at Download/Voicemail when it exists (else wherever it chooses). */
private fun suggestedFolderUri(): Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Voicemail")

private fun openCode(context: Context, code: String, onOpenKeypad: ((String) -> Unit)?) {
    if (onOpenKeypad != null) onOpenKeypad(code)
    else startSafely(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", code, null)))
}

private fun share(context: Context, row: VoicemailRow) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = row.file.mimeType?.takeIf { it.startsWith("audio/") } ?: "audio/*"
        putExtra(Intent.EXTRA_STREAM, row.file.uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.voicemail_share_subject, row.label))
        clipData = ClipData.newRawUri(row.file.fileName, row.file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startSafely(context, Intent.createChooser(send, context.getString(R.string.voicemail_share)))
}

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_can_handle, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, R.string.not_allowed, Toast.LENGTH_SHORT).show()
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

/** "14:30" today, "Yesterday", "Sat" this week, else "24 Oct". */
private fun formatShortDate(timestamp: Long, yesterday: String): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val days = ((startOfDay(now) - startOfDay(then)) / 86_400_000L).toInt()
    val pattern = when {
        days <= 0 -> "HH:mm"
        days == 1 -> return yesterday
        days < 7 -> "EEE"
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "d MMM"
        else -> "d MMM yy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
