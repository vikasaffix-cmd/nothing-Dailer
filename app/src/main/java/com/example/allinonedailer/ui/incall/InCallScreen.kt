package com.example.allinonedailer.ui.incall

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Build
import android.telecom.Call
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.data.Haptics
import com.example.allinonedailer.service.ActiveCallData
import com.example.allinonedailer.service.AudioRoute
import com.example.allinonedailer.service.AudioRouteType
import com.example.allinonedailer.service.CallService
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.dialer.DialpadScreen
import com.example.allinonedailer.ui.reminders.DeclineAndRemindRow
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.components.GrayscaleFilter
import com.example.allinonedailer.ui.components.initialFor
import com.example.allinonedailer.ui.components.rememberContactPhoto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.allinonedailer.R
import com.example.allinonedailer.ui.theme.AlwaysDarkTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.example.allinonedailer.ui.theme.DialerTheme

// Red is the same in both themes; everything else comes from DialerTheme.colors.
private val NothingRed = Color(0xFFD71921)

@Composable
fun InCallScreen(callData: ActiveCallData) {
    val allCalls by CallService.allCalls.collectAsState()
    val others = allCalls.filter { it.callId != callData.callId }
    // A second call ringing while this one is up, and the call in the background (usually held).
    val waiting = others.firstOrNull { it.state == Call.STATE_RINGING }
    val background = others.firstOrNull { it.state != Call.STATE_RINGING }

    // Incoming (full-bleed photo or giant initial fading to black) and video calls
    // stay dark in the light theme too, like iOS, so photos and video read well.
    if (callData.state == Call.STATE_RINGING || callData.isVideo) {
        AlwaysDarkTheme { InCallSurface(callData, waiting, background) }
    } else {
        InCallSurface(callData, waiting, background)
    }
}

@Composable
private fun InCallSurface(callData: ActiveCallData, waiting: ActiveCallData?, background: ActiveCallData?) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (callData.state) {
            Call.STATE_RINGING -> IncomingCallContent(callData, background)
            Call.STATE_SELECT_PHONE_ACCOUNT -> SimChooserContent(callData)
            else -> ActiveCallContent(callData, background)
        }
        val addingCall by CallService.addingCall.collectAsState()
        if (addingCall && callData.state != Call.STATE_RINGING && callData.state != Call.STATE_SELECT_PHONE_ACCOUNT) {
            AddCallPad(callData)
        }
        if (waiting != null && callData.state != Call.STATE_RINGING) {
            // Only offer to hold when the network allows it and nothing is on hold yet.
            WaitingCallSheet(waiting, canHoldCurrent = callData.canHold && background == null)
        }
    }
}

/* ============================ Call waiting ============================ */

/**
 * A second call ringing during a call: Hold & answer, End & answer, or
 * Decline. Sits over the current call, which stays visible behind it.
 */
@Composable
private fun WaitingCallSheet(waiting: ActiveCallData, canHoldCurrent: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .padding(16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(28.dp))
                .background(DialerTheme.colors.panel)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RedPill(
                text = if (waiting.isSpam) {
                    stringResource(R.string.call_waiting).lowercase() + " · " + stringResource(R.string.spam).lowercase()
                } else {
                    stringResource(R.string.call_waiting).lowercase()
                }
            )
            Spacer(modifier = Modifier.height(18.dp))
            CallerAvatar(
                name = waiting.callerName,
                number = waiting.phoneNumber,
                photoUri = waiting.photoUri,
                spam = waiting.isSpam,
                size = 52.dp
            )
            Spacer(modifier = Modifier.height(14.dp))
            DotMatrixText(
                text = waiting.callerName ?: waiting.phoneNumber,
                pitch = 4.dp,
                centered = true,
                maxLines = 2,
                color = DialerTheme.colors.ink
            )
            val subtitle = callerSubtitle(waiting, stringResource(R.string.name_from_network))
                ?: waiting.phoneNumber.takeIf { waiting.callerName != null }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = DialerTheme.colors.ink.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(22.dp))
            if (canHoldCurrent) {
                WaitingAction(
                    icon = Icons.Default.Pause,
                    label = stringResource(R.string.hold_and_answer),
                    container = DialerTheme.colors.answer,
                    content = DialerTheme.colors.onAnswer,
                    onClick = CallService::holdAndAnswer
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            WaitingAction(
                icon = Icons.Default.CallEnd,
                label = stringResource(R.string.end_and_answer),
                container = NothingRed,
                content = DialerTheme.colors.onDecline,
                onClick = CallService::endAndAnswer
            )
            Spacer(modifier = Modifier.height(10.dp))
            WaitingAction(
                icon = Icons.Default.Close,
                label = stringResource(R.string.decline),
                container = DialerTheme.colors.keyBackground,
                content = DialerTheme.colors.ink,
                onClick = CallService::rejectCurrentCall
            )
        }
    }
}

