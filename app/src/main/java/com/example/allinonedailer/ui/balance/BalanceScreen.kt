package com.example.allinonedailer.ui.balance

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.balance.BalanceSim
import com.example.allinonedailer.data.balance.BalanceSims
import com.example.allinonedailer.data.balance.UssdCode
import com.example.allinonedailer.data.balance.UssdCodes
import com.example.allinonedailer.data.balance.UssdKind
import com.example.allinonedailer.data.balance.UssdResult
import com.example.allinonedailer.data.balance.UssdRunner
import com.example.allinonedailer.data.balance.UssdStore
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.theme.DialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The network's answer to the last code sent on one SIM. */
private sealed interface ReplyState {
    val code: String
    data class Sending(override val code: String, val request: Int) : ReplyState
    data class Done(override val code: String, val result: UssdResult) : ReplyState
    data class TimedOut(override val code: String) : ReplyState
}

/** A code waiting for the user's OK before it is sent. */
private data class PendingSend(val sim: BalanceSim, val code: String, val kind: UssdKind)

private const val REPLY_TIMEOUT_MS = 30_000L

/**
 * Balance & plan: per SIM, the operator's USSD codes. Info codes send with
 * one tap and show the reply here; menus and anything that could buy or
 * change a plan ask first. Dial puts a code on the keypad for the user to send.
 */
@Composable
fun BalanceScreen(onBack: () -> Unit, onDial: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { UssdStore(context) }
    val sims by produceState<List<BalanceSim>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { BalanceSims.active(context) }
    }
    // Codes per SIM store key, re-read after each edit.
    val codes = remember { mutableStateMapOf<String, List<UssdCode>>() }
    LaunchedEffect(sims) {
        sims?.forEach { codes[it.storeKey] = store.codes(it) }
    }
    val replies = remember { mutableStateMapOf<Int, ReplyState>() }
    var requestCounter by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<PendingSend?>(null) }
    var editing by remember { mutableStateOf<Pair<BalanceSim, UssdCode?>?>(null) }

    fun send(sim: BalanceSim, code: String) {
        val request = ++requestCounter
        replies[sim.subId] = ReplyState.Sending(code, request)
        UssdRunner.send(context, sim.subId, code) { result ->
            // Ignore a late answer to a code the user has since replaced.
            val now = replies[sim.subId]
            if (now is ReplyState.Sending && now.request == request) replies[sim.subId] = ReplyState.Done(code, result)
        }
    }

    fun tap(sim: BalanceSim, code: UssdCode) {
        if (code.oneTap) send(sim, code.code) else pending = PendingSend(sim, code.code, code.kind)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.balance_title).uppercase(), pitch = 4.dp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.balance_dial_hint), fontSize = 12.sp, color = DialerTheme.colors.muted)

            val list = sims
            when {
                list == null -> Spacer(Modifier.height(120.dp))
                list.isEmpty() -> Text(
                    stringResource(R.string.balance_no_sims).uppercase(),
                    color = DialerTheme.colors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 40.dp)
                )
                else -> {
                    list.forEach { sim ->
                        val simCodes = codes[sim.storeKey].orEmpty()
                        SimCard(
                            sim = sim,
                            codes = simCodes,
                            reply = replies[sim.subId],
                            onTap = { tap(sim, it) },
                            onDial = { onDial(it) },
                            onEdit = { editing = sim to it },
                            onAdd = { editing = sim to null },
                            onReset = {
                                store.reset(sim)
                                codes[sim.storeKey] = store.codes(sim)
                            },
                            onOption = { base, option ->
                                UssdCodes.chained(base, option)?.let { pending = PendingSend(sim, it, UssdKind.MENU) }
                            },
                            onTimeout = { request ->
                                val now = replies[sim.subId]
                                if (now is ReplyState.Sending && now.request == request) {
                                    replies[sim.subId] = ReplyState.TimedOut(now.code)
                                }
                            }
                        )
                    }
                    if (list.any { s -> codes[s.storeKey].orEmpty().any { !it.verified } }) {
                        Text(
                            stringResource(R.string.balance_unconfirmed_note),
                            fontSize = 11.sp,
                            color = DialerTheme.colors.muted,
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.balance_confirm_title, p.code), fontFamily = FontFamily.Monospace) },
            text = {
                Text(
                    stringResource(if (p.kind == UssdKind.MENU) R.string.balance_confirm_menu else R.string.balance_confirm_action),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    send(p.sim, p.code)
                }) { Text(stringResource(R.string.balance_send)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pending = null
                        onDial(p.code)
                    }) { Text(stringResource(R.string.balance_dial)) }
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    editing?.let { (sim, code) ->
        CodeEditor(
            initial = code,
            onDismiss = { editing = null },
            onSave = { saved ->
                val current = codes[sim.storeKey].orEmpty()
                val updated = if (current.any { it.id == saved.id }) current.map { if (it.id == saved.id) saved else it } else current + saved
                store.save(sim, updated)
                codes[sim.storeKey] = updated
                editing = null
            },
            onDelete = code?.let { existing ->
                {
                    val updated = codes[sim.storeKey].orEmpty().filterNot { it.id == existing.id }
                    store.save(sim, updated)
                    codes[sim.storeKey] = updated
                    editing = null
                }
            }
        )
    }
}

