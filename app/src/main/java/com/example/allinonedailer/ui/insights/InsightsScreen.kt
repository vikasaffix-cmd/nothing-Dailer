package com.example.allinonedailer.ui.insights

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.formatCallDuration
import com.example.allinonedailer.data.insights.ContactStat
import com.example.allinonedailer.data.insights.InsightCall
import com.example.allinonedailer.data.insights.InsightRange
import com.example.allinonedailer.data.insights.Insights
import com.example.allinonedailer.data.insights.InsightsAggregator
import com.example.allinonedailer.data.insights.InsightsRepository
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.theme.DialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class RangeChoice { WEEK, MONTH, CUSTOM }

/** Insights: talk time, in/out/missed, per SIM, top contacts, busiest hours and longest calls for a date range. */
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    /** Opens Recents searching for this number key. */
    onOpenContact: (String) -> Unit
) {
    val context = LocalContext.current
    var choice by rememberSaveable { mutableStateOf(RangeChoice.WEEK) }
    // Custom dates as epoch days, so they survive rotation.
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var byTalk by rememberSaveable { mutableStateOf(true) }

    val calls by produceState<List<InsightCall>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { InsightsRepository.loadCalls(context) }
    }
    val simLabels by produceState(initialValue = emptyMap<String, String>()) {
        value = withContext(Dispatchers.IO) { InsightsRepository.simLabels(context) }
    }
    val otherLabel = stringResource(R.string.insights_other_sim)

    val zone = ZoneId.systemDefault()
    val range = remember(choice, customFrom, customTo) {
        val now = System.currentTimeMillis()
        when (choice) {
            RangeChoice.WEEK -> InsightRange.thisWeek(now, zone)
            RangeChoice.MONTH -> InsightRange.thisMonth(now, zone)
            RangeChoice.CUSTOM -> {
                val from = customFrom?.let(LocalDate::ofEpochDay)
                val to = customTo?.let(LocalDate::ofEpochDay) ?: from
                if (from == null || to == null) InsightRange.thisMonth(now, zone) else InsightRange.days(from, to, zone)
            }
        }
    }
    val insights by produceState<Insights?>(initialValue = null, calls, range, simLabels) {
        val all = calls ?: return@produceState
        value = withContext(Dispatchers.Default) {
            InsightsAggregator.aggregate(all, range, zone, simLabels, otherLabel)
        }
    }

    val hm = stringResource(R.string.duration_hm)
    val ms = stringResource(R.string.duration_ms)
    val s = stringResource(R.string.duration_s)
    val duration: (Long) -> String = { formatCallDuration(it, hm, ms, s) }

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
            DotMatrixText(text = stringResource(R.string.insights_title).uppercase(), pitch = 4.dp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.insights_range_week), choice == RangeChoice.WEEK) { choice = RangeChoice.WEEK }
                Pill(stringResource(R.string.insights_range_month), choice == RangeChoice.MONTH) { choice = RangeChoice.MONTH }
                Pill(stringResource(R.string.insights_range_custom), choice == RangeChoice.CUSTOM) { picking = true }
            }
            Spacer(Modifier.height(8.dp))
            Text(rangeLabel(range, zone), fontSize = 12.sp, color = DialerTheme.colors.muted)

            val data = insights
            when {
                data == null -> Spacer(Modifier.height(200.dp))
                data.totalCalls == 0 -> Text(
                    stringResource(R.string.insights_empty).uppercase(),
                    color = DialerTheme.colors.muted,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 48.dp)
                )
                else -> InsightsBody(data, byTalk, { byTalk = it }, duration, onOpenContact)
            }
        }
    }

    if (picking) RangePickerDialog(
        onDismiss = { picking = false },
        onPick = { from, to ->
            picking = false
            customFrom = from.toEpochDay()
            customTo = to.toEpochDay()
            choice = RangeChoice.CUSTOM
        }
    )
}