@Composable
private fun WaitingAction(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable {
                Haptics.play(context, Haptics.Effect.BUTTON)
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 14.sp, letterSpacing = 0.5.sp, color = content)
    }
}

/**
 * The other call while two are up: who it is and how long it's been, with
 * Swap, and Merge when the network can join them. Swap is hidden while the
 * current call is still ringing.
 */
@Composable
private fun BackgroundCallBar(
    call: ActiveCallData,
    foreground: ActiveCallData,
    modifier: Modifier = Modifier
) {
    val canSwap = foreground.state != Call.STATE_RINGING && call.state == Call.STATE_HOLDING
    val canMerge = canSwap && (foreground.canMerge || call.canMerge)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DialerTheme.colors.panel)
            .border(1.dp, DialerTheme.colors.ink.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Pause,
            contentDescription = null,
            tint = DialerTheme.colors.ink.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            DotMatrixText(
                text = call.callerName ?: call.phoneNumber,
                pitch = 2.2.dp,
                color = DialerTheme.colors.ink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (call.state) {
                    Call.STATE_HOLDING -> stringResource(R.string.on_hold) +
                        (callDuration(call, rememberNow())?.let { " · ${formatTime(it)}" } ?: "")
                    Call.STATE_DIALING, Call.STATE_CONNECTING -> stringResource(R.string.calling)
                    Call.STATE_ACTIVE -> stringResource(R.string.connected)
                    else -> stringResource(R.string.connecting)
                },
                fontSize = 11.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.6f)
            )
        }
        if (canMerge) {
            BarButton(stringResource(R.string.merge), filled = false, onClick = CallService::mergeCalls)
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (canSwap) {
            BarButton(stringResource(R.string.swap), filled = true, onClick = CallService::swapCalls)
        }
    }
}

