package com.example.allinonedailer.ui.recordings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.RecordingItem
import com.example.allinonedailer.data.RecordingRepository
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource
import com.example.allinonedailer.ui.theme.DialerTheme

/** A recording whose file still exists, with the caller's name when one is known. */
private data class RecordingRow(val item: RecordingItem, val name: String?, val unknown: String) {
    val label: String get() = name ?: item.phoneNumber.ifBlank { unknown }
}

/**
 * Every call recording, newest first, with search, an inline player (one
 * recording at a time), share and delete.
 */
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingRepo = remember { RecordingRepository(context) }
    val callerIdRepo = remember { CallerIdRepository(context) }

    var rows by remember { mutableStateOf<List<RecordingRow>?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<RecordingRow?>(null) }

    // Player state: a single MediaPlayer shared by every row.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var activePath by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var totalMs by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }

    fun stopPlayer() {
        player?.release()
        player = null
        activePath = null
        isPlaying = false
        positionMs = 0
        totalMs = 0
    }

    fun togglePlay(row: RecordingRow) {
        val current = player
        if (current != null && activePath == row.item.filePath) {
            if (current.isPlaying) current.pause() else current.start()
            isPlaying = current.isPlaying
            return
        }
        stopPlayer()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(row.item.filePath)
                prepare()
                setOnCompletionListener {
                    isPlaying = false
                    positionMs = 0
                    it.seekTo(0)
                }
                start()
            }
            player = mp
            activePath = row.item.filePath
            totalMs = mp.duration.coerceAtLeast(0)
            isPlaying = true
        } catch (e: Exception) {
            stopPlayer()
            Toast.makeText(context, R.string.recording_cant_play, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val names = mutableMapOf<String, String?>()
            recordingRepo.getRecordings()
                .filter { File(it.filePath).exists() }
                .sortedByDescending { it.timestamp }
                .map { item ->
                    val name = names.getOrPut(item.phoneNumber) {
                        if (item.phoneNumber.isBlank()) null
                        else runCatching { callerIdRepo.lookupCallerInfo(item.phoneNumber).name }.getOrNull()
                    }
                    RecordingRow(item, name, context.getString(R.string.unknown))
                }
        }
    }

    // Tick the elapsed time while something is playing.
    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            val mp = player ?: break
            if (!seeking) positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
            delay(200)
        }
    }

    val headerPane: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DotMatrixText(text = stringResource(R.string.recordings_title), pitch = 4.dp)
            Spacer(modifier = Modifier.height(14.dp))
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search).uppercase(), letterSpacing = 1.sp, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
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
        }
    }

    val listPane: @Composable () -> Unit = {
        val all = rows
        val visible = remember(all, searchQuery) {
            val q = searchQuery.trim()
            val qDigits = q.filter { it.isDigit() }
            all.orEmpty().filter { row ->
                q.isEmpty() ||
                    row.name?.contains(q, ignoreCase = true) == true ||
                    row.item.phoneNumber.contains(q) ||
                    (qDigits.isNotEmpty() && row.item.phoneNumber.filter { it.isDigit() }.contains(qDigits))
            }
        }

        when {
            all == null -> Unit
            all.isEmpty() -> EmptyState(
                title = stringResource(R.string.recordings_empty_title).uppercase(),
                body = stringResource(R.string.recordings_empty_body)
            )
            visible.isEmpty() -> EmptyState(title = stringResource(R.string.no_matches).uppercase(), body = stringResource(R.string.recordings_no_match, searchQuery.trim()))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                items(items = visible, key = { it.item.filePath }) { row ->
                    val active = activePath == row.item.filePath
                    RecordingRowView(
                        row = row,
                        active = active,
                        playing = active && isPlaying,
                        positionMs = if (active) {
                            if (seeking) (seekFraction * totalMs).toInt() else positionMs
                        } else 0,
                        totalMs = if (active && totalMs > 0) totalMs else row.item.durationSeconds * 1000,
                        onPlayPause = { togglePlay(row) },
                        onSeek = { fraction ->
                            seeking = true
                            seekFraction = fraction
                        },
                        onSeekFinished = {
                            player?.let { mp ->
                                val target = (seekFraction * totalMs).toInt()
                                mp.seekTo(target)
                                positionMs = target
                            }
                            seeking = false
                        },
                        onShare = { shareRecording(context, row) },
                        onDelete = { pendingDelete = row }
                    )
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

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recording_delete_title), fontFamily = FontFamily.Monospace) },
            text = {
                Text(
                    stringResource(R.string.recording_delete_body, row.label, formatDate(row.item.timestamp)),
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (activePath == row.item.filePath) stopPlayer()
                    rows = rows?.filterNot { it.item.filePath == row.item.filePath }
                    scope.launch(Dispatchers.IO) { recordingRepo.deleteRecording(row.item) }
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
private fun RecordingRowView(
    row: RecordingRow,
    active: Boolean,
    playing: Boolean,
    positionMs: Int,
    totalMs: Int,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlayPause)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${formatDate(row.item.timestamp)}  ·  ${formatTime(row.item.durationSeconds * 1000)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (active) {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(positionMs),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = formatTime(totalMs),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = DialerTheme.colors.hairline)
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        DotMatrixText(text = title, pitch = 3.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, centered = true)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = body,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun shareRecording(context: Context, row: RecordingRow) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(row.item.filePath)
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.recording_share_subject, row.label))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.recording_share)))
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, R.string.recording_cant_share, Toast.LENGTH_SHORT).show()
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_share, Toast.LENGTH_SHORT).show()
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
