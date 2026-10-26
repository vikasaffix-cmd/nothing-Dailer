package com.example.allinonedailer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.SmartSim

/** Settings › Smart SIM: the switch and the pinned contacts, each with a clear button. */
@Composable
fun SmartSimSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SmartSim.isEnabled(context)) }
    var pins by remember { mutableStateOf(SmartSim.pins(context)) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = stringResource(R.string.smart_sim_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    text = stringResource(R.string.smart_sim_summary),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                SmartSim.setEnabled(context, it)
            })
        }

        AnimatedVisibility(visible = enabled) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.smart_sim_pinned_header),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
                if (pins.isEmpty()) {
                    Text(
                        text = stringResource(R.string.smart_sim_pinned_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // A contact with several numbers has one pin per number; show it once.
                pins.groupBy { it.name to it.simId }.forEach { (nameAndSim, group) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = nameAndSim.first, fontSize = 15.sp)
                            Text(
                                text = group.first().simLabel,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            group.forEach { SmartSim.clearPin(context, it.key) }
                            pins = SmartSim.pins(context)
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.smart_sim_clear_pin, nameAndSim.first),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