@Composable
private fun BarButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Text(
        text = label,
        fontSize = 13.sp,
        color = if (filled) DialerTheme.colors.onInk else DialerTheme.colors.ink,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) DialerTheme.colors.ink else DialerTheme.colors.keyBackground)
            .clickable {
                Haptics.play(context, Haptics.Effect.BUTTON)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/* ============================= Conference ============================= */

/**
 * Who's in the conference. End (hang up on one person) and Split (take them
 * into a private call) show only where the network allows them.
 */
@Composable
private fun ConferenceParticipants(participants: List<ActiveCallData>) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DialerTheme.colors.panel)
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.in_call_count, participants.size, participants.size),
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = DialerTheme.colors.ink.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp)
        )
        participants.take(5).forEach { person ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallerAvatar(
                    name = person.callerName,
                    number = person.phoneNumber,
                    photoUri = person.photoUri,
                    size = 30.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                DotMatrixText(
                    text = person.callerName ?: person.phoneNumber,
                    pitch = 2.dp,
                    color = DialerTheme.colors.ink,
                    modifier = Modifier.weight(1f)
                )
                if (person.canSplit) {
                    BarButton(stringResource(R.string.split), filled = false) { CallService.splitParticipant(person.callId) }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (person.canEndFromConference) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(NothingRed)
                            .clickable {
                                Haptics.play(context, Haptics.Effect.BUTTON)
                                CallService.endParticipant(person.callId)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            stringResource(R.string.end_call_with, person.callerName ?: person.phoneNumber),
                            tint = DialerTheme.colors.onDecline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        if (participants.size > 5) {
            Text(
                text = stringResource(R.string.more_count, participants.size - 5),
                fontSize = 11.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

/* ============================== Add call ============================== */

/**
 * The app's keypad over the call, for placing a second call. The current
 * call waits on hold; "Back to call" (or back) resumes it. Placing the call
 * goes through the normal outgoing path, SIM choice included, and the
 * two-call screen takes over once Telecom adds it.
 */
@Composable
private fun AddCallPad(current: ActiveCallData) {
    BackHandler { CallService.cancelAddCall() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        DialpadScreen(
            onOpenRecents = {},
            onOpenContacts = {},
            onBackToCall = CallService::cancelAddCall,
            header = { HeldCallBar(current) }
        )
    }
}

/** "On hold" bar above the add-call keypad; tapping it goes back to the call. */
@Composable
private fun HeldCallBar(current: ActiveCallData) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(DialerTheme.colors.panel)
            .clickable {
                Haptics.play(context, Haptics.Effect.BUTTON)
                CallService.cancelAddCall()
            }
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Pause,
            contentDescription = null,
            tint = DialerTheme.colors.ink.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            DotMatrixText(
                text = current.callerName ?: current.phoneNumber,
                pitch = 2.2.dp,
                color = DialerTheme.colors.ink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(if (current.state == Call.STATE_HOLDING) R.string.on_hold else R.string.holding),
                fontSize = 11.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.6f)
            )
        }
        BarButton(stringResource(R.string.back_to_call), filled = true) { CallService.cancelAddCall() }
    }
}

/** Wall-clock time, ticking once a second, for live call timers. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Seconds since [call] connected, or null if it hasn't. */
private fun callDuration(call: ActiveCallData, now: Long): Int? =
    call.connectTimeMillis.takeIf { it > 0 }?.let { ((now - it) / 1000).coerceAtLeast(0).toInt() }

/* ============================== SIM choice ============================== */

/**
 * "Call with" sheet for outgoing calls when no default SIM is set for calls.
 * Picking a SIM hands it to Telecom; Cancel (or back) drops the call.
 */
@Composable
private fun SimChooserContent(callData: ActiveCallData) {
    val context = LocalContext.current
    var rememberChoice by rememberSaveable { mutableStateOf(false) }
    BackHandler { CallService.disconnectCurrentCall() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        DotMatrixText(
            text = callData.callerName ?: callData.phoneNumber,
            pitch = 6.dp,
            centered = true,
            maxLines = 2,
            color = DialerTheme.colors.ink
        )
        Spacer(modifier = Modifier.height(16.dp))
        RedPill(text = stringResource(R.string.call_with))

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            callData.simChoices.forEach { sim ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(DialerTheme.colors.keyBackground)
                        .clickable {
                            Haptics.play(context, Haptics.Effect.BUTTON)
                            CallService.selectSim(sim.handle, rememberChoice)
                        }
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SimCard, null,
                        tint = NothingRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = sim.label,
                        fontSize = 16.sp,
                        color = DialerTheme.colors.ink,
                        modifier = Modifier.weight(1f)
                    )
                    sim.detail?.let {
                        Text(text = it, fontSize = 12.sp, color = DialerTheme.colors.ink.copy(alpha = 0.55f))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { rememberChoice = !rememberChoice }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .then(
                            if (rememberChoice) Modifier.background(NothingRed)
                            else Modifier.border(1.5.dp, DialerTheme.colors.ink.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (rememberChoice) {
                        Icon(Icons.Default.Check, null, tint = DialerTheme.colors.onAccent, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = stringResource(R.string.remember_choice), fontSize = 14.sp, color = DialerTheme.colors.ink.copy(alpha = 0.8f))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.cancel),
            fontSize = 14.sp,
            color = DialerTheme.colors.ink,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, DialerTheme.colors.ink.copy(alpha = 0.35f), RoundedCornerShape(50))
                .clickable {
                    Haptics.play(context, Haptics.Effect.BUTTON)
                    CallService.disconnectCurrentCall()
                }
                .padding(horizontal = 28.dp, vertical = 12.dp)
        )
    }
}

/* ============================== Incoming ============================== */

@Composable
private fun IncomingCallContent(callData: ActiveCallData, background: ActiveCallData?) {
    val context = LocalContext.current
    val landscape = isLandscape()
    var showReplies by rememberSaveable { mutableStateOf(false) }
    val onMessage: () -> Unit = {
        if (CallService.canReplyWithText()) {
            showReplies = true
        } else {
            Toast.makeText(context, R.string.text_replies_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    val topBar: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            AccountLabel(callData.accountLabel, Modifier.align(Alignment.Center))
            CallMenu(callData, Modifier.align(Alignment.CenterEnd))
        }
    }

    // Name, caller detail and the "is calling" pill.
    val identity: @Composable (Dp) -> Unit = { namePitch ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DotMatrixText(
                text = callData.callerName ?: callData.phoneNumber,
                pitch = namePitch,
                centered = true,
                maxLines = 2,
                color = DialerTheme.colors.ink,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            if (callerSubtitle(callData, stringResource(R.string.name_from_network)) != null) {
                Text(
                    text = callerSubtitle(callData, stringResource(R.string.name_from_network))!!,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    color = DialerTheme.colors.ink.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            RedPill(
                text = when {
                    callData.isSpam -> stringResource(R.string.spam).lowercase() + " · " +
                        (callData.spamCategory ?: stringResource(R.string.spam_suspected)).lowercase()
                    callData.isVideo -> stringResource(R.string.is_video_calling)
                    else -> stringResource(R.string.is_calling)
                }
            )
            // Last note, call-back reminder, last call, missed calls, birthday: whatever exists.
            CallerCardView(callData.phoneNumber, Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (landscape) {
            // Caller on the left over their photo, the answer knob on the right.
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    HeroBackdrop(callData = callData, modifier = Modifier.fillMaxSize())
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .systemBarsPadding()
                            .padding(bottom = 24.dp)
                    ) {
                        identity(6.dp)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .systemBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    topBar()
                    if (background != null) {
                        BackgroundCallBar(background, callData, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        FourWayAnswerControl(
                            onMessage = onMessage,
                            size = minOf(maxWidth, maxHeight, 300.dp)
                        )
                    }
                }
            }
        } else {
            // Full-bleed photo (or a giant dotted initial) fading into black.
            HeroBackdrop(
                callData = callData,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                topBar()

                if (background != null) {
                    BackgroundCallBar(background, callData, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                Spacer(modifier = Modifier.weight(1f))

                identity(7.dp)

                Spacer(modifier = Modifier.weight(0.35f))

                FourWayAnswerControl(onMessage = onMessage)

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showReplies) {
            QuickReplies(
                onSend = { CallService.rejectWithMessage(it) },
                onCancel = { showReplies = false },
                remind = {
                    DeclineAndRemindRow(callData.phoneNumber, callData.callerName) { CallService.rejectCurrentCall() }
                }
            )
        }
    }
}

private val QuickReplyMessages = listOf(
    R.string.quick_reply_later,
    R.string.quick_reply_right_back,
    R.string.quick_reply_meeting,
    R.string.quick_reply_text_me
)

/** Canned SMS replies shown after swiping up on an incoming call. */
@Composable
private fun QuickReplies(
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    remind: @Composable () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable { onCancel() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(28.dp))
                .background(DialerTheme.colors.panel)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.reply_with_message),
                fontSize = 12.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            )
            QuickReplyMessages.forEach { messageRes ->
                val message = stringResource(messageRes)
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = DialerTheme.colors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(DialerTheme.colors.keyBackground)
                        .clickable { onSend(message) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
            remind()
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 14.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .clickable { onCancel() }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun HeroBackdrop(callData: ActiveCallData, modifier: Modifier) {
    // Full-screen backdrop: big enough that contact photos keep their own resolution.
    val photo = rememberContactPhoto(callData.photoUri, maxSize = 480.dp)
    Box(modifier = modifier) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = GrayscaleFilter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            DotMatrixText(
                text = initialFor(callData.callerName, callData.phoneNumber),
                pitch = 26.dp,
                color = DialerTheme.colors.ink.copy(alpha = 0.10f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.4f to Color.Transparent,
                        1f to Color.Black
                    )
                )
        )
    }
}

/**
 * Center knob with four targets: drag right to answer, left to decline, up to
 * reply with a text message, down to send to voicemail. The knob moves along
 * one axis at a time and springs back if released short of a target.
 */
@Composable
private fun FourWayAnswerControl(onMessage: () -> Unit, size: Dp = 300.dp) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    // 120dp of travel at the full 300dp size; less when landscape makes it smaller.
    val reachPx = with(density) { (size * 0.4f).toPx() }
    val thresholdPx = reachPx * 0.8f
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    // 0..1 progress toward each target, used to light up its icon.
    val right = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
    val left = (-offsetX.value / thresholdPx).coerceIn(0f, 1f)
    val up = (-offsetY.value / thresholdPx).coerceIn(0f, 1f)
    val down = (offsetY.value / thresholdPx).coerceIn(0f, 1f)

    // The target the knob is past the threshold for (0 = none); ticks when it changes.
    var armed by remember { mutableIntStateOf(0) }
    fun targetAt(x: Float, y: Float) = when {
        x >= thresholdPx -> 1
        x <= -thresholdPx -> 2
        y <= -thresholdPx -> 3
        y >= thresholdPx -> 4
        else -> 0
    }

    fun springBack() = scope.launch {
        launch { offsetX.animateTo(0f) }
        launch { offsetY.animateTo(0f) }
    }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        TargetIcon(Icons.Default.ChatBubbleOutline, stringResource(R.string.reply_with_message), DialerTheme.colors.ink, up, Modifier.align(Alignment.TopCenter))
        TargetIcon(Icons.Default.Voicemail, stringResource(R.string.send_to_voicemail), DialerTheme.colors.ink, down, Modifier.align(Alignment.BottomCenter))
        TargetIcon(Icons.Default.CallEnd, stringResource(R.string.decline), NothingRed, left, Modifier.align(Alignment.CenterStart))
        TargetIcon(Icons.Default.Call, stringResource(R.string.answer), DialerTheme.colors.ink, right, Modifier.align(Alignment.CenterEnd))

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(68.dp)
                .clip(CircleShape)
                .background(DialerTheme.colors.chip)
                .pointerInput(Unit) {
                    detectTapGestures {
                        // Wiggle toward "answer" to hint that the knob drags.
                        scope.launch {
                            offsetX.animateTo(30f)
                            offsetX.animateTo(0f)
                        }
                    }
                }
                .pointerInput(reachPx) {
                    detectDragGestures(
                        onDragEnd = {
                            val x = offsetX.value
                            val y = offsetY.value
                            val action: (() -> Unit)? = when {
                                x >= thresholdPx -> CallService::answerCurrentCall
                                x <= -thresholdPx -> CallService::rejectCurrentCall
                                y <= -thresholdPx -> onMessage
                                y >= thresholdPx -> CallService::sendToVoicemail
                                else -> null
                            }
                            armed = 0
                            if (action != null) {
                                Haptics.play(context, Haptics.Effect.BUTTON)
                                action()
                            }
                            springBack()
                        },
                        onDragCancel = { springBack() },
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                // Lock to whichever axis the finger is moving along most.
                                val nx = offsetX.value + drag.x
                                val ny = offsetY.value + drag.y
                                if (kotlin.math.abs(nx) >= kotlin.math.abs(ny)) {
                                    offsetX.snapTo(nx.coerceIn(-reachPx, reachPx))
                                    offsetY.snapTo(0f)
                                } else {
                                    offsetY.snapTo(ny.coerceIn(-reachPx, reachPx))
                                    offsetX.snapTo(0f)
                                }
                                val target = targetAt(offsetX.value, offsetY.value)
                                if (target != armed) {
                                    if (target != 0) Haptics.play(context, Haptics.Effect.TICK)
                                    armed = target
                                }
                            }
                        }
                    )
                }
        )
    }
}

@Composable
private fun TargetIcon(
    icon: ImageVector,
    description: String,
    color: Color,
    progress: Float,
    modifier: Modifier
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = color.copy(alpha = 0.55f + 0.45f * progress),
        modifier = modifier
            .padding(8.dp)
            .size((30 + 8 * progress).dp)
    )
}

/* =============================== Active =============================== */

@Composable
private fun ActiveCallContent(callData: ActiveCallData, background: ActiveCallData?) {
    val context = LocalContext.current
    // Timer and keypad belong to the call on screen, so Swap shows the other call's own.
    val callSeconds = callDuration(callData, rememberNow()) ?: 0
    var showKeypad by rememberSaveable(callData.callId) { mutableStateOf(false) }
    var dtmfDigits by rememberSaveable(callData.callId) { mutableStateOf("") }
    var showRoutes by rememberSaveable { mutableStateOf(false) }
    val isOnHold = callData.state == Call.STATE_HOLDING
    // Telecom's canAddCall, plus the call itself must be able to go on hold for the new one.
    val canAddCall = CallService.canAddCall.collectAsState().value && callData.canHold &&
        (callData.state == Call.STATE_ACTIVE || isOnHold)

    val statusText = when (callData.state) {
        Call.STATE_DIALING, Call.STATE_CONNECTING -> stringResource(R.string.calling)
        Call.STATE_HOLDING -> stringResource(R.string.on_hold)
        Call.STATE_ACTIVE -> formatTime(callSeconds)
        Call.STATE_DISCONNECTED -> stringResource(R.string.call_ended)
        else -> stringResource(R.string.connecting)
    }

    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val startVideoAfterPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) CallService.requestVideo(true)
        else Toast.makeText(context, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
    }
    val acceptVideoAfterPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) CallService.acceptVideoUpgrade() else CallService.declineVideoUpgrade()
    }

    val landscape = isLandscape()

    // Avatar, name, status, plus the conference list, video prompt and typed tones.
    val identity: @Composable () -> Unit = {
        CallerAvatar(
            name = callData.callerName,
            number = callData.phoneNumber,
            photoUri = callData.photoUri,
            spam = callData.isSpam,
            size = 56.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        DotMatrixText(
            text = callData.callerName ?: callData.phoneNumber,
            pitch = 4.5.dp,
            maxLines = 2,
            color = DialerTheme.colors.ink
        )

        if (callerSubtitle(callData, stringResource(R.string.name_from_network)) != null) {
            Text(
                text = callerSubtitle(callData, stringResource(R.string.name_from_network))!!,
                fontSize = 12.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, DialerTheme.colors.ink.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(text = statusText, fontSize = 12.sp, color = DialerTheme.colors.ink)
            }
            if (callData.isRecording) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NothingRed)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = stringResource(R.string.rec_badge), fontSize = 11.sp, color = NothingRed, letterSpacing = 1.sp)
            }
        }

        if (callData.isConference && callData.participants.isNotEmpty() && !showKeypad) {
            Spacer(modifier = Modifier.height(18.dp))
            ConferenceParticipants(callData.participants)
        }

        if (callData.videoUpgradeRequested) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(DialerTheme.colors.keyBackground)
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.switch_to_video),
                    fontSize = 13.sp,
                    color = DialerTheme.colors.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.decline),
                    fontSize = 13.sp,
                    color = DialerTheme.colors.ink.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            Haptics.play(context, Haptics.Effect.BUTTON)
                            CallService.declineVideoUpgrade()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    text = stringResource(R.string.accept),
                    fontSize = 13.sp,
                    color = DialerTheme.colors.onInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(DialerTheme.colors.ink)
                        .clickable {
                            Haptics.play(context, Haptics.Effect.BUTTON)
                            if (hasCamera) CallService.acceptVideoUpgrade()
                            else acceptVideoAfterPermission.launch(Manifest.permission.CAMERA)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        if (showKeypad && dtmfDigits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            DotMatrixText(text = dtmfDigits.takeLast(14), pitch = 3.dp, color = DialerTheme.colors.ink)
        }
    }

    // The 3×2 control grid, or the DTMF keypad in its place.
    val controls: @Composable (Dp, Dp) -> Unit = { buttonSize, keySize ->
        Column(modifier = Modifier.widthIn(max = 380.dp)) {
            if (showKeypad) {
                InCallKeypad(
                    keySize = keySize,
                    onDigit = { dtmfDigits += it },
                    onHide = { showKeypad = false }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ControlButton(Icons.Default.MicOff, stringResource(R.string.mute), active = callData.isMuted, size = buttonSize) {
                        CallService.setMute(!callData.isMuted)
                    }
                    ControlButton(Icons.Default.Dialpad, stringResource(R.string.keypad), size = buttonSize) { showKeypad = true }
                    AudioRouteButton(callData, buttonSize) { showRoutes = true }
                }
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ControlButton(Icons.Default.Add, stringResource(R.string.add_call), dimmed = !canAddCall, size = buttonSize) {
                        if (canAddCall) {
                            CallService.startAddCall()
                        } else {
                            Toast.makeText(
                                context,
                                if (background != null) R.string.two_calls_up
                                else R.string.network_cant_add_call,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    ControlButton(Icons.Default.Pause, stringResource(R.string.hold), active = isOnHold, size = buttonSize) {
                        CallService.holdCurrentCall(!isOnHold)
                    }
                    ControlButton(Icons.Default.Videocam, stringResource(R.string.video_call), active = callData.isVideo, size = buttonSize) {
                        when {
                            callData.isVideo -> CallService.requestVideo(false)
                            !callData.videoCapable -> Toast.makeText(
                                context,
                                R.string.video_not_offered,
                                Toast.LENGTH_LONG
                            ).show()
                            hasCamera -> CallService.requestVideo(true)
                            else -> startVideoAfterPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }
        }
    }

    val endButton: @Composable (Dp) -> Unit = { size ->
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(NothingRed)
                .clickable {
                    Haptics.play(context, Haptics.Effect.BUTTON)
                    CallService.disconnectCurrentCall()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CallEnd, stringResource(R.string.end_call), tint = DialerTheme.colors.onDecline, modifier = Modifier.size(30.dp))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (callData.isVideo) {
        // Rebind the surfaces when Swap brings a different call forward.
        key(callData.callId) { VideoSurfaces() }
        // Scrim so the controls stay readable over video.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (landscape) {
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.65f),
                            0.35f to Color.Transparent,
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f)
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.65f),
                            0.3f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f)
                        )
                    }
                )
        )
    }

    if (landscape) {
        // Who's on the call on the left, controls and End on the right.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp, bottom = 16.dp, end = 16.dp)
            ) {
                AccountLabel(callData.accountLabel)
                if (background != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    BackgroundCallBar(background, callData)
                }
                Spacer(modifier = Modifier.height(18.dp))
                identity()
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val endSize = 64.dp
                // Fit the grid (two rows with labels) or the four keypad rows above End.
                val room = maxHeight - endSize - 40.dp
                val buttonSize = ((room - 22.dp) / 2 - 26.dp).coerceIn(44.dp, 64.dp)
                val keySize = ((room - 36.dp) / 4 - 12.dp).coerceIn(36.dp, 60.dp)
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    controls(buttonSize, keySize)
                    Spacer(modifier = Modifier.height(16.dp))
                    endButton(endSize)
                }
                CallMenu(callData, Modifier.align(Alignment.TopEnd))
            }
        }
    } else {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            AccountLabel(callData.accountLabel, Modifier.align(Alignment.CenterStart))
            CallMenu(callData, Modifier.align(Alignment.CenterEnd))
        }

        if (background != null) {
            Spacer(modifier = Modifier.height(10.dp))
            BackgroundCallBar(background, callData)
        }

        Spacer(modifier = Modifier.height(if (background != null) 18.dp else 28.dp))

        identity()

        Spacer(modifier = Modifier.weight(1f))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            controls(64.dp, 60.dp)
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Page indicator
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(DialerTheme.colors.ink)
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            endButton(76.dp)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
    }
    if (showRoutes && hasRouteChoice(callData.audioRoutes)) {
        AudioRouteSheet(callData.audioRoutes, onDismiss = { showRoutes = false })
    }
    }
}

