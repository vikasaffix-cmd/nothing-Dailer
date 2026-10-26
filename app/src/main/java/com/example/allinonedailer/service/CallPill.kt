package com.example.allinonedailer.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.Call
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.allinonedailer.R
import com.example.allinonedailer.data.AppLanguageSetting
import com.example.allinonedailer.data.Haptics
import com.example.allinonedailer.data.QuietHoursGate
import com.example.allinonedailer.ui.MainActivity
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.incall.CallerCardView
import com.example.allinonedailer.ui.reminders.DeclineAndRemindRow
import com.example.allinonedailer.ui.reminders.canRemind
import com.example.allinonedailer.ui.theme.AllinoneDailerTheme
import com.example.allinonedailer.ui.theme.DialerTheme

private val NothingRed = Color(0xFFD71921)

/**
 * The incoming-call pill: a black pill at the top of the screen, under the
 * camera cutout, shown instead of the heads-up notification while the phone
 * is unlocked and another app is in front. It is an overlay window, so it
 * needs "Display over other apps"; without it (or when the phone is locked,
 * or the app is open) CallService posts its usual notification.
 *
 * Driven from CallService: [show] when a call starts ringing, [dismiss] once
 * nothing rings. The pill reads the ringing call from [CallService.allCalls],
 * so the name, photo and spam flag fill in as the lookup finishes.
 */
object CallPill {
    private const val TAG = "CallPill"
    private const val PREFS = "call_pill"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    fun hasPermission(context: Context) = Settings.canDrawOverlays(context)

    /** The system "Display over other apps" screen for this app. The user grants it there. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Posts the incoming-call notification when the pill goes away while the
     * call still rings. quiet = true: into the shade without a heads-up.
     * Set by CallService.
     */
    var onFallback: ((quiet: Boolean) -> Unit)? = null

    /** MainActivity is on screen; it shows the ringing call itself. Set from onStart / onStop. */
    var appVisible = false
        set(value) {
            field = value
            if (value) window?.let { quietCalls += it.callId; close(fallbackQuiet = true) }
        }

    private var window: PillWindow? = null

    /** Calls whose pill was swiped away or replaced by the app: no pill and no heads-up again. */
    private val quietCalls = HashSet<String>()

    fun wantsQuietNotification(callId: String) = callId in quietCalls

    /**
     * Shows the pill for the ringing call [callId]. True if it is up, in which
     * case CallService skips the heads-up notification.
     */
    fun show(context: Context, callId: String, number: String?): Boolean {
        window?.let { if (it.callId == callId && !it.closing) return true }
        // Quiet hours silenced this caller: no pill popping up either.
        if (QuietHoursGate.isSilenced(number)) return false
        if (callId in quietCalls || !canShowNow(context)) return false
        window?.removeNow()
        val pill = PillWindow(AppLanguageSetting.wrap(context.applicationContext), callId)
        return try {
            pill.add()
            window = pill
            Haptics.play(context, Haptics.Effect.TICK)
            Log.d(TAG, "Pill shown for $callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't add the call pill", e)
            pill.removeNow()
            false
        }
    }

    /** Nothing rings any more (answered, declined, missed): animate the pill away. */
    fun dismiss() {
        quietCalls.clear()
        close(fallbackQuiet = null)
    }

    /** The user swiped the pill up: it goes, the call keeps ringing, and it waits in the shade. */
    internal fun hideByUser(callId: String) {
        quietCalls += callId
        close(fallbackQuiet = true)
    }

    /** The screen went off: the locked phone needs the full-screen notification instead. */
    internal fun onScreenOff() = close(fallbackQuiet = false)

    private fun close(fallbackQuiet: Boolean?) {
        val pill = window ?: return
        window = null
        pill.animateOut()
        if (fallbackQuiet != null) onFallback?.invoke(fallbackQuiet)
    }

    private fun canShowNow(context: Context): Boolean {
        if (!isEnabled(context) || !hasPermission(context) || appVisible) return false
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }
}

