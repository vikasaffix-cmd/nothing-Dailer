package com.example.allinonedailer.ui.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as TileIcon
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.allinonedailer.R
import com.example.allinonedailer.data.ContactItem
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.TileFavourite
import com.example.allinonedailer.data.TileLogic
import com.example.allinonedailer.service.tiles.CallFavouriteTileService
import com.example.allinonedailer.service.tiles.LastCallerTileService
import com.example.allinonedailer.service.tiles.QuietHoursTileService
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.theme.DialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → "Quick Settings tiles": who the "Call favourite" tile calls
 * (default: the first starred contact) and, on Android 13+, buttons that
 * offer each tile to the Quick Settings panel.
 */
@Composable
fun QuickTilesSettingsRow() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(TileFavourite.chosenName(context) ?: TileFavourite.chosenNumber(context)) }
    val starred by produceState(initialValue = emptyList<ContactItem>()) {
        value = withContext(Dispatchers.IO) { loadStarred(context) }
    }
    val auto = TileLogic.favouriteTarget(null, null, starred)
    val current = chosen ?: auto?.let { context.getString(R.string.tiles_favourite_auto_named, it.name ?: it.number) }
        ?: context.getString(R.string.tile_no_favourite)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = stringResource(R.string.tiles_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                text = stringResource(R.string.tiles_summary, current),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(DialerTheme.colors.panel)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DotMatrixText(text = stringResource(R.string.tiles_title).uppercase(), pitch = 3.dp, color = DialerTheme.colors.ink)
                Text(
                    text = stringResource(R.string.tiles_help),
                    fontSize = 13.sp,
                    color = DialerTheme.colors.muted
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    TilePill(stringResource(R.string.tiles_add, stringResource(R.string.tile_quiet_hours)), false) {
                        requestAdd(context, QuietHoursTileService::class.java, R.string.tile_quiet_hours, R.drawable.ic_tile_quiet_hours)
                    }
                    TilePill(stringResource(R.string.tiles_add, stringResource(R.string.tile_call_favourite)), false) {
                        requestAdd(context, CallFavouriteTileService::class.java, R.string.tile_call_favourite, R.drawable.ic_tile_favourite)
                    }
                    TilePill(stringResource(R.string.tiles_add, stringResource(R.string.tile_last_caller)), false) {
                        requestAdd(context, LastCallerTileService::class.java, R.string.tile_last_caller, R.drawable.ic_tile_last_caller)
                    }
                }

                Text(
                    text = stringResource(R.string.tiles_favourite_heading).uppercase(),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = DialerTheme.colors.muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TilePill(
                    auto?.let { stringResource(R.string.tiles_favourite_auto_named, it.name ?: it.number) }
                        ?: stringResource(R.string.tiles_favourite_auto),
                    chosen == null
                ) {
                    TileFavourite.choose(context, null)
                    chosen = null
                    open = false
                }
                val chosenNumber = TileFavourite.chosenNumber(context)
                starred.forEach { contact ->
                    TilePill(contact.name, chosenNumber != null && PhoneNumbers.sameNumber(chosenNumber, contact.phoneNumber)) {
                        TileFavourite.choose(context, contact)
                        chosen = contact.name
                        open = false
                    }
                }
                if (starred.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tiles_no_favourites),
                        fontSize = 13.sp,
                        color = DialerTheme.colors.muted
                    )
                }
            }
        }
    }
}

@Composable
private fun TilePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 15.sp,
        color = if (selected) DialerTheme.colors.onAccent else DialerTheme.colors.ink,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (selected) DialerTheme.colors.accent else DialerTheme.colors.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}

private fun loadStarred(context: Context): List<ContactItem> {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return try {
        ContactsRepository(context).loadContacts().filter { it.starred }
    } catch (e: SecurityException) {
        emptyList()
    }
}

/** Android 13+: the system asks "Add tile to Quick Settings?" (the app must be in front). */
private fun requestAdd(context: Context, service: Class<*>, @StringRes label: Int, @DrawableRes icon: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    try {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, service),
            context.getString(label),
            TileIcon.createWithResource(context, icon),
            context.mainExecutor
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(context, R.string.tiles_already_added, Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, R.string.tiles_add_failed, Toast.LENGTH_LONG).show()
    }
}