/* ============================== Audio route ============================== */

/** More than earpiece and speaker to pick from (Bluetooth or a wired headset). */
private fun hasRouteChoice(routes: List<AudioRoute>) =
    routes.any { it.type == AudioRouteType.BLUETOOTH || it.type == AudioRouteType.WIRED_HEADSET }

private fun routeIcon(type: AudioRouteType?): ImageVector = when (type) {
    AudioRouteType.EARPIECE -> Icons.Default.PhoneInTalk
    AudioRouteType.WIRED_HEADSET -> Icons.Default.Headset
    AudioRouteType.BLUETOOTH -> Icons.Default.BluetoothAudio
    AudioRouteType.SPEAKER, null -> Icons.AutoMirrored.Filled.VolumeUp
}

/**
 * Speaker toggle while only earpiece and speaker exist. With Bluetooth or a
 * wired headset it shows the current route and opens the route picker.
 */
@Composable
private fun AudioRouteButton(callData: ActiveCallData, size: Dp, onOpenPicker: () -> Unit) {
    val context = LocalContext.current
    val routes = callData.audioRoutes
    // Android 12+ hides Bluetooth device names until BLUETOOTH_CONNECT is granted.
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (!hasRouteChoice(routes)) {
        ControlButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.audio_speaker), active = callData.isSpeakerOn, size = size) {
            CallService.setSpeaker(!callData.isSpeakerOn)
        }
        return
    }
    val current = routes.firstOrNull { it.isActive }
    ControlButton(
        icon = routeIcon(current?.type),
        label = current?.let { routeLabel(it) } ?: stringResource(R.string.audio),
        active = current != null && current.type != AudioRouteType.EARPIECE,
        size = size
    ) {
        val unnamed = routes.any { it.type == AudioRouteType.BLUETOOTH && it.name == "Bluetooth" }
        if (unnamed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        onOpenPicker()
    }
}

