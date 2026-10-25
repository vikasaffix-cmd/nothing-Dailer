package com.example.allinonedailer.ui.contacts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.ui.components.DotMatrixText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val SheetPanel = Color(0xFF1E1F21)
private val SheetPill = Color(0xFF2C2D30)

@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var sheetContact by remember { mutableStateOf<ContactItem?>(null) }
    // While the long-press popup is open everything but the pressed row dims.
    val dim = if (sheetContact != null) 0.15f else 1f

    // Reload on every resume so edits made in the system editor show up.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadKey++ }
    LaunchedEffect(reloadKey) {
        if (reloadKey == 0) return@LaunchedEffect
        contacts = withContext(Dispatchers.IO) { ContactsRepository(context).loadContacts() }
    }

    // Favourite toggling and deleting need WRITE_CONTACTS, asked for on first use.
    var pendingWrite by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingWrite?.invoke()
        else Toast.makeText(context, "Contacts permission needed", Toast.LENGTH_SHORT).show()
        pendingWrite = null
    }
    fun withWriteAccess(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingWrite = action
            writePermission.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }
    fun toggleFavourite(contact: ContactItem) = withWriteAccess {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { setStarred(context, contact.id, !contact.starred) }
            if (ok) contacts = contacts.map {
                if (it.id == contact.id) it.copy(starred = !contact.starred) else it
            }
        }
    }

    val list = remember(contacts, searchQuery, favouritesOnly) {
        buildContactList(contacts, searchQuery, favouritesOnly)
    }
    val listState = rememberLazyListState()
    val currentSection by remember(list) {
        derivedStateOf { list.sectionOfRow.getOrNull(listState.firstVisibleItemIndex) ?: 0 }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: back to keypad, profile/settings badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(dim)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
            }
        }

        Column(
            modifier = Modifier
                .alpha(dim)
                .padding(horizontal = 16.dp)
        ) {
            DotMatrixText(text = "CONTACTS", pitch = 4.dp)
            Spacer(modifier = Modifier.height(14.dp))

            // Search + add
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("SEARCH", letterSpacing = 1.sp, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                        .clickable { addContact(context) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add contact",
                        tint = MaterialTheme.colorScheme.onTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Chip(
                    text = "☆ FAVOURITE",
                    selected = favouritesOnly,
                    onClick = { favouritesOnly = !favouritesOnly }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Chip(text = "TOTAL: ${list.contactCount}", selected = false, onClick = null)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 34.dp)
            ) {
                items(
                    items = list.rows,
                    key = { row ->
                        when (row) {
                            is ContactRowModel.Header -> "h:${row.letter}"
                            is ContactRowModel.Entry -> "c:${row.contact.id}"
                        }
                    },
                    contentType = { it::class }
                ) { row ->
                    when (row) {
                        is ContactRowModel.Header -> DotMatrixText(
                            text = row.letter,
                            pitch = 3.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .alpha(dim)
                                .padding(top = 18.dp, bottom = 8.dp)
                        )
                        is ContactRowModel.Entry -> {
                            val contact = row.contact
                            val pressed = sheetContact?.id == contact.id
                            ContactRow(
                                contact = contact,
                                onClick = { placeCall(context, contact.phoneNumber) },
                                onLongClick = { sheetContact = contact },
                                highlighted = pressed,
                                modifier = Modifier.alpha(if (pressed) 1f else dim)
                            ) {
                                if (pressed) ContactActionPopup(
                                    contact = contact,
                                    onDismiss = { sheetContact = null },
                                    onCall = { placeCall(context, contact.phoneNumber) },
                                    onText = { sendText(context, contact.phoneNumber) },
                                    onVideoCall = { placeVideoCall(context, contact.phoneNumber) },
                                    onToggleFavourite = { toggleFavourite(contact) },
                                    onCopy = { copyNumber(context, contact.phoneNumber) },
                                    onShare = { shareContact(context, contact.id) },
                                    onEdit = { editContact(context, contact.id) }
                                )
                            }
                        }
                    }
                }
            }

            if (list.sections.isNotEmpty()) {
                FastScroller(
                    sections = list.sections,
                    currentSection = currentSection,
                    onSectionSelected = { index ->
                        scope.launch { listState.scrollToItem(list.sections[index].rowIndex) }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .alpha(dim)
                        .padding(top = 8.dp, bottom = 16.dp, end = 6.dp)
                )
            }
        }
    }
}

