package com.example.allinonedailer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.allinonedailer.R
import com.example.allinonedailer.service.CallPill

/**
 * Settings › Call pill: incoming calls as a pill over other apps. Turning it
 * on without "Display over other apps" opens the system screen for it; the
 * user grants it there. The switch shows on only when both are set.
 */
@Composable
fun CallPillSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(CallPill.isEnabled(context)) }
    var permitted by remember { mutableStateOf(CallPill.hasPermission(context)) }
    // Back from the system screen: pick up the new permission state.
    LifecycleResumeEffect(Unit) {
        permitted = CallPill.hasPermission(context)
        onPauseOrDispose { }
    }
    val openPermission = { context.startActivity(CallPill.permissionIntent(context)) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.call_pill_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = stringResource(R.string.call_pill_summary),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled && permitted,
                onCheckedChange = { on ->
                    enabled = on
                    CallPill.setEnabled(context, on)
                    if (on && !permitted) openPermission()
                }
            )
        }
        if (enabled && !permitted) {
            Text(
                text = stringResource(R.string.call_pill_needs_permission),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .clickable { openPermission() }
                    .padding(vertical = 4.dp)
            )
        }
    }
}
