package com.example.allinonedailer.ui.dialer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.example.allinonedailer.ui.reminders.ScheduleCallHost
import com.example.allinonedailer.ui.reminders.contactNameFor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.allinonedailer.data.CallPlacer
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.SimPick
import com.example.allinonedailer.data.SmartSim
import com.example.allinonedailer.ui.components.smartSimHint
import com.example.allinonedailer.data.Haptics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallLogRepository
import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.KeypadIndex
import com.example.allinonedailer.data.NumberInfo
import com.example.allinonedailer.service.SimOption
import com.example.allinonedailer.service.callCapableSims
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource

private val KeyGrey = Color(0xFF262626)
private val NothingRed = Color(0xFFD71921)
private val Muted = Color.White.copy(alpha = 0.55f)

private data class Suggestion(
    val name: String?,
    val number: String,
    val photoUri: String?,
    val nameHighlight: IntRange? = null,
    val numberHighlight: IntRange? = null,
    /** Stable LazyColumn key: the contact (one row each) or the recent number. */
    val key: String = "r:$number"
)

/** The T9 index for the shared contacts list, kept while that list is current. */
private object KeypadIndexCache {
    @Volatile
    private var built: Pair<List<ContactItem>, KeypadIndex>? = null
    private val empty = KeypadIndex(emptyList())

    /** The index if it is already built for [contacts], else an empty one. */
    fun peek(contacts: List<ContactItem>): KeypadIndex =
        built?.takeIf { it.first === contacts }?.second ?: empty

    /** Builds it if needed. Off the main thread. */
    fun get(contacts: List<ContactItem>): KeypadIndex {
        built?.let { (list, index) -> if (list === contacts) return index }
        return KeypadIndex(contacts).also { built = contacts to it }
    }
}