/** "Audio" sheet: one pill per route, the current one lit. */
@Composable
private fun AudioRouteSheet(routes: List<AudioRoute>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .padding(16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(28.dp))
                .background(DialerTheme.colors.panel)
                // Taps inside the sheet don't fall through to the scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.audio).uppercase(),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = DialerTheme.colors.ink.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 6.dp, bottom = 14.dp)
            )
            routes.forEachIndexed { i, route ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                val content = if (route.isActive) DialerTheme.colors.onInk else DialerTheme.colors.ink
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (route.isActive) DialerTheme.colors.ink else DialerTheme.colors.keyBackground)
                        .clickable {
                            Haptics.play(context, Haptics.Effect.BUTTON)
                            if (!route.isActive) CallService.selectAudioRoute(route.id)
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(routeIcon(route.type), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = routeLabel(route),
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (route.isActive) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NothingRed)
                        )
                    }
                }
            }
        }
    }
}

/** Remote video full-screen, with your own camera in a corner window. */
@Composable
private fun VideoSurfaces() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        CallService.setCamera(context, true)
        onDispose {
            CallService.setCamera(context, false)
            CallService.setPreviewSurface(null)
            CallService.setDisplaySurface(null)
        }
    }
    val landscape = isLandscape()
    Box(modifier = Modifier.fillMaxSize()) {
        VideoTexture(onSurface = CallService::setDisplaySurface, modifier = Modifier.fillMaxSize())
        VideoTexture(
            onSurface = CallService::setPreviewSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(top = 56.dp, end = 16.dp)
                .size(width = if (landscape) 150.dp else 110.dp, height = if (landscape) 110.dp else 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DialerTheme.colors.ink.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        )
    }
}

