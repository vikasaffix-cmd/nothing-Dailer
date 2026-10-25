package com.example.allinonedailer.ui.incall

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.telecom.Call
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.service.ActiveCallData
import com.example.allinonedailer.service.CallService
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.components.GrayscaleFilter
import com.example.allinonedailer.ui.components.initialFor
import com.example.allinonedailer.ui.components.rememberContactPhoto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ButtonGrey = Color(0xFF262626)
private val NothingRed = Color(0xFFD71921)

@Composable
fun InCallScreen(callData: ActiveCallData) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        if (callData.state == Call.STATE_RINGING) {
            IncomingCallContent(callData)
        } else {
            ActiveCallContent(callData)
        }
    }
}

/* ============================== Incoming ============================== */

@Composable
private fun IncomingCallContent(callData: ActiveCallData) {
    val context = LocalContext.current
    var showReplies by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                AccountLabel(callData.accountLabel, Modifier.align(Alignment.Center))
                CallMenu(callData, Modifier.align(Alignment.CenterEnd))
            }

            Spacer(modifier = Modifier.weight(1f))

            DotMatrixText(
                text = callData.callerName ?: callData.phoneNumber,
                pitch = 7.dp,
                centered = true,
                maxLines = 2,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            if (callerSubtitle(callData) != null) {
                Text(
                    text = callerSubtitle(callData)!!,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            RedPill(
                text = when {
                    callData.isSpam -> "spam · ${(callData.spamCategory ?: "suspected").lowercase()}"
                    callData.isVideo -> "is video calling…"
                    else -> "is calling…"
                }
            )

            Spacer(modifier = Modifier.weight(0.35f))

            FourWayAnswerControl(
                onMessage = {
                    if (CallService.canReplyWithText()) {
                        showReplies = true
                    } else {
                        Toast.makeText(context, "Text replies aren't available for this call", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showReplies) {
            QuickReplies(
                onSend = { CallService.rejectWithMessage(it) },
                onCancel = { showReplies = false }
            )
        }
    }
}

private val QuickReplyMessages = listOf(
    "Can't talk now. Call you later.",
    "I'll call you right back.",
    "I'm in a meeting. I'll call you soon.",
    "Can you text me instead?"
)

/** Canned SMS replies shown after swiping up on an incoming call. */
@Composable
private fun QuickReplies(onSend: (String) -> Unit, onCancel: () -> Unit) {
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
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1C1C1C))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Reply with a message",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            )
            QuickReplyMessages.forEach { message ->
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(ButtonGrey)
                        .clickable { onSend(message) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
            Text(
                text = "Cancel",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
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
    val photo = rememberContactPhoto(callData.photoUri)
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
                color = Color.White.copy(alpha = 0.10f),
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
private fun FourWayAnswerControl(onMessage: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reachPx = with(density) { 120.dp.toPx() }
    val thresholdPx = reachPx * 0.8f
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    // 0..1 progress toward each target, used to light up its icon.
    val right = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
    val left = (-offsetX.value / thresholdPx).coerceIn(0f, 1f)
    val up = (-offsetY.value / thresholdPx).coerceIn(0f, 1f)
    val down = (offsetY.value / thresholdPx).coerceIn(0f, 1f)

    fun springBack() = scope.launch {
        launch { offsetX.animateTo(0f) }
        launch { offsetY.animateTo(0f) }
    }

    Box(modifier = Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        TargetIcon(Icons.Default.ChatBubbleOutline, "Reply with message", Color.White, up, Modifier.align(Alignment.TopCenter))
        TargetIcon(Icons.Default.Voicemail, "Send to voicemail", Color.White, down, Modifier.align(Alignment.BottomCenter))
        TargetIcon(Icons.Default.CallEnd, "Decline", NothingRed, left, Modifier.align(Alignment.CenterStart))
        TargetIcon(Icons.Default.Call, "Answer", Color.White, right, Modifier.align(Alignment.CenterEnd))

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(68.dp)
                .clip(CircleShape)
                .background(Color(0xFF2A2A2A))
                .pointerInput(Unit) {
                    detectTapGestures {
                        // Wiggle toward "answer" to hint that the knob drags.
                        scope.launch {
                            offsetX.animateTo(30f)
                            offsetX.animateTo(0f)
                        }
                    }
                }
                .pointerInput(Unit) {
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
                            if (action != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
private fun ActiveCallContent(callData: ActiveCallData) {
    val context = LocalContext.current
    var callSeconds by remember { mutableIntStateOf(0) }
    var showKeypad by remember { mutableStateOf(false) }
    var dtmfDigits by remember { mutableStateOf("") }
    val isOnHold = callData.state == Call.STATE_HOLDING

    LaunchedEffect(callData.state) {
        if (callData.state == Call.STATE_ACTIVE) {
            while (true) {
                delay(1000)
                callSeconds++
            }
        }
    }

    val statusText = when (callData.state) {
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_ACTIVE -> formatTime(callSeconds)
        Call.STATE_DISCONNECTED -> "Call ended"
        else -> "Connecting…"
    }

    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val startVideoAfterPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) CallService.requestVideo(true)
        else Toast.makeText(context, "Camera permission is needed for video", Toast.LENGTH_SHORT).show()
    }
    val acceptVideoAfterPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) CallService.acceptVideoUpgrade() else CallService.declineVideoUpgrade()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (callData.isVideo) {
        VideoSurfaces()
        // Scrim so the controls stay readable over video.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f),
                        0.3f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f)
                    )
                )
        )
    }

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

        Spacer(modifier = Modifier.height(28.dp))

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
            color = Color.White
        )

        if (callerSubtitle(callData) != null) {
            Text(
                text = callerSubtitle(callData)!!,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(text = statusText, fontSize = 12.sp, color = Color.White)
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
                Text(text = "REC", fontSize = 11.sp, color = NothingRed, letterSpacing = 1.sp)
            }
        }

        if (callData.videoUpgradeRequested) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(ButtonGrey)
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Switch to video?",
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Decline",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { CallService.declineVideoUpgrade() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    text = "Accept",
                    fontSize = 13.sp,
                    color = Color.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable {
                            if (hasCamera) CallService.acceptVideoUpgrade()
                            else acceptVideoAfterPermission.launch(Manifest.permission.CAMERA)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        if (showKeypad && dtmfDigits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            DotMatrixText(text = dtmfDigits.takeLast(14), pitch = 3.dp, color = Color.White)
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.widthIn(max = 380.dp)) {
                if (showKeypad) {
                    InCallKeypad(
                        onDigit = { dtmfDigits += it },
                        onHide = { showKeypad = false }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ControlButton(Icons.Default.MicOff, "Mute", active = callData.isMuted) {
                            CallService.setMute(!callData.isMuted)
                        }
                        ControlButton(Icons.Default.Dialpad, "Keypad") { showKeypad = true }
                        ControlButton(Icons.AutoMirrored.Filled.VolumeUp, "Speaker", active = callData.isSpeakerOn) {
                            CallService.setSpeaker(!callData.isSpeakerOn)
                        }
                    }
                    Spacer(modifier = Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ControlButton(Icons.Default.Add, "Add call") {
                            Toast.makeText(context, "Add call isn't available yet", Toast.LENGTH_SHORT).show()
                        }
                        ControlButton(Icons.Default.Pause, "Hold", active = isOnHold) {
                            CallService.holdCurrentCall(!isOnHold)
                        }
                        ControlButton(Icons.Default.Videocam, "Video call", active = callData.isVideo) {
                            when {
                                callData.isVideo -> CallService.requestVideo(false)
                                !callData.videoCapable -> Toast.makeText(
                                    context,
                                    "Video isn't offered on this call by your carrier or the other phone",
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

        Spacer(modifier = Modifier.height(22.dp))

        // Page indicator
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(NothingRed)
                    .clickable { CallService.disconnectCurrentCall() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
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
    Box(modifier = Modifier.fillMaxSize()) {
        VideoTexture(onSurface = CallService::setDisplaySurface, modifier = Modifier.fillMaxSize())
        VideoTexture(
            onSurface = CallService::setPreviewSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(top = 56.dp, end = 16.dp)
                .size(width = 110.dp, height = 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
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
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else ButtonGrey)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
    }
}

/** DTMF keypad shown during a call: tones play while a key is held. */
@Composable
private fun InCallKeypad(onDigit: (Char) -> Unit, onHide: () -> Unit) {
    val haptics = LocalHapticFeedback.current
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
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(ButtonGrey)
                            .pointerInput(digit) {
                                detectTapGestures(onPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    CallService.playDtmf(digit)
                                    onDigit(digit)
                                    tryAwaitRelease()
                                    CallService.stopDtmf()
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        DotMatrixText(text = digit.toString(), pitch = 2.8.dp, color = Color.White)
                    }
                }
            }
        }
        Text(
            text = "Hide keypad",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onHide() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/* ============================== Shared ============================== */

@Composable
private fun AccountLabel(label: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.SimCard,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label ?: "Mobile", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

/** ⋮ menu with call actions that don't have their own button (decline, block). */
@Composable
private fun CallMenu(callData: ActiveCallData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val ringing = callData.state == Call.STATE_RINGING
    val canBlock = !callData.inContacts && callData.phoneNumber.any { it.isDigit() }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, "More", tint = Color.White.copy(alpha = 0.8f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (ringing) {
                DropdownMenuItem(
                    text = { Text("Decline") },
                    onClick = {
                        open = false
                        CallService.rejectCurrentCall()
                    }
                )
            }
            if (canBlock) {
                DropdownMenuItem(
                    text = { Text(if (ringing) "Block & decline" else "Block & end", color = NothingRed) },
                    onClick = {
                        open = false
                        CallerIdRepository(context).blockNumber(callData.phoneNumber)
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
        Text(text = text, color = Color.White, fontSize = 12.sp)
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
private fun callerSubtitle(callData: ActiveCallData): String? {
    if (callData.inContacts) return null
    return listOfNotNull(
        if (callData.nameFromNetwork) "Name from network" else null,
        callData.numberLabel
    ).joinToString(" · ").ifBlank { null }
}