private sealed interface ContactRowModel {
    data class Header(val letter: String) : ContactRowModel
    data class Entry(val contact: ContactItem) : ContactRowModel
}

private class Section(val letter: String, val rowIndex: Int)

/** Rows with section headers, the sections in order, and each row's section index. */
private class ContactList(
    val rows: List<ContactRowModel>,
    val sections: List<Section>,
    val sectionOfRow: IntArray,
    val contactCount: Int
)

private fun sectionLetter(name: String): String {
    val c = name.trim().firstOrNull()?.uppercaseChar()
    return if (c != null && c in 'A'..'Z') c.toString() else "#"
}

private fun buildContactList(all: List<ContactItem>, query: String, favouritesOnly: Boolean): ContactList {
    val q = query.trim()
    val digits = q.filter(Char::isDigit)
    val filtered = all.filter { c ->
        (!favouritesOnly || c.starred) && (q.isEmpty() ||
            c.name.contains(q, ignoreCase = true) ||
            (digits.isNotEmpty() && c.phoneNumber.filter(Char::isDigit).contains(digits)))
    }
    // Letters first, then "#" for names starting with digits or symbols.
    val grouped = filtered.groupBy { sectionLetter(it.name) }
    val letters = grouped.keys.sortedWith(compareBy({ it == "#" }, { it }))
    val rows = ArrayList<ContactRowModel>(filtered.size + letters.size)
    val sections = ArrayList<Section>(letters.size)
    val sectionOfRow = IntArray(filtered.size + letters.size)
    letters.forEachIndexed { sectionIndex, letter ->
        sections += Section(letter, rows.size)
        sectionOfRow[rows.size] = sectionIndex
        rows += ContactRowModel.Header(letter)
        grouped.getValue(letter).forEach {
            sectionOfRow[rows.size] = sectionIndex
            rows += ContactRowModel.Entry(it)
        }
    }
    return ContactList(rows, sections, sectionOfRow, filtered.size)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.tertiary else Color.Transparent)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                shape
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = if (selected) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: ContactItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    popup: @Composable () -> Unit = {}
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallerAvatar(
                name = contact.name,
                number = contact.phoneNumber,
                size = 42.dp,
                photoUri = contact.photoUri,
                pill = true,
                modifier = if (highlighted) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = contact.name.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (contact.starred) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Favourite",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF262626))
        popup()
    }
}

/**
 * Dotted A–Z rail: one dot per section, the current section drawn as a short
 * red bar. Tapping or dragging jumps to a section and shows a red letter
 * bubble to the left of the rail.
 */
@Composable
private fun FastScroller(
    sections: List<Section>,
    currentSection: Int,
    onSectionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var railHeight by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var selected by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current

    // The gesture handler outlives list reloads, so it reads the latest sections.
    val currentSections by rememberUpdatedState(sections)
    val currentOnSelected by rememberUpdatedState(onSectionSelected)

    fun select(y: Float) {
        val count = currentSections.size
        if (count == 0) return
        touchY = y.coerceIn(0f, railHeight.toFloat())
        val index = ((touchY / railHeight) * count).toInt().coerceIn(0, count - 1)
        if (index != selected) {
            selected = index
            currentOnSelected(index)
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(22.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF111111))
                .onSizeChanged { railHeight = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    // One gesture covers both tap and drag: follow the finger until it lifts.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        selected = -1
                        try {
                            select(down.position.y)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                select(change.position.y)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                }
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val active = if (dragging && selected >= 0) selected else currentSection
            sections.indices.forEach { i ->
                if (i == active) {
                    Box(
                        modifier = Modifier
                            .size(width = 5.dp, height = 18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                    )
                }
            }
        }

        if (dragging && selected in sections.indices) {
            val bubbleHeight = 64.dp
            val bubbleHeightPx = with(density) { bubbleHeight.toPx() }
            val y = (touchY - bubbleHeightPx / 2).coerceIn(0f, (railHeight - bubbleHeightPx).coerceAtLeast(0f))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y.roundToInt()) }
                    .padding(end = 60.dp)
                    .size(width = 84.dp, height = bubbleHeight)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = sections[selected].letter,
                    fontSize = 36.sp,
                    color = Color.White
                )
            }
        }
    }
}

