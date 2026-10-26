package com.example.allinonedailer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.HapticStrength
import com.example.allinonedailer.data.Haptics
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource

/** Settings › Vibration: master switch, strength, and which events buzz. */
@Composable
fun VibrationSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Haptics.isEnabled(context)) }
    var strength by remember { mutableStateOf(Haptics.strength(context)) }
    var keypad by remember { mutableStateOf(Haptics.isKeypadEnabled(context)) }
    var callEvents by remember { mutableStateOf(Haptics.isCallEventsEnabled(context)) }

    Column {
        VibrationSwitchRow(
            title = stringResource(R.string.vibration),
            subtitle = stringResource(R.string.vibration_summary),
            checked = enabled
        ) {
            enabled = it
            Haptics.setEnabled(context, it)
            Haptics.play(context, Haptics.Effect.BUTTON)
        }

        AnimatedVisibility(visible = enabled) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.vibration_strength),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HapticStrength.entries.forEach { option ->
                        val on = option == strength
                        Text(
                            text = stringResource(option.label),
                            fontSize = 13.sp,
                            color = if (on) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    strength = option
                                    Haptics.setStrength(context, option)
                                    // Let the user feel the new strength.
                                    Haptics.play(context, Haptics.Effect.BUTTON)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                VibrationSwitchRow(
                    title = stringResource(R.string.keypad),
                    subtitle = stringResource(R.string.vibration_keypad_summary),
                    checked = keypad
                ) {
                    keypad = it
                    Haptics.setKeypadEnabled(context, it)
                }
                VibrationSwitchRow(
                    title = stringResource(R.string.vibration_call_events),
                    subtitle = stringResource(R.string.vibration_call_events_summary),
                    checked = callEvents
                ) {
                    callEvents = it
                    Haptics.setCallEventsEnabled(context, it)
                }
            }
        }
    }
}

@Composable
private fun VibrationSwitchRow(
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
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