/** Lifecycle, saved state and view models for a ComposeView that lives outside an activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

/** One overlay window holding the pill for one ringing call. */
private class PillWindow(private val context: Context, val callId: String) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private var view: ComposeView? = null
    private var shown by mutableStateOf(true)
    var closing = false
        private set

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = CallPill.onScreenOff()
    }
    private var receiverRegistered = false

    fun add() {
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                AllinoneDailerTheme {
                    CallPillContent(
                        callId = callId,
                        shown = shown,
                        onHide = { CallPill.hideByUser(callId) },
                        onGone = ::removeNow
                    )
                }
            }
        }
        owner.start()
        view = compose
        windowManager.addView(compose, layoutParams())
        ContextCompat.registerReceiver(
            context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    /** Shrinks back to a dot, then removes the window. */
    fun animateOut() {
        closing = true
        shown = false
    }

    fun removeNow() {
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                context.unregisterReceiver(screenOff)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
        val v = view ?: return
        view = null
        try {
            windowManager.removeViewImmediate(v)
        } catch (e: Exception) {
            Log.w("CallPill", "Couldn't remove the call pill", e)
        }
        owner.destroy()
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = topOffsetPx()
        title = "CallPill"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Just under the camera punch-hole; under the status bar where there is none. */
    private fun topOffsetPx(): Int {
        val density = context.resources.displayMetrics.density
        val gap = (6 * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            val cutout = insets.displayCutout?.boundingRects
                ?.filter { it.top <= 0 + (8 * density) && !it.isEmpty }
                ?.maxByOrNull { it.bottom }
            if (cutout != null) return cutout.bottom + gap
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            if (bars > 0) return bars + gap
        }
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val bar = if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * density).toInt()
        return bar + gap
    }
}

private val QuickReplies = listOf(
    R.string.quick_reply_later,
    R.string.quick_reply_right_back,
    R.string.quick_reply_meeting,
    R.string.quick_reply_text_me
)

@Composable
private fun CallPillContent(callId: String, shown: Boolean, onHide: () -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val calls by CallService.allCalls.collectAsState()
    // Keep the last data so the pill still has a face while it shrinks away.
    val holder = remember { arrayOfNulls<ActiveCallData>(1) }
    val ringing = calls.firstOrNull { it.callId == callId && it.state == Call.STATE_RINGING }
    if (ringing != null) holder[0] = ringing
    val data = holder[0]
    val waiting = calls.any { it.callId != callId && it.state != Call.STATE_RINGING }

    var expanded by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val visible = shown && (ringing != null || data == null)
    LaunchedEffect(visible) {
        if (visible) {
            progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
        } else {
            progress.animateTo(0f, tween(220))
            onGone()
        }
    }
    // Ringing stopped on its own (missed, answered elsewhere): let CallService's dismiss tidy up too.
    LaunchedEffect(ringing == null, data != null) {
        if (ringing == null && data != null) CallPill.dismiss()
    }

    val p = progress.value
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val fullWidth = minOf(screenWidth - 24.dp, if (expanded) 380.dp else 360.dp)
    val dot = 18.dp
    val width = lerp(dot, fullWidth, p).coerceAtLeast(dot)
    val height = lerp(dot, 68.dp, p).coerceAtLeast(dot)
    val contentAlpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(if (expanded && p >= 1f) 32.dp else height / 2)
    var dragScale by remember { mutableStateOf(1f) }

    val openCallScreen = {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    Box(
        modifier = Modifier
            .padding(4.dp)
            .graphicsLayer { scaleX = dragScale; scaleY = dragScale }
            .width(width)
            .then(if (expanded && p >= 1f) Modifier.wrapContentHeight() else Modifier.height(height))
            .animateContentSize(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
            .clip(shape)
            .background(DialerTheme.colors.callPillBackground)
            .border(1.dp, DialerTheme.colors.callPillBorder, shape)
            .pointerInput(callId) {
                var total = 0f
                val threshold = 36.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        dragScale = 1f
                        when {
                            total < -threshold -> onHide()
                            total > threshold -> expanded = true
                        }
                    },
                    onDragCancel = { dragScale = 1f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        total += dy
                        dragScale = 1f + (total / 2000f).coerceIn(-0.06f, 0.04f)
                    }
                )
            }
            .pointerInput(callId) {
                detectTapGestures(
                    onTap = { openCallScreen() },
                    onLongPress = { expanded = true }
                )
            }
    ) {
        if (data != null && contentAlpha > 0f) {
            Column(modifier = Modifier.graphicsLayer { alpha = contentAlpha }) {
                PillRow(data, waiting)
                if (expanded && p >= 1f) {
                    ExpandedActions(
                        data = data,
                        onReply = { CallService.rejectWithMessage(it) },
                        onOpen = openCallScreen
                    )
                }
            }
        }
    }
}

