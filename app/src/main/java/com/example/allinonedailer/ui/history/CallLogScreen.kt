package com.example.allinonedailer.ui.history

import android.media.MediaPlayer
import android.provider.CallLog
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallLogRepository
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.ui.components.CallerAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun CallLogScreen() {
    val context = LocalContext.current
    // Start from the last loaded list so Recents opens instantly, then refresh.
    var callLogs by remember { mutableStateOf(CallLogRepository.lastResult) }
    var loaded by remember { mutableStateOf(CallLogRepository.lastResult.isNotEmpty()) }

    LaunchedEffect(Unit) {
        callLogs = withContext(Dispatchers.IO) { CallLogRepository(context).getCallLogs() }
        loaded = true
    }

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize())
    } else if (callLogs.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No recent calls found",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(callLogs) { entry ->
                CallLogItemRow(entry = entry)
            }
        }
    }
}

@Composable
private fun CallLogItemRow(entry: CallLogEntry) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CallerAvatar(
                        name = entry.callerName,
                        number = entry.number,
                        size = 40.dp,
                        spam = entry.isSpam
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Call Type Icon
                    val (icon, tint) = when (entry.callType) {
                        CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
                        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
                        CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.tertiary
                        else -> Icons.Default.CallEnd to MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = entry.callerName ?: entry.number,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.isSpam) {
                                Text(
                                    text = "⚠ SPAM ",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(entry.date)
                            Text(
                                text = entry.numberLabel?.let { "$dateStr · $it" } ?: dateStr,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Recording Available Indicator Badge
                if (entry.recordingPath != null) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Recording Available",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded actions: recording playback (if any) + block/unblock
            AnimatedVisibility(visible = expanded) {
                Column {
                    entry.recordingPath?.let { path ->
                        AudioPlayerWidget(filePath = path)
                    }
                    BlockActionRow(number = entry.number)
                }
            }
        }
    }
}

@Composable
private fun BlockActionRow(number: String) {
    val context = LocalContext.current
    val repo = remember { CallerIdRepository(context) }
    var blocked by remember(number) { mutableStateOf(repo.isBlocked(number)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = {
                if (blocked) {
                    repo.unblockNumber(number)
                    blocked = false
                } else {
                    repo.blockNumber(number)
                    blocked = true
                }
            }
        ) {
            Icon(
                imageVector = if (blocked) Icons.Default.CheckCircle else Icons.Default.Block,
                contentDescription = null,
                tint = if (blocked) Color(0xFF4CAF50) else Color(0xFFD32F2F),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (blocked) "Unblock" else "Block number",
                color = if (blocked) Color(0xFF4CAF50) else Color(0xFFD32F2F)
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

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🎵 Call Recording File (.m4a)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

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
                    contentDescription = "Play/Pause Recording",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