/** The latest distinct numbers from the call log, for the empty keypad. */
private fun recentSuggestions(log: List<CallLogEntry>): List<Suggestion> =
    log.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
        .take(8)
        .map { Suggestion(it.callerName, it.number, it.photoUri) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadScreen(
    onOpenRecents: () -> Unit,
    onOpenContacts: () -> Unit,
    /** Set when the keypad is open over a call to add another: shows "Back to call" instead of Recents / Contacts. */
    onBackToCall: (() -> Unit)? = null,
    /** Landscape only: the keypad draws its own Settings button (portrait has it in the top bar). */
    onOpenSettings: (() -> Unit)? = null,
    /** Long-press menu on the number: Balance & plan (operator USSD codes). */
    onOpenBalance: (() -> Unit)? = null,
    /** Shown above the suggestions (the held call while adding a call). */
    header: (@Composable () -> Unit)? = null,
    /** Typed into the keypad once (e.g. a call-forwarding code from Voicemail); never dialled. */
    prefillNumber: String? = null,
    onPrefillConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var numberText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(prefillNumber) {
        if (prefillNumber != null) {
            numberText = prefillNumber
            onPrefillConsumed()
        }
    }
    // Start from what is already in memory (shared contacts cache, last call
    // log), so returning to the keypad shows suggestions on the first frame.
    var contacts by remember { mutableStateOf(ContactsRepository.cached(onePerContact = false).orEmpty()) }
    var keypadIndex by remember { mutableStateOf(KeypadIndexCache.peek(contacts)) }
    var recents by remember { mutableStateOf(recentSuggestions(CallLogRepository.lastResult)) }

    LaunchedEffect(Unit) {
        // The call log and contacts load side by side rather than one after the other.
        launch {
            recents = recentSuggestions(withContext(Dispatchers.IO) { CallLogRepository(context).getCallLogs() })
        }
        contacts = withContext(Dispatchers.IO) {
            ContactsRepository(context).loadContacts(onePerContact = false)
        }
        // Number digits and T9 name keys, computed once rather than per keystroke.
        keypadIndex = withContext(Dispatchers.Default) { KeypadIndexCache.get(contacts) }
    }

    val digits = numberText.filter(Char::isDigit)
    // Number prefix, then T9 name, then number-contains (see KeypadIndex.search),
    // searched off the main thread so typing never waits on 9,000+ contacts.
    val suggestions by produceState(initialValue = if (digits.isEmpty()) recents else emptyList(), digits, keypadIndex, recents) {
        value = if (digits.isEmpty()) {
            recents
        } else {
            withContext(Dispatchers.Default) {
                keypadIndex.search(digits).map {
                    Suggestion(
                        it.contact.name, it.contact.phoneNumber, it.contact.photoUri,
                        it.nameHighlight, it.numberHighlight, key = "c:" + it.contact.id
                    )
                }
            }
        }
    }
    val savedKeys = remember(contacts) { contacts.mapTo(HashSet()) { it.phoneNumber.filter(Char::isDigit).takeLast(10) } }
    val isSavedNumber = digits.length >= 6 && digits.takeLast(10) in savedKeys

    // Offline carrier / region for the number being typed (libphonenumber).
    var numberLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(digits) {
        numberLabel = if (digits.length < 7) null else withContext(Dispatchers.IO) {
            NumberInfo.describe(numberText, NumberInfo.defaultRegion(context), context = context)?.label
        }
    }

    val tones = rememberDialTones()

    // Dual SIM: the SIM to call with. Starts on the default calling SIM;
    // none selected ("") means Telecom asks ("Ask every time"). Saved by
    // account id so the choice survives rotation.
    val sims = remember { callCapableSims(context) }
    val defaultSimId = remember { defaultSim(context, sims)?.handle?.id.orEmpty() }
    var selectedSimId by rememberSaveable { mutableStateOf(defaultSimId) }
    val selectedSim = sims.firstOrNull { it.handle.id == selectedSimId }

    // Smart SIM: preselects pinned > last used > same network for the typed
    // number until the user taps the toggle; cleared with the number.
    var simTouched by rememberSaveable { mutableStateOf(false) }
    var smartPick by remember { mutableStateOf<SimPick?>(null) }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) simTouched = false
        val pick = if (digits.length < 7 || sims.size < 2) null else withContext(Dispatchers.IO) {
            SmartSim.suggest(context, numberText, sims)
        }
        smartPick = pick
        if (!simTouched) selectedSimId = pick?.simId ?: defaultSimId
    }

    val dial: (String, Boolean) -> Unit = { number, video ->
        // The toggle is for the typed number (or whatever the user picked on it);
        // other numbers (favourites, suggestions) get Smart SIM's own pick.
        if (simTouched || PhoneNumbers.sameNumber(number, numberText)) {
            placeCall(context, number, video, selectedSim?.handle, autoSim = false)
        } else {
            placeCall(context, number, video)
        }
    }

    // Starred contacts for the empty keypad, and speed dial on keys 1–9.
    val favourites = remember(contacts) { contacts.filter { it.starred }.distinctBy { it.id } }
    val speedDial = rememberSpeedDialState()

    // Matching contacts / recent calls
    val suggestionList: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(modifier = modifier) {
            if (numberText.isEmpty() && favourites.isNotEmpty()) {
                item(key = "favourites") { FavouritesRow(favourites = favourites, onCall = dial) }
            }
            items(suggestions, key = { it.key }) { s ->
                SuggestionRow(
                    suggestion = s,
                    onPick = { numberText = s.number },
                    onCall = { dial(s.number, false) }
                )
            }
        }
    }

    // Entered number. The dot-matrix display isn't a text field, so long-press
    // offers Paste / Copy like a stock dialer.
    val clipboard = LocalClipboardManager.current
    var showNumberMenu by rememberSaveable { mutableStateOf(false) }
    val numberDisplay: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 24.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showNumberMenu = true }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (numberText.isNotEmpty()) {
                DotMatrixText(
                    text = formatForDisplay(numberText),
                    pitch = 5.dp,
                    centered = true,
                    color = Color.White
                )
            }
            DropdownMenu(expanded = showNumberMenu, onDismissRequest = { showNumberMenu = false }) {
                val pasted = clipboard.getText()?.text?.let(::dialableFrom).orEmpty()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.paste)) },
                    enabled = pasted.isNotEmpty(),
                    onClick = {
                        numberText += pasted
                        showNumberMenu = false
                    }
                )
                if (numberText.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = {
                            clipboard.setText(AnnotatedString(numberText))
                            showNumberMenu = false
                        }
                    )
                }
                if (onOpenBalance != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.balance_title)) },
                        onClick = {
                            showNumberMenu = false
                            onOpenBalance()
                        }
                    )
                }
            }
        }
    }

    val addContactArea: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (numberText.isNotEmpty() && !isSavedNumber) {
                numberLabel?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = Muted,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                AddContactPill { addContact(context, numberText) }
            }
        }
    }

    val simToggle: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            SimToggle(sims = sims, selected = selectedSim, onSelect = {
                simTouched = true
                selectedSimId = it?.handle?.id.orEmpty()
            })
            // e.g. "Same network: Jio", only while the toggle still shows the pick.
            val hint = smartSimHint(smartPick?.takeIf { it.simId == selectedSimId }, sims)
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = Muted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    // Keypad
    val keyGrid: @Composable (Dp, Dp) -> Unit = { keySize, gap ->
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            val press: (Char) -> Unit = { c ->
                tones.play(c)
                numberText += c
            }
            // Long-press on the empty keypad speed-dials; while typing it just types the digit.
            val speed: (Int) -> () -> Unit = { key ->
                {
                    if (numberText.isEmpty()) {
                        speedDial.longPress(context, key, selectedSim?.handle) { dial(it, false) }
                    } else {
                        press('0' + key)
                    }
                }
            }
            KeyRow {
                DialKey("1", keySize, subIcon = Icons.Default.Voicemail, onLongPress = speed(1)) { press('1') }
                DialKey("2", keySize, sub = "ABC", marked = speedDial.isAssigned(2), onLongPress = speed(2)) { press('2') }
                DialKey("3", keySize, sub = "DEF", marked = speedDial.isAssigned(3), onLongPress = speed(3)) { press('3') }
            }
            KeyRow {
                DialKey("4", keySize, sub = "GHI", marked = speedDial.isAssigned(4), onLongPress = speed(4)) { press('4') }
                DialKey("5", keySize, sub = "JKL", marked = speedDial.isAssigned(5), onLongPress = speed(5)) { press('5') }
                DialKey("6", keySize, sub = "MNO", marked = speedDial.isAssigned(6), onLongPress = speed(6)) { press('6') }
            }
            KeyRow {
                DialKey("7", keySize, sub = "PQRS", marked = speedDial.isAssigned(7), onLongPress = speed(7)) { press('7') }
                DialKey("8", keySize, sub = "TUV", marked = speedDial.isAssigned(8), onLongPress = speed(8)) { press('8') }
                DialKey("9", keySize, sub = "WXYZ", marked = speedDial.isAssigned(9), onLongPress = speed(9)) { press('9') }
            }
            KeyRow {
                DialKey("#", keySize, sub = "*", outlined = true, onLongPress = { press('*') }) { press('#') }
                DialKey("0", keySize, sub = "+", onLongPress = { numberText += '+' }) { press('0') }
                BackspaceKey(
                    size = keySize,
                    onTap = { numberText = numberText.dropLast(1) },
                    onLongPress = { numberText = "" }
                )
            }
        }
    }

    // Long-press on the call button: Video call / Schedule call for the typed number.
    var showCallMenu by remember { mutableStateOf(false) }
    var scheduleNumber by remember { mutableStateOf<String?>(null) }
    scheduleNumber?.let { n -> ScheduleCallHost(n, remember(n) { contactNameFor(n) }) { scheduleNumber = null } }

    // Bottom bar: recents · call · contacts
    val bottomBar: @Composable (Dp) -> Unit = { callSize ->
        Row(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBackToCall != null) {
                IconButton(onClick = onBackToCall) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(R.string.back_to_call),
                        tint = Color.White
                    )
                }
            } else {
                IconButton(onClick = onOpenRecents) {
                    Icon(Icons.Default.History, stringResource(R.string.recents_title), tint = Color.White)
                }
            }
            Box(
                modifier = Modifier
                    .size(callSize)
                    .clip(CircleShape)
                    .background(Color.White)
                    .combinedClickable(
                        onClick = {
                            when {
                                // placeCall() buzzes for the call itself.
                                numberText.isNotEmpty() -> dial(numberText, false)
                                // Empty keypad: recall the last number, like stock dialers.
                                recents.isNotEmpty() -> {
                                    Haptics.play(context, Haptics.Effect.KEY)
                                    numberText = recents.first().number
                                }
                            }
                        },
                        // Long-press: Video call or Schedule call.
                        onLongClick = {
                            if (numberText.isNotEmpty()) {
                                Haptics.play(context, Haptics.Effect.KEY)
                                showCallMenu = true
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Call, stringResource(R.string.call), tint = Color.Black, modifier = Modifier.size(28.dp))
                DropdownMenu(expanded = showCallMenu, onDismissRequest = { showCallMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.video_call)) },
                        onClick = {
                            showCallMenu = false
                            if (numberText.isNotEmpty()) dial(numberText, true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scheduled_call_schedule)) },
                        onClick = {
                            showCallMenu = false
                            if (numberText.isNotEmpty()) scheduleNumber = numberText
                        }
                    )
                }
            }
            if (onBackToCall != null) {
                // Keeps the call button centred.
                Spacer(modifier = Modifier.size(48.dp))
            } else {
                IconButton(onClick = onOpenContacts) {
                    Icon(Icons.Default.ContactPage, stringResource(R.string.contacts_title), tint = Color.White)
                }
            }
        }
    }

    if (landscape) {
        // Two panes: suggestions and the number on the left, keys and call on the right.
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                header?.invoke()
                suggestionList(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                numberDisplay()
                addContactArea()
                if (sims.size > 1) {
                    simToggle()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Four key rows and the call row share the height, so nothing scrolls.
                val gap = 8.dp
                val keySize = ((maxHeight - 16.dp - gap * 4) / 5).coerceIn(40.dp, 78.dp)
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = keySize * 3 + 72.dp)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    keyGrid(keySize, gap)
                    bottomBar(keySize)
                }
                if (onOpenSettings != null) {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = Muted)
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            header?.invoke()
            suggestionList(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Sheet handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f))
                )
            }

            numberDisplay()
            addContactArea()

            Spacer(modifier = Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                keyGrid(78.dp, 14.dp)
            }

            if (sims.size > 1) {
                Spacer(modifier = Modifier.height(14.dp))
                simToggle()
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                bottomBar(80.dp)
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    SpeedDialDialogs(speedDial)
}