@Composable
private fun PillRow(data: ActiveCallData, waiting: Boolean) {
    val context = LocalContext.current
    val title = data.callerName ?: data.phoneNumber
    val subtitle = when {
        data.callerName == null && data.numberLabel != null -> data.numberLabel
        data.callerName != null && !data.inContacts -> data.phoneNumber
        else -> stringResource(if (waiting) R.string.call_waiting else R.string.incoming_call)
    }
    Row(
        modifier = Modifier
            .height(68.dp)
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CallerAvatar(
            name = data.callerName,
            number = data.phoneNumber,
            size = 44.dp,
            spam = data.isSpam,
            photoUri = data.photoUri
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            DotMatrixText(text = title, pitch = 2.4.dp, color = DialerTheme.colors.ink)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (data.isSpam) {
                    Text(
                        text = stringResource(R.string.spam).uppercase(),
                        color = DialerTheme.colors.onAccent,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(NothingRed)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = (if (data.isSpam) data.spamCategory ?: subtitle else subtitle).orEmpty(),
                    color = DialerTheme.colors.ink.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(
            background = NothingRed,
            onClick = {
                Haptics.play(context, Haptics.Effect.BUTTON)
                CallService.rejectCurrentCall()
            }
        ) {
            Icon(Icons.Filled.CallEnd, stringResource(R.string.decline), tint = DialerTheme.colors.onDecline)
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(
            background = DialerTheme.colors.answer,
            onClick = {
                Haptics.play(context, Haptics.Effect.BUTTON)
                CallService.answerCurrentCall()
                // A video call needs the camera screen; a voice call stays in the app you're in.
                if (data.isVideo) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            }
        ) {
            Icon(Icons.Filled.Call, stringResource(R.string.answer), tint = DialerTheme.colors.onAnswer)
        }
    }
}

@Composable
private fun RoundButton(background: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { icon() }
}

@Composable
private fun ExpandedActions(data: ActiveCallData, onReply: (String) -> Unit, onOpen: () -> Unit) {
    // "Remind me" opens the reminder presets (15 min, 1 h, tonight…); picking one declines the call.
    var picking by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CallerCardView(data.phoneNumber, fillWidth = true)
        QuickReplies.forEach { res ->
            val message = stringResource(res)
            PillChip(text = message, onClick = { onReply(message) }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canRemind(data.phoneNumber)) PillChip(
                text = stringResource(R.string.call_pill_remind_me),
                onClick = { picking = !picking },
                modifier = Modifier.weight(1f),
                accent = true
            )
            PillChip(
                text = stringResource(R.string.call_pill_open),
                onClick = onOpen,
                modifier = Modifier.weight(1f)
            )
        }
        if (picking) DeclineAndRemindRow(data.phoneNumber, data.callerName) { CallService.rejectCurrentCall() }
    }
}

@Composable
private fun PillChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text = text,
        color = DialerTheme.colors.ink,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (accent) NothingRed.copy(alpha = 0.18f) else DialerTheme.colors.panel)
            .border(1.dp, if (accent) NothingRed else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    )
}
