package com.example.allinonedailer.ui.dialer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.Haptics
import com.example.allinonedailer.ui.components.CallerAvatar
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource
import com.example.allinonedailer.ui.theme.DialerTheme


/**
 * Starred contacts as a horizontal strip for the empty keypad. Tap calls;
 * long-press offers a video call. Draws nothing when there are no favourites.
 */
@Composable
fun FavouritesRow(
    favourites: List<ContactItem>,
    onCall: (number: String, video: Boolean) -> Unit
) {
    if (favourites.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.favourites).uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace,
            color = DialerTheme.colors.muted,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(favourites, key = { it.id }) { contact ->
                FavouriteChip(contact = contact, onCall = onCall)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = DialerTheme.colors.divider)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavouriteChip(contact: ContactItem, onCall: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .width(66.dp)
                .combinedClickable(
                    onClick = { onCall(contact.phoneNumber, false) },
                    onLongClick = {
                        Haptics.play(context, Haptics.Effect.KEY_LONG)
                        menuOpen = true
                    }
                )
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CallerAvatar(
                name = contact.name,
                number = contact.phoneNumber,
                photoUri = contact.photoUri,
                size = 48.dp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = firstName(contact.name).uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                color = DialerTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(DialerTheme.colors.panel)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_call), fontFamily = FontFamily.Monospace, color = DialerTheme.colors.ink) },
                leadingIcon = { Icon(Icons.Default.Videocam, null, tint = DialerTheme.colors.ink, modifier = Modifier.size(18.dp)) },
                onClick = {
                    menuOpen = false
                    onCall(contact.phoneNumber, true)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.voice_call), fontFamily = FontFamily.Monospace, color = DialerTheme.colors.ink) },
                leadingIcon = { Icon(Icons.Default.Call, null, tint = DialerTheme.colors.ink, modifier = Modifier.size(18.dp)) },
                onClick = {
                    menuOpen = false
                    onCall(contact.phoneNumber, false)
                }
            )
        }
    }
}

private fun firstName(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifEmpty { name }