@Composable
private fun InsightsBody(
    data: Insights,
    byTalk: Boolean,
    onByTalk: (Boolean) -> Unit,
    duration: (Long) -> String,
    onOpenContact: (String) -> Unit
) {
    // Talk time, big
    Section(stringResource(R.string.insights_talk_time))
    DotMatrixText(text = duration(data.talkSeconds), pitch = 6.dp, maxLines = 1)
    Text(
        pluralStringResource(R.plurals.insights_calls_count, data.totalCalls, data.totalCalls),
        fontSize = 13.sp,
        color = DialerTheme.colors.muted,
        modifier = Modifier.padding(top = 6.dp)
    )

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatBox(stringResource(R.string.insights_calls_in), data.incoming, DialerTheme.colors.ink, Modifier.weight(1f))
        StatBox(stringResource(R.string.insights_calls_out), data.outgoing, DialerTheme.colors.ink, Modifier.weight(1f))
        StatBox(stringResource(R.string.insights_calls_missed), data.missed, DialerTheme.colors.accent, Modifier.weight(1f))
    }

    // Missed-call rate
    Section(stringResource(R.string.insights_missed_rate))
    val percent = (data.missedRate * 100).roundToInt()
    Text("%d%%".format(percent), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (percent > 0) DialerTheme.colors.accent else DialerTheme.colors.ink)
    Text(stringResource(R.string.insights_missed_rate_value, percent), fontSize = 12.sp, color = DialerTheme.colors.muted)
    Spacer(Modifier.height(8.dp))
    DotBar(fraction = data.missedRate, color = DialerTheme.colors.accent)

    // Per SIM
    if (data.perSim.isNotEmpty()) {
        Section(stringResource(R.string.insights_per_sim))
        val maxTalk = data.perSim.maxOf { it.talkSeconds }.coerceAtLeast(1)
        data.perSim.forEachIndexed { i, sim ->
            BarRow(
                title = sim.label,
                value = duration(sim.talkSeconds),
                detail = pluralStringResource(R.plurals.insights_calls_count, sim.calls, sim.calls),
                fraction = sim.talkSeconds.toFloat() / maxTalk,
                color = if (i == 0) DialerTheme.colors.ink else DialerTheme.colors.accent
            )
        }
    }

    // Busiest hours
    Section(stringResource(R.string.insights_busiest_hours))
    HourChart(data.callsByHour, data.busiestHour)
    data.busiestHour?.let {
        Text(
            stringResource(R.string.insights_busiest_at, hourLabel(it)),
            fontSize = 12.sp,
            color = DialerTheme.colors.muted,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    // Top contacts
    Section(stringResource(R.string.insights_top_contacts))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.insights_by_talk), byTalk) { onByTalk(true) }
        Pill(stringResource(R.string.insights_by_count), !byTalk) { onByTalk(false) }
    }
    Text(
        stringResource(R.string.insights_open_recents),
        fontSize = 11.sp,
        color = DialerTheme.colors.muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
    )
    val top = if (byTalk) data.topByTalk else data.topByCount
    val maxValue = top.maxOfOrNull { if (byTalk) it.talkSeconds else it.calls.toLong() }?.coerceAtLeast(1) ?: 1
    top.forEachIndexed { i, c ->
        ContactRow(i + 1, c, byTalk, maxValue, duration) { onOpenContact(c.key) }
    }

    // Longest calls
    if (data.longest.isNotEmpty()) {
        Section(stringResource(R.string.insights_longest))
        val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()) }
        data.longest.forEach { call ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenContact(PhoneNumbers.key(call.number).ifEmpty { call.number }) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(call.name ?: call.number, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Instant.ofEpochMilli(call.dateMs).atZone(ZoneId.systemDefault()).format(dateFormat),
                        fontSize = 12.sp,
                        color = DialerTheme.colors.muted
                    )
                }
                Text(duration(call.durationSeconds), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    DotMatrixText(text = title.uppercase(), pitch = 2.4.dp, color = DialerTheme.colors.muted)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) Modifier.background(DialerTheme.colors.ink)
                else Modifier.border(1.dp, DialerTheme.colors.muted.copy(alpha = 0.5f), RoundedCornerShape(50))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 1.sp, color = if (selected) DialerTheme.colors.onInk else DialerTheme.colors.ink)
    }
}

