package com.example.allinonedailer.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.RecordingRepository

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val recordingRepo = remember { RecordingRepository(context) }
    val callerIdRepo = remember { CallerIdRepository(context) }

    var autoRecordEnabled by remember { mutableStateOf(recordingRepo.isAutoRecordEnabled()) }
    var callerIdEnabled by remember { mutableStateOf(callerIdRepo.isCallerIdEnabled()) }
    var spamBlockEnabled by remember { mutableStateOf(callerIdRepo.isSpamBlockEnabled()) }
    var onlineLookupEnabled by remember { mutableStateOf(callerIdRepo.isOnlineLookupEnabled()) }
    var endpoint by remember { mutableStateOf(callerIdRepo.getLookupEndpoint()) }
    var blockedCount by remember { mutableIntStateOf(callerIdRepo.getBlockedNumbers().size) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Dialer Settings",
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
                    text = "🛡️ Ad-free & on-device",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "No ads, no bundled tracking. Recordings and your blocklist stay in local storage on this device.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        // Auto call recording
        SettingSwitchRow(
            title = "Auto call recording",
            subtitle = "Record connected calls automatically. You are responsible for obtaining consent where your local law requires it.",
            checked = autoRecordEnabled,
            onCheckedChange = {
                autoRecordEnabled = it
                recordingRepo.setAutoRecordEnabled(it)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Caller ID
        SettingSwitchRow(
            title = "Caller ID",
            subtitle = "Identify incoming numbers using your contacts and, if enabled below, an online lookup service.",
            checked = callerIdEnabled,
            onCheckedChange = {
                callerIdEnabled = it
                callerIdRepo.setCallerIdEnabled(it)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Spam blocking
        SettingSwitchRow(
            title = "Block flagged spam calls",
            subtitle = "Auto-decline numbers on your blocklist, plus numbers an enabled lookup service flags as spam.",
            checked = spamBlockEnabled,
            onCheckedChange = {
                spamBlockEnabled = it
                callerIdRepo.setSpamBlockEnabled(it)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Online lookup (opt-in; requires an endpoint)
        SettingSwitchRow(
            title = "Online number lookup",
            subtitle = "Optional. Sends unknown numbers to a lookup endpoint you configure. The app has no bundled caller-ID database.",
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
                label = { Text("Lookup endpoint (https://…)") },
                placeholder = { Text("https://your-backend.example.com/") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Blocklist management
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = "Blocked numbers",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = if (blockedCount == 0) "No numbers blocked" else "$blockedCount blocked",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (blockedCount > 0) {
                TextButton(onClick = {
                    callerIdRepo.getBlockedNumbers().forEach { callerIdRepo.unblockNumber(it) }
                    blockedCount = 0
                }) {
                    Text("Clear all")
                }
            }
        }
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
