package com.example.allinonedailer.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.Haptics
import com.example.allinonedailer.data.SimPick
import com.example.allinonedailer.data.SimReason
import com.example.allinonedailer.data.SmartSim
import com.example.allinonedailer.data.WaAction
import com.example.allinonedailer.data.WaKind
import com.example.allinonedailer.data.WhatsApp
import com.example.allinonedailer.service.SimOption
import com.example.allinonedailer.service.callCapableSims
import com.example.allinonedailer.ui.theme.DialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/** What WhatsApp offers for a number: synced rows, or just the wa.me link when installed. */
data class WhatsAppState(val actions: List<WaAction>, val installed: Boolean)

/** Loads [WhatsAppState] on IO; null while loading. With [contactId] it reads that contact's rows. */
@Composable
fun rememberWhatsAppState(number: String, contactId: String? = null): WhatsAppState? {
    val context = LocalContext.current
    val state by produceState<WhatsAppState?>(null, number, contactId) {
        value = withContext(Dispatchers.IO) {
            val installed = WhatsApp.isInstalled(context)
            val actions = when {
                !installed -> emptyList()
                contactId != null -> WhatsApp.actionsForContact(context, contactId, number)
                else -> WhatsApp.actionsForNumber(context, number)
            }
            WhatsAppState(actions, installed)
        }
    }
    return state
}

/**
 * WhatsApp chat / voice / video pills from the contact's synced rows. When the
 * number has none, a single "Open chat in WhatsApp" pill (wa.me) instead.
 * Nothing when WhatsApp isn't installed. A generic chat icon stands in for
 * WhatsApp's logo, which its brand rules don't let us redraw in our style.
 */
@Composable
fun WhatsAppActionRow(
    number: String,
    contactId: String? = null,
    modifier: Modifier = Modifier,
    pillColor: Color = DialerTheme.colors.chip,
    onAction: () -> Unit = {}
) {
    val context = LocalContext.current
    val wa = rememberWhatsAppState(number, contactId) ?: return
    if (!wa.installed) return
    Column(modifier = modifier) {
        Text(
            text = "WhatsApp",
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = DialerTheme.colors.muted,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        if (wa.actions.isEmpty()) {
            WaPill(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = stringResource(R.string.whatsapp_open_chat),
                description = stringResource(R.string.whatsapp_open_chat),
                color = pillColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                onAction()
                openOrToast(context) { WhatsApp.openChat(context, number) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wa.actions.forEach { action ->
                    val (icon, short, full) = when (action.kind) {
                        WaKind.CHAT -> Triple(Icons.AutoMirrored.Filled.Chat, R.string.whatsapp_short_chat, R.string.whatsapp_chat)
                        WaKind.VOICE -> Triple(Icons.Default.Call, R.string.whatsapp_short_voice, R.string.whatsapp_voice_call)
                        WaKind.VIDEO -> Triple(Icons.Default.Videocam, R.string.whatsapp_short_video, R.string.whatsapp_video_call)
                    }
                    WaPill(
                        icon = icon,
                        label = stringResource(short),
                        description = stringResource(full),
                        color = pillColor,
                        modifier = Modifier.weight(1f)
                    ) {
                        onAction()
                        openOrToast(context) { WhatsApp.open(context, action) }
                    }
                }
            }
        }
    }
}

private fun openOrToast(context: Context, open: () -> Boolean) {
    Haptics.play(context, Haptics.Effect.BUTTON)
    if (!open()) Toast.makeText(context, R.string.whatsapp_open_failed, Toast.LENGTH_SHORT).show()
}

@Composable
private fun WaPill(
    icon: ImageVector,
    label: String,
    description: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = DialerTheme.colors.ink, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = DialerTheme.colors.ink
        )
    }
}

/**
 * "Always call with" + Auto / one chip per SIM, for a contact's long-press
 * sheet. Pins apply to all of [numbers]. Hidden on single-SIM phones.
 */
@Composable
fun PinSimRow(name: String, numbers: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sims = remember { callCapableSims(context) }
    if (sims.size < 2 || numbers.isEmpty()) return
    var pinnedId by remember(numbers) { mutableStateOf(SmartSim.pinnedSimId(context, numbers.first())) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.smart_sim_always_use).uppercase(),
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = DialerTheme.colors.muted,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(DialerTheme.colors.chip)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val options: List<SimOption?> = listOf(null) + sims
            options.forEach { sim ->
                val on = sim?.handle?.id == pinnedId
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) DialerTheme.colors.accent else Color.Transparent)
                        .clickable {
                            Haptics.play(context, Haptics.Effect.KEY)
                            pinnedId = sim?.handle?.id
                            SmartSim.setPinned(context, numbers, name, sim)
                            val msg = if (sim == null) context.getString(R.string.smart_sim_unpinned_toast, name)
                            else context.getString(R.string.smart_sim_pinned_toast, name, sim.label)
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (sim != null) {
                        Icon(
                            Icons.Default.SimCard, null,
                            tint = if (on) DialerTheme.colors.onAccent else DialerTheme.colors.muted,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = sim?.label ?: stringResource(R.string.smart_sim_auto),
                        fontSize = 12.sp,
                        color = if (on) DialerTheme.colors.onAccent else DialerTheme.colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** The keypad hint for a smart pick, e.g. "Same network: Jio"; null when nothing to say. */
@Composable
fun smartSimHint(pick: SimPick?, sims: List<SimOption>): String? {
    pick ?: return null
    val label = sims.firstOrNull { it.handle.id == pick.simId }?.label ?: return null
    return when (pick.reason) {
        SimReason.PINNED -> stringResource(R.string.smart_sim_hint_pinned, label)
        SimReason.LAST_USED -> stringResource(R.string.smart_sim_hint_last_used, label)
        SimReason.SAME_NETWORK -> stringResource(R.string.smart_sim_hint_same_network, pick.network ?: label)
    }
}