@Composable
private fun KeyRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    label: String,
    size: Dp,
    sub: String? = null,
    subIcon: ImageVector? = null,
    outlined: Boolean = false,
    marked: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (outlined) Modifier.border(1.dp, Color(0xFF2E2E2E), CircleShape)
                else Modifier.background(KeyGrey)
            )
            .combinedClickable(
                onClick = {
                    Haptics.play(context, Haptics.Effect.KEY)
                    onTap()
                },
                onLongClick = onLongPress?.let { action ->
                    {
                        Haptics.play(context, Haptics.Effect.KEY_LONG)
                        action()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Smaller landscape keys get a finer dot pitch so the label and letters still fit.
            val compact = size < 66.dp
            DotMatrixText(text = label, pitch = if (compact) 2.8.dp else 3.4.dp, color = Color.White)
            Spacer(modifier = Modifier.height(if (compact) 2.dp else 5.dp))
            when {
                subIcon != null -> Icon(subIcon, null, tint = Muted, modifier = Modifier.size(12.dp))
                sub != null -> Text(text = sub, fontSize = 10.sp, letterSpacing = 1.sp, color = Muted)
                else -> Spacer(modifier = Modifier.height(12.dp))
            }
        }
        // Speed-dial number assigned to this key.
        if (marked) SpeedDialDot(Modifier.align(Alignment.TopCenter).padding(top = size * 0.11f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceKey(size: Dp, onTap: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(NothingRed)
            .combinedClickable(
                onClick = {
                    Haptics.play(context, Haptics.Effect.KEY)
                    onTap()
                },
                onLongClick = {
                    Haptics.play(context, Haptics.Effect.KEY_LONG)
                    onLongPress()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.delete), tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onPick: () -> Unit, onCall: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallerAvatar(
                name = suggestion.name,
                number = suggestion.number,
                photoUri = suggestion.photoUri,
                size = 30.dp,
                pill = true
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (suggestion.name != null) {
                        highlighted(suggestion.name.uppercase(), suggestion.nameHighlight)
                    } else {
                        highlighted(suggestion.number, suggestion.numberHighlight)
                    },
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = Color.White,
                    maxLines = 1
                )
                if (suggestion.name != null) {
                    Text(
                        text = highlighted(suggestion.number, suggestion.numberHighlight),
                        fontSize = 11.sp,
                        color = Muted,
                        maxLines = 1
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onCall() }
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = stringResource(R.string.call).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
            }
        }
        HorizontalDivider(color = Color(0xFF161616))
    }
}

/** [text] with the keypad match in red; ranges past the end (e.g. after uppercasing) are clipped. */
private fun highlighted(text: String, range: IntRange?): AnnotatedString = buildAnnotatedString {
    append(text)
    if (range != null && range.first < text.length) {
        addStyle(SpanStyle(color = NothingRed), range.first, minOf(range.last + 1, text.length))
    }
}

@Composable
private fun AddContactPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .drawBehind {
                drawRoundRect(
                    color = NothingRed,
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    )
                )
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.AddCircleOutline, null, tint = NothingRed, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = stringResource(R.string.add_contact), fontSize = 12.sp, color = NothingRed)
    }
}