@Composable
private fun StatBox(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DialerTheme.colors.panel)
            .padding(12.dp)
    ) {
        DotMatrixText(text = "%d".format(value), pitch = 3.5.dp, color = color)
        Spacer(Modifier.height(6.dp))
        Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = DialerTheme.colors.muted)
    }
}

@Composable
private fun BarRow(title: String, value: String, detail: String?, fraction: Float, color: Color, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (detail != null) Text(detail, fontSize = 12.sp, color = DialerTheme.colors.muted)
        Spacer(Modifier.height(6.dp))
        DotBar(fraction = fraction, color = color)
    }
}

@Composable
private fun ContactRow(rank: Int, c: ContactStat, byTalk: Boolean, maxValue: Long, duration: (Long) -> String, onClick: () -> Unit) {
    val count = pluralStringResource(R.plurals.insights_calls_count, c.calls, c.calls)
    BarRow(
        title = "%d. %s".format(rank, c.name ?: c.number),
        value = if (byTalk) duration(c.talkSeconds) else count,
        detail = if (byTalk) count else duration(c.talkSeconds),
        fraction = (if (byTalk) c.talkSeconds else c.calls.toLong()).toFloat() / maxValue,
        color = if (rank == 1) DialerTheme.colors.accent else DialerTheme.colors.ink,
        onClick = onClick
    )
}

/** A row of dots, the first [fraction] of them lit: the Nothing-style bar. */
@Composable
private fun DotBar(fraction: Float, color: Color, dot: Dp = 4.dp, pitch: Dp = 6.dp) {
    val dim = DialerTheme.colors.ink.copy(alpha = 0.15f)
    Canvas(modifier = Modifier.fillMaxWidth().height(dot)) {
        val p = pitch.toPx()
        val r = dot.toPx() / 2
        val n = (size.width / p).toInt().coerceAtLeast(1)
        val lit = if (fraction <= 0f) 0 else (fraction.coerceAtMost(1f) * n).roundToInt().coerceAtLeast(1)
        for (i in 0 until n) {
            drawCircle(if (i < lit) color else dim, r, Offset(i * p + r, size.height / 2))
        }
    }
}

/** 24 columns of dots, one per hour; the busiest hour in red. */
@Composable
private fun HourChart(counts: IntArray, busiest: Int?) {
    val ink = DialerTheme.colors.ink
    val accent = DialerTheme.colors.accent
    val dim = ink.copy(alpha = 0.12f)
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val colW = size.width / 24f
            val r = (colW * 0.32f).coerceAtMost(3.dp.toPx())
            val pitch = r * 2 + 2.dp.toPx()
            val rows = (size.height / pitch).toInt().coerceAtLeast(1)
            val max = counts.maxOrNull()?.takeIf { it > 0 } ?: 1
            for (h in 0 until 24) {
                val lit = if (counts[h] == 0) 0 else ceil(counts[h].toDouble() / max * rows).toInt()
                val x = h * colW + colW / 2
                for (row in 0 until rows) {
                    val y = size.height - row * pitch - r
                    val color = when {
                        row >= lit -> dim
                        h == busiest -> accent
                        else -> ink
                    }
                    drawCircle(color, r, Offset(x, y))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            for (h in listOf(0, 6, 12, 18)) {
                Text(hourLabel(h), fontSize = 10.sp, color = DialerTheme.colors.muted, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun hourLabel(hour: Int): String {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val pattern = if (is24) "HH:mm" else "h a"
    return LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private fun rangeLabel(range: InsightRange, zone: ZoneId): String {
    val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    val from = Instant.ofEpochMilli(range.startMs).atZone(zone).toLocalDate()
    val to = Instant.ofEpochMilli(range.endMs - 1).atZone(zone).toLocalDate()
    return if (from == to) from.format(f) else "${from.format(f)} – ${to.format(f)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    // The picker's millis are UTC midnights of the chosen days.
                    val from = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    val to = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: from
                    if (from != null && to != null) onPick(from, to)
                }
            ) { Text(stringResource(R.string.insights_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    ) {
        DateRangePicker(
            state = state,
            title = { Text(stringResource(R.string.insights_pick_dates), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            modifier = Modifier.weight(1f)
        )
    }
}