/** Places the popup just under its anchor row, or above it when there is no room below. */
private class BelowAnchorPosition(private val topInsetPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = (windowSize.width - popupContentSize.width) / 2
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - popupContentSize.height).coerceAtLeast(topInsetPx)
        return IntOffset(x, y)
    }
}

/**
 * Long-press actions for a contact, anchored to the pressed row: a 2×2 grid of
 * pills (Call, Text, Video call, favourite) and round Copy / Share / Edit
 * buttons. Tapping outside or the back gesture closes it.
 */
@Composable
private fun ContactActionPopup(
    contact: ContactItem,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onText: () -> Unit,
    onVideoCall: () -> Unit,
    onToggleFavourite: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit
) {
    // Every action closes the popup first.
    fun act(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Popup(
        popupPositionProvider = remember(density) { BelowAnchorPosition(with(density) { 48.dp.roundToPx() }) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(screenWidth - 32.dp)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(SheetPanel)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPillButton(Icons.Default.Call, "Call", act(onCall), Modifier.weight(1f))
                SheetPillButton(Icons.AutoMirrored.Filled.Message, "Text", act(onText), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPillButton(Icons.Default.Videocam, "Video call", act(onVideoCall), Modifier.weight(1f))
                SheetPillButton(
                    Icons.Default.Star,
                    if (contact.starred) "Remove from fav." else "Add to fav.",
                    act(onToggleFavourite),
                    Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SheetRoundButton(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy number",
                    background = MaterialTheme.colorScheme.tertiary,
                    tint = Color.White,
                    onClick = act(onCopy)
                )
                SheetRoundButton(
                    icon = Icons.Default.Share,
                    label = "Share",
                    background = Color.White,
                    tint = Color.Black,
                    onClick = act(onShare)
                )
                SheetRoundButton(
                    icon = Icons.Default.Edit,
                    label = "Edit",
                    background = Color.Transparent,
                    tint = Color.White,
                    outlined = true,
                    onClick = act(onEdit)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SheetPillButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(SheetPill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Default,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )
    }
}

@Composable
private fun SheetRoundButton(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}
private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "No app can handle this", Toast.LENGTH_SHORT).show()
    }
}

private fun placeCall(context: Context, number: String) {
    startSafely(context, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
}

private fun placeVideoCall(context: Context, number: String) {
    startSafely(context, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).apply {
        putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
    })
}

private fun sendText(context: Context, number: String) {
    startSafely(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
}

private fun contactUri(id: String): Uri =
    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLong())

private fun setStarred(context: Context, id: String, starred: Boolean): Boolean = try {
    val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
    context.contentResolver.update(contactUri(id), values, null, null) > 0
} catch (e: Exception) {
    e.printStackTrace()
    false
}

private fun copyNumber(context: Context, number: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", number))
    Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
}

private fun shareContact(context: Context, id: String) {
    val lookupKey = try {
        context.contentResolver.query(
            contactUri(id), arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    } ?: return
    val vcard = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
        putExtra(Intent.EXTRA_STREAM, vcard)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startSafely(context, Intent.createChooser(send, "Share contact"))
}

private fun editContact(context: Context, id: String) {
    startSafely(context, Intent(Intent.ACTION_EDIT, contactUri(id)).apply {
        putExtra("finishActivityOnSaveCompleted", true)
    })
}

private fun addContact(context: Context) {
    startSafely(context, Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
    })
}