/** Local key-press tones, honouring the system "dial pad tones" setting. */
private class DialTones(private val generator: ToneGenerator?, private val enabled: Boolean) {
    fun play(c: Char) {
        if (!enabled || generator == null) return
        val tone = when (c) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        generator.startTone(tone, 120)
    }

    fun release() = generator?.release()
}

@Composable
private fun rememberDialTones(): DialTones {
    val context = LocalContext.current
    val tones = remember {
        val enabled = Settings.System.getInt(
            context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1
        ) == 1
        val generator = try {
            ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (e: RuntimeException) {
            null
        }
        DialTones(generator, enabled)
    }
    DisposableEffect(Unit) { onDispose { tones.release() } }
    return tones
}

private fun formatForDisplay(raw: String): String {
    val digitCount = raw.count(Char::isDigit)
    if (digitCount < 7 || raw.any { it == '*' || it == '#' }) return raw
    return PhoneNumberUtils.formatNumber(raw, Locale.getDefault().country) ?: raw
}

/** Two-segment pill above the call button: tap a SIM to call with it, tap it again to be asked. */
@Composable
private fun SimToggle(sims: List<SimOption>, selected: SimOption?, onSelect: (SimOption?) -> Unit) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1A1A1A))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sims.forEach { sim ->
                val on = sim.handle == selected?.handle
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) NothingRed else Color.Transparent)
                        .clickable {
                            Haptics.play(context, Haptics.Effect.KEY)
                            onSelect(if (on) null else sim)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SimCard, null,
                        tint = if (on) Color.White else Muted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = sim.label,
                        fontSize = 12.sp,
                        color = if (on) Color.White else Muted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** The SIM set as default for calls, or null for "Ask every time". */
private fun defaultSim(context: Context, sims: List<SimOption>): SimOption? = try {
    val handle = context.getSystemService(TelecomManager::class.java)
        .getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
    sims.firstOrNull { it.handle == handle }
} catch (e: SecurityException) {
    null
}

private fun placeCall(
    context: Context,
    number: String,
    video: Boolean = false,
    sim: PhoneAccountHandle? = null,
    autoSim: Boolean = true
) {
    Haptics.play(context, Haptics.Effect.CALL_PLACED)
    CallPlacer.placeCall(context, number, video, sim, autoSim = autoSim)
}

private fun addContact(context: Context, number: String) {
    try {
        context.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Keeps what a dialer can dial from pasted text: digits, a leading "+", and
 * "*", "#", "," (pause) and ";" (wait). "Call +91 98765-43210 now" -> "+919876543210".
 */
internal fun dialableFrom(text: String): String {
    val kept = text.filter { it.isDigit() || it in "+*#,;" }
    // A "+" only means something at the very start.
    return kept.take(1) + kept.drop(1).replace("+", "")
}
