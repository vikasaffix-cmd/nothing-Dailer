package com.example.allinonedailer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.service.GlyphController
import com.example.allinonedailer.service.GlyphPattern
import com.example.allinonedailer.service.GlyphSettings
import com.example.allinonedailer.ui.theme.DialerTheme

@StringRes
fun GlyphPattern.label(): Int = when (this) {
    GlyphPattern.PULSE -> R.string.glyph_pattern_pulse
    GlyphPattern.RIPPLE -> R.string.glyph_pattern_ripple
    GlyphPattern.SPIN -> R.string.glyph_pattern_spin
    GlyphPattern.HEARTBEAT -> R.string.glyph_pattern_heartbeat
    GlyphPattern.FLASH -> R.string.glyph_pattern_flash
}

/**
 * Settings › Glyph lights (Nothing phones with a Glyph Matrix): master switch,
 * which events light up, and the ringing pattern for everyone and for
 * favourites. Tapping a pattern previews it on the back of the phone.
 */
@Composable
fun GlyphLightsSettings() {
    val context = LocalContext.current
    val supported = GlyphController.isSupported
    var enabled by remember { mutableStateOf(supported && GlyphSettings.isEnabled(context)) }
    var incoming by remember { mutableStateOf(GlyphSettings.isIncomingEnabled(context)) }
    var missed by remember { mutableStateOf(GlyphSettings.isMissedEnabled(context)) }
    var timer by remember { mutableStateOf(GlyphSettings.isCallTimerEnabled(context)) }
    var pattern by remember { mutableStateOf(GlyphSettings.pattern(context)) }
    var favourites by remember { mutableStateOf(GlyphSettings.favouritesPattern(context)) }

    Column {
        GlyphSwitchRow(
            title = stringResource(R.string.glyph_lights),
            subtitle = stringResource(if (supported) R.string.glyph_lights_summary else R.string.glyph_lights_unsupported),
            checked = enabled,
            enabled = supported
        ) {
            enabled = it
            GlyphSettings.setBoolean(context, GlyphSettings.KEY_ENABLED, it)
        }

        AnimatedVisibility(visible = enabled) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                GlyphSwitchRow(
                    title = stringResource(R.string.glyph_incoming),
                    subtitle = stringResource(R.string.glyph_incoming_summary),
                    checked = incoming
                ) {
                    incoming = it
                    GlyphSettings.setBoolean(context, GlyphSettings.KEY_INCOMING, it)
                }
                AnimatedVisibility(visible = incoming) {
                    Column {
                        PatternPicker(stringResource(R.string.glyph_pattern_everyone), pattern) {
                            pattern = it
                            GlyphSettings.setPattern(context, GlyphSettings.KEY_PATTERN, it)
                            GlyphController.preview(context, it)
                        }
                        PatternPicker(stringResource(R.string.glyph_pattern_favourites), favourites) {
                            favourites = it
                            GlyphSettings.setPattern(context, GlyphSettings.KEY_FAVOURITES_PATTERN, it)
                            GlyphController.preview(context, it)
                        }
                        Text(
                            text = stringResource(R.string.glyph_pattern_contact_hint),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
                GlyphSwitchRow(
                    title = stringResource(R.string.glyph_missed),
                    subtitle = stringResource(R.string.glyph_missed_summary),
                    checked = missed
                ) {
                    missed = it
                    GlyphSettings.setBoolean(context, GlyphSettings.KEY_MISSED, it)
                }
                GlyphSwitchRow(
                    title = stringResource(R.string.glyph_call_timer),
                    subtitle = stringResource(R.string.glyph_call_timer_summary),
                    checked = timer
                ) {
                    timer = it
                    GlyphSettings.setBoolean(context, GlyphSettings.KEY_CALL_TIMER, it)
                }
                Text(
                    text = stringResource(R.string.glyph_lights_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }
        }
    }
}

/**
 * Contact long-press popup: this person's ringing pattern ("Default" follows
 * Settings). Draws nothing unless Glyph lights are on for this phone.
 */
@Composable
fun GlyphContactPatternRow(number: String, pillColor: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!GlyphController.isSupported || !GlyphSettings.isEnabled(context) || number.isBlank()) return
    var chosen by remember(number) { mutableStateOf(GlyphSettings.contactPattern(context, number)) }
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.glyph_contact_pattern),
            fontSize = 13.sp,
            color = DialerTheme.colors.ink.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PatternChip(stringResource(R.string.glyph_pattern_default), chosen == null, pillColor, DialerTheme.colors.ink) {
                chosen = null
                GlyphSettings.setContactPattern(context, number, null)
            }
            GlyphPattern.entries.forEach { option ->
                PatternChip(stringResource(option.label()), chosen == option, pillColor, DialerTheme.colors.ink) {
                    chosen = option
                    GlyphSettings.setContactPattern(context, number, option)
                    GlyphController.preview(context, option)
                }
            }
        }
    }
}

@Composable
private fun PatternPicker(title: String, selected: GlyphPattern, onPick: (GlyphPattern) -> Unit) {
    Text(
        text = title,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlyphPattern.entries.forEach { option ->
            PatternChip(
                stringResource(option.label()), option == selected,
                MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface
            ) { onPick(option) }
        }
    }
}

@Composable
private fun PatternChip(text: String, on: Boolean, background: Color, textColor: Color, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (on) MaterialTheme.colorScheme.onTertiary else textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) MaterialTheme.colorScheme.tertiary else background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun GlyphSwitchRow(
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
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
