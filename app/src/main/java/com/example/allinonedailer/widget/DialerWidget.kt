package com.example.allinonedailer.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity as actionStartIntent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.allinonedailer.R
import com.example.allinonedailer.ui.MainActivity
import com.example.allinonedailer.ui.components.initialFor

// Resource colours, so the widget follows the phone's light/dark mode (values-night).
private val White = ColorProvider(R.color.widget_ink)
private val Grey = ColorProvider(R.color.widget_muted)
private val Red = ColorProvider(R.color.widget_red)

private val Padding = 14.dp
private val HeaderHeight = 20.dp
private val Gap = 8.dp
private val NameHeight = 14.dp
private val RecentRowHeight = 30.dp

/**
 * Home-screen widget in the Nothing OS style: favourites as round black-and-white
 * avatars, plus recent calls when the widget is tall enough. Every row places a call.
 */
class DialerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp),
            DpSize(140.dp, 150.dp),
            DpSize(250.dp, 110.dp),
            DpSize(110.dp, 250.dp),
            DpSize(250.dp, 250.dp),
            DpSize(250.dp, 360.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = WidgetData.state.value
            ?: WidgetData.cachedSnapshot(context)
            ?: WidgetData.refresh(context)
        provideContent {
            val snapshot by WidgetData.state.collectAsState()
            LaunchedEffect(Unit) {
                if (WidgetData.state.value == null) WidgetData.refresh(context)
            }
            WidgetContent(snapshot ?: first)
        }
    }
}

@Composable
private fun WidgetContent(data: WidgetSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val innerWidth = size.width - Padding * 2
    val cols = if (size.width >= 200.dp) 4 else 2
    val showHeader = if (cols == 4) size.height >= 100.dp else size.height >= 140.dp
    var available = size.height - Padding * 2 - (if (showHeader) HeaderHeight + Gap else 0.dp)

    val favourites = data.favourites
    val favRows = if (favourites.size > 2 && cols == 2) 2 else 1
    var avatar = min(52.dp, (innerWidth - Gap * (cols - 1)) / cols)
    avatar = min(avatar, (available - Gap * (favRows - 1)) / favRows)
    val showNames = cols == 4 && available >= avatar + NameHeight + 40.dp
    val favBlock = if (favourites.isEmpty()) 32.dp
    else avatar * favRows + Gap * (favRows - 1) + (if (showNames) NameHeight else 0.dp)
    available -= favBlock + Gap
    val recentRows = if (available >= RecentRowHeight) {
        (available / RecentRowHeight).toInt().coerceAtMost(data.recents.size)
    } else 0

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(ImageProvider(R.drawable.widget_background))
            .padding(Padding)
    ) {
        if (showHeader) {
            Header(data)
            Spacer(GlanceModifier.height(Gap))
        }
        if (favourites.isEmpty()) {
            Message(
                context.getString(
                    if (data.canReadContacts) R.string.widget_no_favourites else R.string.widget_needs_permission
                ),
                GlanceModifier.height(favBlock).fillMaxWidth()
            )
        } else {
            favourites.chunked(cols).take(favRows).forEachIndexed { index, rowItems ->
                if (index > 0) Spacer(GlanceModifier.height(Gap))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    rowItems.forEachIndexed { i, fav ->
                        if (i > 0) Spacer(GlanceModifier.defaultWeight())
                        Favourite(fav, avatar, showNames)
                    }
                    // Keep a short last row left-aligned with the row above.
                    repeat(cols - rowItems.size) {
                        Spacer(GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(avatar))
                    }
                }
            }
        }
        if (recentRows > 0) {
            Spacer(GlanceModifier.height(Gap))
            data.recents.take(recentRows).forEach { RecentRow(it, showTime = size.width >= 160.dp) }
        } else if (data.recents.isEmpty() && available >= RecentRowHeight * 2) {
            Spacer(GlanceModifier.height(Gap))
            Message(
                context.getString(
                    if (data.canReadCallLog) R.string.widget_no_recents else R.string.widget_needs_permission
                ),
                GlanceModifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Header(data: WidgetSnapshot) {
    val context = LocalContext.current
    val title = context.getString(R.string.widget_title)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .clickable(actionStartActivity<MainActivity>())
            .semantics { contentDescription = context.getString(R.string.widget_open_app) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (data.title != null) {
            // Drawn white; tinted to the widget's ink colour.
            Image(
                provider = ImageProvider(data.title),
                contentDescription = title,
                colorFilter = ColorFilter.tint(White)
            )
        } else {
            Text(title.uppercase(), style = textStyle(White, 14.sp, bold = true))
        }
        Spacer(GlanceModifier.defaultWeight())
        Image(
            provider = ImageProvider(R.drawable.widget_red_dot),
            contentDescription = null,
            modifier = GlanceModifier.size(8.dp)
        )
    }
}

@Composable
private fun Favourite(fav: WidgetFavourite, avatar: Dp, showName: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .clickable(callAction(context, fav.number))
            .semantics { contentDescription = context.getString(R.string.widget_call, fav.name) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (fav.photo != null) {
            Image(
                provider = ImageProvider(fav.photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(avatar)
            )
        } else {
            Box(
                modifier = GlanceModifier.size(avatar).background(ImageProvider(R.drawable.widget_avatar_background)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initialFor(fav.name, fav.number),
                    style = textStyle(White, (avatar.value / 2.4f).sp, bold = true)
                )
            }
        }
        if (showName) {
            Text(
                fav.name.substringBefore(' '),
                maxLines = 1,
                style = textStyle(Grey, 10.sp, align = TextAlign.Center),
                modifier = GlanceModifier.width(avatar + 8.dp).height(NameHeight)
            )
        }
    }
}

@Composable
private fun RecentRow(recent: WidgetRecent, showTime: Boolean) {
    val context = LocalContext.current
    val (icon, typeLabel) = when (recent.callType) {
        CallLog.Calls.OUTGOING_TYPE -> R.drawable.widget_ic_outgoing to R.string.widget_outgoing
        CallLog.Calls.MISSED_TYPE -> R.drawable.widget_ic_missed to R.string.widget_missed
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> R.drawable.widget_ic_declined to R.string.widget_declined
        else -> R.drawable.widget_ic_incoming to R.string.widget_incoming
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(RecentRowHeight)
            .clickable(callAction(context, recent.number))
            .semantics { contentDescription = context.getString(R.string.widget_call, recent.label) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = context.getString(typeLabel),
            modifier = GlanceModifier.size(14.dp)
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            recent.label,
            maxLines = 1,
            style = textStyle(if (recent.callType == CallLog.Calls.MISSED_TYPE) Red else White, 13.sp),
            modifier = GlanceModifier.defaultWeight()
        )
        if (showTime) {
            Spacer(GlanceModifier.width(6.dp))
            Text(recent.time, maxLines = 1, style = textStyle(Grey, 11.sp))
        }
    }
}

@Composable
private fun Message(text: String, modifier: GlanceModifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Text(text, maxLines = 2, style = textStyle(Grey, 12.sp))
    }
}

private fun textStyle(
    color: ColorProvider,
    size: TextUnit,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Start
) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = FontFamily.Monospace,
    textAlign = align
)

/** Calls go through [WidgetCallActivity]: Android blocks service/receiver trampolines from widgets. */
private fun callAction(context: Context, number: String): Action = actionStartIntent(
    Intent(context, WidgetCallActivity::class.java)
        .setData(Uri.fromParts("tel", number, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
)