@Composable
private fun VideoTexture(onSurface: (Surface?) -> Unit, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(st).also(onSurface)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onSurface(null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        }
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    /** Greyed out: the action isn't possible right now (tapping can still explain why). */
    dimmed: Boolean = false,
    size: Dp = 64.dp,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (active) DialerTheme.colors.ink else DialerTheme.colors.keyBackground)
                .clickable {
                    Haptics.play(context, Haptics.Effect.BUTTON)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    active -> DialerTheme.colors.onInk
                    dimmed -> DialerTheme.colors.ink.copy(alpha = 0.3f)
                    else -> DialerTheme.colors.ink
                },
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = DialerTheme.colors.ink.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 84.dp)
        )
    }
}

/** DTMF keypad shown during a call: tones play while a key is held. */
@Composable
private fun InCallKeypad(keySize: Dp, onDigit: (Char) -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val rows = listOf("123", "456", "789", "*0#")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { digit ->
                    Box(
                        modifier = Modifier
                            .size(keySize)
                            .clip(CircleShape)
                            .background(DialerTheme.colors.keyBackground)
                            .pointerInput(digit) {
                                detectTapGestures(onPress = {
                                    Haptics.play(context, Haptics.Effect.KEY)
                                    CallService.playDtmf(digit)
                                    onDigit(digit)
                                    tryAwaitRelease()
                                    CallService.stopDtmf()
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        DotMatrixText(text = digit.toString(), pitch = 2.8.dp, color = DialerTheme.colors.ink)
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.hide_keypad),
            fontSize = 12.sp,
            color = DialerTheme.colors.ink.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable {
                    Haptics.play(context, Haptics.Effect.BUTTON)
                    onHide()
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/* ============================== Shared ============================== */

@Composable
private fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
private fun AccountLabel(label: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.SimCard,
            contentDescription = null,
            tint = DialerTheme.colors.ink.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label ?: stringResource(R.string.number_type_mobile), fontSize = 12.sp, color = DialerTheme.colors.ink.copy(alpha = 0.7f))
    }
}

/** ⋮ menu with call actions that don't have their own button (decline, block). */
@Composable
private fun CallMenu(callData: ActiveCallData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val ringing = callData.state == Call.STATE_RINGING
    val canBlock = !callData.inContacts && callData.phoneNumber.any { it.isDigit() }
    // "Continue on WhatsApp": opens the chat (synced row, else wa.me); the call stays up.
    val whatsApp = com.example.allinonedailer.ui.components.rememberWhatsAppState(callData.phoneNumber)
    val canWhatsApp = whatsApp?.installed == true && callData.phoneNumber.count { it.isDigit() } >= 8 &&
        !com.example.allinonedailer.data.CallPlacer.isEmergency(context, callData.phoneNumber)

    Box(modifier = modifier) {
        IconButton(onClick = {
            Haptics.play(context, Haptics.Effect.BUTTON)
            open = true
        }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = DialerTheme.colors.ink.copy(alpha = 0.8f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canWhatsApp) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.whatsapp_continue)) },
                    onClick = {
                        open = false
                        Haptics.play(context, Haptics.Effect.BUTTON)
                        val opened = com.example.allinonedailer.data.WhatsApp.openChat(
                            context, callData.phoneNumber, whatsApp?.actions.orEmpty()
                        )
                        if (!opened) android.widget.Toast.makeText(context, R.string.whatsapp_open_failed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
            if (ringing) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.decline)) },
                    onClick = {
                        open = false
                        Haptics.play(context, Haptics.Effect.BUTTON)
                        CallService.rejectCurrentCall()
                    }
                )
            }
            if (canBlock) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (ringing) R.string.block_and_decline else R.string.block_and_end), color = NothingRed) },
                    onClick = {
                        open = false
                        Haptics.play(context, Haptics.Effect.BUTTON)
                        CallerIdRepository(context).blockNumber(callData.phoneNumber)
                        if (ringing) CallService.rejectCurrentCall() else CallService.disconnectCurrentCall()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.report_spam), color = NothingRed) },
                    onClick = {
                        open = false
                        Haptics.play(context, Haptics.Effect.BUTTON)
                        // Also blocks the number.
                        CallerIdRepository(context).reportSpam(callData.phoneNumber)
                        if (ringing) CallService.rejectCurrentCall() else CallService.disconnectCurrentCall()
                    }
                )
            }
        }
    }
}

@Composable
private fun RedPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(NothingRed)
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = DialerTheme.colors.onAccent, fontSize = 12.sp)
    }
}

private fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}

/**
 * The line under a non-contact's name: "Name from network" for CNAP names,
 * plus the offline carrier / region, e.g. "Name from network · Jio · India".
 */
private fun callerSubtitle(callData: ActiveCallData, nameFromNetwork: String): String? {
    if (callData.inContacts) return null
    return listOfNotNull(
        if (callData.nameFromNetwork) nameFromNetwork else null,
        callData.numberLabel
    ).joinToString(" · ").ifBlank { null }
}

/** An audio route's name in the app's language; Bluetooth devices keep their own name. */
@Composable
private fun routeLabel(route: AudioRoute): String = when (route.type) {
    AudioRouteType.EARPIECE -> stringResource(R.string.audio_phone)
    AudioRouteType.SPEAKER -> stringResource(R.string.audio_speaker)
    AudioRouteType.WIRED_HEADSET -> stringResource(R.string.audio_wired_headset)
    AudioRouteType.BLUETOOTH -> if (route.name == "Bluetooth") stringResource(R.string.audio_bluetooth) else route.name
}