@Composable
private fun SimCard(
    sim: BalanceSim,
    codes: List<UssdCode>,
    reply: ReplyState?,
    onTap: (UssdCode) -> Unit,
    onDial: (String) -> Unit,
    onEdit: (UssdCode) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    onOption: (base: String, option: String) -> Unit,
    onTimeout: (request: Int) -> Unit
) {
    Spacer(Modifier.height(20.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DialerTheme.colors.panel)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotMatrixText(text = sim.name.uppercase(), pitch = 3.dp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sim_slot, sim.slot + 1), fontSize = 12.sp, color = DialerTheme.colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        codes.forEach { code ->
            CodeRow(code, onTap = { onTap(code) }, onDial = { onDial(code.code) }, onEdit = { onEdit(code) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, null, tint = DialerTheme.colors.ink)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.balance_add).uppercase(), color = DialerTheme.colors.ink, fontSize = 12.sp, letterSpacing = 1.sp)
            }
            if (sim.network != null) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.balance_reset), color = DialerTheme.colors.muted, fontSize = 11.sp)
                }
            }
        }
        reply?.let { ReplyPanel(sim, it, onDial, onOption, onTimeout) }
    }
}

@Composable
private fun CodeRow(code: UssdCode, onTap: () -> Unit, onDial: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (code.oneTap) DialerTheme.colors.ink.copy(alpha = 0.6f) else DialerTheme.colors.accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onTap)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(codeLabel(code), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code.code, fontSize = 12.sp, color = DialerTheme.colors.muted, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (code.oneTap) R.string.balance_one_tap else R.string.balance_asks_first).uppercase(),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = if (code.oneTap) DialerTheme.colors.muted else DialerTheme.colors.accent
                )
                if (!code.verified) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.balance_unconfirmed).uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, color = DialerTheme.colors.accent)
                }
            }
        }
        IconButton(onClick = onDial) { Icon(Icons.Default.Dialpad, stringResource(R.string.balance_dial), tint = DialerTheme.colors.muted) }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.balance_edit), tint = DialerTheme.colors.muted) }
    }
}

