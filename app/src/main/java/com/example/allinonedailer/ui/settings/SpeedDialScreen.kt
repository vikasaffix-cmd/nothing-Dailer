package com.example.allinonedailer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.SpeedDialRepository
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.dialer.SpeedDialDialogs
import com.example.allinonedailer.ui.dialer.label
import com.example.allinonedailer.ui.dialer.openVoicemailSettings
import com.example.allinonedailer.ui.dialer.rememberSpeedDialState
import com.example.allinonedailer.ui.dialer.voicemailNumber
import com.example.allinonedailer.ui.theme.NothingRed
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource

/** Speed-dial keys: 1 is voicemail; 2–9 can each hold one contact number. */
@Composable
fun SpeedDialScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state = rememberSpeedDialState()
    val voicemail = remember { voicemailNumber(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
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
            DotMatrixText(text = stringResource(R.string.speed_dial_title), pitch = 4.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.speed_dial_help),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

            KeyRow(
                key = 1,
                title = stringResource(R.string.voicemail),
                subtitle = voicemail ?: stringResource(R.string.speed_dial_no_voicemail),
                warn = voicemail == null,
                onClick = { openVoicemailSettings(context) }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            for (key in SpeedDialRepository.KEYS) {
                val entry = state.entries[key]
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                KeyRow(
                    key = key,
                    title = entry?.label() ?: stringResource(R.string.speed_dial_not_set),
                    subtitle = entry?.number ?: stringResource(R.string.speed_dial_tap_to_choose),
                    dimmed = entry == null,
                    photo = entry?.let { { CallerAvatar(name = it.name, number = it.number, photoUri = it.photoUri, size = 30.dp, pill = true) } },
                    onClick = { state.assigningKey = key }
                ) {
                    if (entry != null) {
                        IconButton(onClick = { state.clear(key) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    SpeedDialDialogs(state)
}

@Composable
private fun KeyRow(
    key: Int,
    title: String,
    subtitle: String,
    dimmed: Boolean = false,
    warn: Boolean = false,
    photo: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            DotMatrixText(text = key.toString(), pitch = 3.dp, color = if (dimmed) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onBackground)
        }
        Spacer(modifier = Modifier.width(14.dp))
        if (photo != null) {
            photo()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = if (warn) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        trailing()
    }
}
