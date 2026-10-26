package com.example.allinonedailer.ui.incall

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.CallerCard
import com.example.allinonedailer.data.CallerCardLoader
import com.example.allinonedailer.data.CardDay
import com.example.allinonedailer.data.ContactEventKind
import com.example.allinonedailer.data.LastCallKind
import com.example.allinonedailer.ui.theme.DialerTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The compact card under a ringing caller's name: birthday, call-back
 * reminder, last note, last call and missed calls this week, whichever exist.
 * Loaded in the background; nothing shows until it's ready, or at all when
 * there's nothing to say.
 */
@Composable
fun CallerCardView(number: String, modifier: Modifier = Modifier, fillWidth: Boolean = false) {
    val context = LocalContext.current
    val card by produceState<CallerCard?>(null, number) { value = CallerCardLoader.load(context, number) }
    AnimatedVisibility(visible = card != null, enter = fadeIn() + expandVertically(), modifier = modifier) {
        card?.let { CardBody(it, fillWidth) }
    }
}

@Composable
private fun CardBody(card: CallerCard, fillWidth: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    val colors = DialerTheme.colors
    // In the pill every line stays on one row so the expanded card stays phone-sized.
    val lines = if (fillWidth) 1 else 2
    Column(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(max = 420.dp))
            .clip(shape)
            .background(colors.panel.copy(alpha = 0.88f))
            .border(1.dp, colors.keyOutline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        card.eventsToday.forEach { kind ->
            CardLine(
                stringResource(
                    if (kind == ContactEventKind.BIRTHDAY) R.string.caller_card_birthday else R.string.caller_card_anniversary
                ),
                lines,
                accent = true
            )
        }
        card.reminderAt?.let { CardLine(stringResource(R.string.caller_card_reminder, whenText(it)), lines, accent = true) }
        card.note?.let { CardLine(stringResource(R.string.call_note_last, it), lines) }
        card.lastCall?.let { last ->
            val parts = buildList {
                add(
                    when (last.day) {
                        CardDay.TODAY -> stringResource(R.string.caller_card_today)
                        CardDay.YESTERDAY -> stringResource(R.string.caller_card_yesterday)
                        CardDay.EARLIER -> shortDate(last.date)
                    }
                )
                if (last.durationSeconds > 0) add(
                    if (last.durationSeconds < 60) stringResource(R.string.caller_card_seconds, last.durationSeconds)
                    else stringResource(R.string.caller_card_minutes, (last.durationSeconds + 30) / 60)
                )
                add(
                    stringResource(
                        when (last.kind) {
                            LastCallKind.INCOMING -> R.string.caller_card_incoming
                            LastCallKind.OUTGOING -> R.string.caller_card_outgoing
                            LastCallKind.MISSED -> R.string.caller_card_missed
                            LastCallKind.REJECTED -> R.string.caller_card_declined
                        }
                    )
                )
            }
            CardLine(stringResource(R.string.caller_card_last_call, parts.joinToString(", ")), lines)
        }
        if (card.missedThisWeek > 0) {
            CardLine(pluralStringResource(R.plurals.caller_card_missed_week, card.missedThisWeek, card.missedThisWeek), lines)
        }
    }
}

@Composable
private fun CardLine(text: String, maxLines: Int, accent: Boolean = false) {
    val colors = DialerTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Nothing-style dot bullet; red for the things worth acting on.
        Spacer(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (accent) colors.accent else colors.muted)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (accent) colors.ink else colors.ink.copy(alpha = 0.85f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun whenText(at: Long): String {
    val context = LocalContext.current
    val time = DateFormat.getTimeFormat(context).format(Date(at))
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.caller_card_today_at, time)
        today.plusDays(1) -> stringResource(R.string.caller_card_tomorrow_at, time)
        else -> "${shortDate(at)}, $time"
    }
}

private fun shortDate(at: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "d MMM"), locale).format(Date(at))
}