@Composable
private fun ReplyPanel(
    sim: BalanceSim,
    reply: ReplyState,
    onDial: (String) -> Unit,
    onOption: (base: String, option: String) -> Unit,
    onTimeout: (request: Int) -> Unit
) {
    if (reply is ReplyState.Sending) {
        LaunchedEffect(reply.request) {
            delay(REPLY_TIMEOUT_MS)
            onTimeout(reply.request)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        when (reply) {
            is ReplyState.Sending -> Text(stringResource(R.string.balance_sending, sim.name), fontSize = 13.sp, color = DialerTheme.colors.muted)
            is ReplyState.TimedOut -> FailureText(stringResource(R.string.balance_timeout))
            is ReplyState.Done -> when (val r = reply.result) {
                is UssdResult.Reply -> {
                    Text(
                        stringResource(R.string.balance_reply_from, sim.name).uppercase(),
                        fontSize = 10.sp, letterSpacing = 1.sp, color = DialerTheme.colors.muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(r.text, fontSize = 14.sp, color = DialerTheme.colors.ink, fontFamily = FontFamily.Monospace)
                    if (UssdCodes.menuOptions(r.text).isNotEmpty() && reply.code.startsWith("*")) {
                        MenuAnswer(onSend = { onOption(reply.code, it) })
                    }
                }
                is UssdResult.Failed -> FailureText(stringResource(R.string.balance_failed))
                UssdResult.NoPermission -> FailureText(stringResource(R.string.balance_no_permission))
            }
        }
        if (reply !is ReplyState.Sending) {
            TextButton(onClick = { onDial(reply.code) }, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Default.Dialpad, null, tint = DialerTheme.colors.ink)
                Spacer(Modifier.width(6.dp))
                Text("${stringResource(R.string.balance_dial).uppercase()} ${reply.code}", color = DialerTheme.colors.ink, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FailureText(text: String) {
    Text(text, fontSize = 13.sp, color = DialerTheme.colors.accent)
}

/** Android's USSD API can't answer a menu, so the option goes out as a new chained code, after the user confirms. */
@Composable
private fun MenuAnswer(onSend: (String) -> Unit) {
    var option by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.balance_menu_hint), fontSize = 11.sp, color = DialerTheme.colors.muted)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = option,
            onValueChange = { v -> option = v.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.balance_option)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        TextButton(enabled = option.isNotEmpty(), onClick = { onSend(option) }) {
            Text(stringResource(R.string.balance_send))
        }
    }
}

@Composable
private fun codeLabel(code: UssdCode): String {
    if (code.label.isNotBlank()) return code.label
    val res = when (code.id.substringAfterLast('.')) {
        "balance" -> R.string.balance_code_main
        "number" -> R.string.balance_code_number
        "data" -> R.string.balance_code_data
        "plan" -> R.string.balance_code_plan
        "offers" -> R.string.balance_code_offers
        else -> null
    }
    return res?.let { stringResource(it) } ?: code.code
}

@Composable
private fun CodeEditor(initial: UssdCode?, onDismiss: () -> Unit, onSave: (UssdCode) -> Unit, onDelete: (() -> Unit)?) {
    val initialLabel = initial?.let { codeLabel(it) }.orEmpty()
    var label by remember { mutableStateOf(initialLabel) }
    var code by remember { mutableStateOf(initial?.code.orEmpty()) }
    var infoOnly by remember { mutableStateOf(initial?.kind == UssdKind.INFO) }
    val valid = UssdCodes.isValid(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(if (initial == null) R.string.balance_add else R.string.balance_edit), fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.balance_name)) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() || it == '*' || it == '#' }.take(42) },
                    label = { Text(stringResource(R.string.balance_code)) },
                    singleLine = true,
                    isError = code.isNotEmpty() && !valid,
                    supportingText = if (code.isNotEmpty() && !valid) {
                        { Text(stringResource(R.string.balance_code_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { infoOnly = !infoOnly }) {
                    Checkbox(checked = infoOnly, onCheckedChange = { infoOnly = it })
                    Text(stringResource(R.string.balance_info_only), fontSize = 13.sp)
                }
                Text(stringResource(R.string.balance_info_only_help), fontSize = 11.sp, color = DialerTheme.colors.muted)
                if (onDelete != null) {
                    Box(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = DialerTheme.colors.accent) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val normalized = UssdCodes.normalize(code)
                val kind = if (infoOnly) UssdKind.INFO else if (initial?.kind == UssdKind.MENU) UssdKind.MENU else UssdKind.ACTION
                val saved = if (initial == null) {
                    UssdCodes.custom("custom.${System.currentTimeMillis()}", label, normalized, infoOnly)
                } else {
                    initial.copy(
                        // Keep a built-in's translated name unless the user renamed it.
                        label = if (label == initialLabel && initial.label.isBlank()) "" else label.trim(),
                        code = normalized,
                        kind = kind,
                        // An edited code is the user's own now.
                        verified = if (normalized == initial.code) initial.verified else true
                    )
                }
                onSave(saved)
            }) { Text(stringResource(R.string.balance_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
