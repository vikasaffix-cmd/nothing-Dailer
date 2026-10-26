package com.example.allinonedailer.ui.settings

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.data.BlockRules
import com.example.allinonedailer.data.CallerIdRepository
import com.example.allinonedailer.ui.components.DotMatrixText
import com.example.allinonedailer.ui.theme.NothingRed
import com.example.allinonedailer.ui.theme.NothingWhite
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource

private const val PROMO_PREFIX = "140"

/** Blocked numbers, "starts with" rules, hidden-number blocking and local spam reports. */
@Composable
fun BlockRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { CallerIdRepository(context) }

    // Bumped after each change so the lists below re-read the repository.
    var version by remember { mutableIntStateOf(0) }
    val rules = remember(version) { repo.getPrefixRules() }
    val reports = remember(version) { repo.getSpamReports() }
    val reportedKeys = remember(reports) { reports.map { it.phoneNumber.filter(Char::isDigit).takeLast(10) }.toSet() }
    val blocked = remember(version) {
        // Reported numbers are listed under "Reported spam" instead.
        repo.getBlockedNumbers().filterNot { it.filter(Char::isDigit).takeLast(10) in reportedKeys }.sorted()
    }
    var blockHidden by remember { mutableStateOf(repo.isBlockHiddenEnabled()) }

    var prefixInput by rememberSaveable { mutableStateOf("") }
    var numberInput by rememberSaveable { mutableStateOf("") }
    var reportInput by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            DotMatrixText(text = stringResource(R.string.blocking_title), pitch = 4.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.blocking_intro),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Hidden numbers ----
            SectionLabel(stringResource(R.string.blocking_hidden_numbers).uppercase())
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.blocking_hidden_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        text = stringResource(R.string.blocking_hidden_summary),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = blockHidden,
                    onCheckedChange = {
                        blockHidden = it
                        repo.setBlockHiddenEnabled(it)
                    },
                    colors = redSwitchColors()
                )
            }

            // ---- Starts-with rules ----
            SectionLabel(stringResource(R.string.blocking_starts_with).uppercase())
            if (rules.none { it.prefix == PROMO_PREFIX }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            repo.addPrefixRule(PROMO_PREFIX)
                            version++
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.blocking_140_title),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.blocking_140_summary),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("+ " + stringResource(R.string.add).uppercase(), color = NothingRed, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
                    }
                }
            }
            Text(
                text = stringResource(R.string.blocking_160_note),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            rules.forEach { rule ->
                ListRow(
                    title = "${rule.prefix}…",
                    subtitle = if (rule.enabled) stringResource(R.string.blocking_prefix_on, rule.prefix) else stringResource(R.string.off),
                    onRemove = {
                        repo.removePrefixRule(rule.prefix)
                        version++
                    },
                    trailing = {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = {
                                repo.setPrefixRuleEnabled(rule.prefix, it)
                                version++
                            },
                            colors = redSwitchColors()
                        )
                    }
                )
            }

            val cleanedPrefix = BlockRules.cleanPrefix(prefixInput)
            AddRow(
                value = prefixInput,
                onValueChange = { prefixInput = it },
                label = stringResource(R.string.blocking_prefix_hint),
                button = stringResource(R.string.add).uppercase(),
                onAdd = {
                    if (repo.addPrefixRule(prefixInput)) {
                        prefixInput = ""
                        version++
                    }
                }
            )
            if (cleanedPrefix.startsWith("160")) {
                Text(
                    text = stringResource(R.string.blocking_160_warning),
                    fontSize = 12.sp,
                    color = NothingRed,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ---- Exact numbers ----
            SectionLabel(stringResource(R.string.blocking_blocked_numbers).uppercase())
            if (blocked.isEmpty()) EmptyNote(stringResource(R.string.blocking_none_blocked))
            blocked.forEach { number ->
                ListRow(
                    title = number,
                    subtitle = null,
                    onRemove = {
                        repo.unblockNumber(number)
                        version++
                    }
                )
            }
            AddRow(
                value = numberInput,
                onValueChange = { numberInput = it },
                label = stringResource(R.string.phone_number),
                button = stringResource(R.string.block).uppercase(),
                onAdd = {
                    val n = numberInput.trim()
                    if (n.any(Char::isDigit)) {
                        repo.blockNumber(n)
                        numberInput = ""
                        version++
                    }
                }
            )

            // ---- Spam reports ----
            SectionLabel(stringResource(R.string.blocking_reported_spam).uppercase())
            Text(
                text = stringResource(R.string.blocking_reported_note),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (reports.isEmpty()) EmptyNote(stringResource(R.string.blocking_none_reported))
            reports.forEach { report ->
                ListRow(
                    title = report.phoneNumber,
                    subtitle = stringResource(R.string.blocking_reported_when, DateUtils.getRelativeTimeSpanString(report.reportedAt)),
                    onRemove = {
                        // Removing a report also unblocks the number.
                        repo.unblockNumber(report.phoneNumber)
                        version++
                    }
                )
            }
            AddRow(
                value = reportInput,
                onValueChange = { reportInput = it },
                label = stringResource(R.string.blocking_report_hint),
                button = stringResource(R.string.report).uppercase(),
                onAdd = {
                    val n = reportInput.trim()
                    if (n.any(Char::isDigit)) {
                        repo.reportSpam(n)
                        reportInput = ""
                        version++
                        Toast.makeText(context, R.string.blocking_reported_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 14.dp))
    Text(
        text = text,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    onRemove: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.remove_item, title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    button: String,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        OutlinedButton(
            onClick = onAdd,
            enabled = value.any(Char::isDigit),
            border = BorderStroke(1.dp, if (value.any(Char::isDigit)) NothingRed else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(button, color = if (value.any(Char::isDigit)) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun redSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = NothingWhite,
    checkedTrackColor = NothingRed
)
