package com.example.allinonedailer.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.IconButton
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.allinonedailer.service.CallActionReceiver
import com.example.allinonedailer.service.CallPill
import com.example.allinonedailer.service.CallService
import com.example.allinonedailer.service.MissedCallNotifier
import com.example.allinonedailer.ui.contacts.ContactsScreen
import com.example.allinonedailer.ui.dialer.DialpadScreen
import com.example.allinonedailer.ui.history.CallLogScreen
import com.example.allinonedailer.ui.insights.InsightsScreen
import com.example.allinonedailer.ui.balance.BalanceScreen
import com.example.allinonedailer.ui.incall.InCallScreen
import com.example.allinonedailer.ui.notes.PostCallNoteCard
import com.example.allinonedailer.ui.recordings.RecordingsScreen
import com.example.allinonedailer.ui.settings.BackupScreen
import com.example.allinonedailer.ui.settings.BlockRulesScreen
import com.example.allinonedailer.ui.settings.SettingsScreen
import com.example.allinonedailer.ui.settings.QuietHoursScreen
import com.example.allinonedailer.ui.settings.RemindersScreen
import com.example.allinonedailer.ui.settings.SpeedDialScreen
import com.example.allinonedailer.ui.voicemail.VoicemailScreen
import com.example.allinonedailer.ui.theme.AllinoneDailerTheme
import com.example.allinonedailer.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.example.allinonedailer.data.AppLanguageSetting
import com.example.allinonedailer.data.AppTheme
import com.example.allinonedailer.data.CallPlacer

class MainActivity : ComponentActivity() {

    companion object {
        /** Sent by the Answer action on the incoming-call notification. */
        const val ACTION_ANSWER_CALL = "com.example.allinonedailer.action.ANSWER_CALL"
        /** Tap on a missed-call notification: open Recents. */
        const val ACTION_SHOW_MISSED_CALLS = "com.example.allinonedailer.action.SHOW_MISSED_CALLS"
        /** Missed-call notification actions; the number is the tel: data URI. */
        const val ACTION_MISSED_CALL_BACK = "com.example.allinonedailer.action.MISSED_CALL_BACK"
        const val ACTION_MISSED_CALL_MESSAGE = "com.example.allinonedailer.action.MISSED_CALL_MESSAGE"
        /** Intent.ACTION_CALL_EMERGENCY is hidden from the SDK. */
        private const val ACTION_CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    private val isDefaultDialer = mutableStateOf(false)

    // Bumped each time a missed-call notification asks for Recents.
    private val openRecentsRequest = mutableIntStateOf(0)

    // A number another app (or the system, for an emergency number) asked to
    // dial: shown on the keypad, never called by itself. Bumped per request.
    private val dialNumber = mutableStateOf<String?>(null)
    private val dialRequest = mutableIntStateOf(0)

    // The role request must be launched for a result from this activity;
    // starting it with FLAG_ACTIVITY_NEW_TASK makes Android cancel it.
    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        isDefaultDialer.value = checkDefaultDialer(this)
    }

    // Settings → Language before Android 13 (newer versions apply it system-wide).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageSetting.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Settings → Theme: system dialogs and the window follow the app, not the phone.
        AppTheme.applyNightMode(this)

        // Skip on recreation (e.g. rotation) so a Call back isn't dialled twice.
        if (savedInstanceState == null) handleCallAction(intent)
        requestRequiredPermissions()

        setContent {
            AllinoneDailerTheme {
                MainAppScreen(
                    isDefaultDialer = isDefaultDialer.value,
                    onRequestDefaultDialer = ::requestDefaultDialer,
                    openRecentsRequest = openRecentsRequest.intValue,
                    dialNumber = dialNumber.value,
                    dialRequest = dialRequest.intValue
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallAction(intent)
    }

    // The call pill stays out of the way while this screen shows the call.
    override fun onStart() {
        super.onStart()
        CallPill.appVisible = true
    }

    override fun onStop() {
        CallPill.appVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        isDefaultDialer.value = checkDefaultDialer(this)
    }

    private fun handleCallAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER_CALL -> CallService.answerCurrentCall()
            ACTION_SHOW_MISSED_CALLS -> openRecentsRequest.intValue++
            // DIAL / VIEW tel: from other apps, and CALL / CALL_EMERGENCY if one is
            // ever sent here (Telecom turns an emergency CALL into a DIAL to the
            // default dialer): show the number on the keypad, where the call
            // button places it through CallPlacer.
            Intent.ACTION_DIAL, Intent.ACTION_VIEW, Intent.ACTION_CALL, ACTION_CALL_EMERGENCY -> {
                val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
                if (!number.isNullOrBlank()) {
                    dialNumber.value = number
                    dialRequest.intValue++
                }
            }
            ACTION_MISSED_CALL_BACK, ACTION_MISSED_CALL_MESSAGE -> {
                val number = intent.data?.schemeSpecificPart ?: return
                val callId = intent.getLongExtra(CallActionReceiver.EXTRA_MISSED_CALL_ID, -1L)
                Thread {
                    if (callId >= 0) MissedCallNotifier.markRead(applicationContext, callId)
                    else MissedCallNotifier.clear(applicationContext)
                }.start()
                if (intent.action == ACTION_MISSED_CALL_BACK) {
                    if (CallPlacer.placeCall(this, number) == CallPlacer.Outcome.FAILED) {
                        Toast.makeText(this, R.string.no_app_can_handle, Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.no_app_can_handle, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestDefaultDialer() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) return
            roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            @Suppress("DEPRECATION")
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        }
        defaultDialerLauncher.launch(intent)
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

enum class NavigationTab(@StringRes val title: Int, val icon: ImageVector) {
    RECENTS(R.string.recents_title, Icons.Default.History),
    KEYPAD(R.string.keypad, Icons.Default.Dialpad),
    CONTACTS(R.string.contacts_title, Icons.Default.Contacts),
    SETTINGS(R.string.settings, Icons.Default.Settings),
    RECORDINGS(R.string.recordings_title, Icons.Default.History),
    BLOCK_RULES(R.string.blocking_title, Icons.Default.Settings),
    SPEED_DIAL(R.string.speed_dial_title, Icons.Default.Dialpad),
    VOICEMAIL(R.string.voicemail, Icons.Default.Settings),
    BACKUP(R.string.backup_title, Icons.Default.Settings),
    QUIET_HOURS(R.string.quiet_hours_title, Icons.Default.Settings),
    REMINDERS(R.string.reminders_title, Icons.Default.Settings),
    INSIGHTS(R.string.insights_title, Icons.Default.History),
    BALANCE(R.string.balance_title, Icons.Default.Settings)
}

@Composable
fun MainAppScreen(
    isDefaultDialer: Boolean,
    onRequestDefaultDialer: () -> Unit,
    openRecentsRequest: Int = 0,
    dialNumber: String? = null,
    dialRequest: Int = 0
) {
    val activeCallData by CallService.callData.collectAsStateWithLifecycle()
    // The keypad is home; Recents, Contacts and Settings open over it and
    // the system back gesture returns to the keypad (no tab bar, as in the design).
    var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.KEYPAD) }
    // A code Voicemail puts on the keypad; cleared once the keypad has taken it.
    var keypadPrefill by remember { mutableStateOf<String?>(null) }
    // Landscape keypad draws its own Settings button so the keys get the full height.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Insights / Balance return to where they were opened from (Recents, Settings or the keypad);
    // Recents opened from Insights for one contact searches that number and returns there.
    var subScreenReturn by remember { mutableStateOf(NavigationTab.SETTINGS) }
    var recentsQuery by remember { mutableStateOf<String?>(null) }
    var recentsReturn by remember { mutableStateOf(NavigationTab.KEYPAD) }
    BackHandler(enabled = selectedTab != NavigationTab.KEYPAD) {
        selectedTab = when (selectedTab) {
            NavigationTab.BLOCK_RULES, NavigationTab.SPEED_DIAL, NavigationTab.VOICEMAIL,
            NavigationTab.BACKUP, NavigationTab.QUIET_HOURS, NavigationTab.REMINDERS -> NavigationTab.SETTINGS
            NavigationTab.INSIGHTS, NavigationTab.BALANCE -> subScreenReturn
            NavigationTab.RECENTS -> recentsReturn.also {
                recentsQuery = null
                recentsReturn = NavigationTab.KEYPAD
            }
            else -> NavigationTab.KEYPAD
        }
    }
    LaunchedEffect(openRecentsRequest) {
        if (openRecentsRequest > 0) {
            recentsQuery = null
            recentsReturn = NavigationTab.KEYPAD
            selectedTab = NavigationTab.RECENTS
        }
    }
    LaunchedEffect(dialRequest) {
        if (dialRequest > 0 && dialNumber != null) {
            keypadPrefill = dialNumber
            selectedTab = NavigationTab.KEYPAD
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        // If an active call exists, overlay the InCallScreen
        if (activeCallData != null) {
            InCallScreen(callData = activeCallData!!)
        } else {
            run {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    if (selectedTab == NavigationTab.KEYPAD && !landscape) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = { selectedTab = NavigationTab.SETTINGS }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.settings),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Default Dialer Banner Notice
                    AnimatedVisibility(visible = !isDefaultDialer) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.default_dialer_title),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = stringResource(R.string.default_dialer_body),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Button(onClick = onRequestDefaultDialer) {
                                    Text(stringResource(R.string.default_dialer_button))
                                }
                            }
                        }
                    }

                    // Content Screen Tab
                    when (selectedTab) {
                        NavigationTab.RECENTS -> CallLogScreen(
                            initialQuery = recentsQuery,
                            onOpenInsights = {
                                subScreenReturn = NavigationTab.RECENTS
                                selectedTab = NavigationTab.INSIGHTS
                            }
                        )
                        NavigationTab.INSIGHTS -> CentredPane {
                            InsightsScreen(
                                onBack = { selectedTab = subScreenReturn },
                                onOpenContact = { key ->
                                    recentsQuery = key
                                    recentsReturn = NavigationTab.INSIGHTS
                                    selectedTab = NavigationTab.RECENTS
                                }
                            )
                        }
                        NavigationTab.BALANCE -> CentredPane {
                            BalanceScreen(
                                onBack = { selectedTab = subScreenReturn },
                                onDial = { code ->
                                    keypadPrefill = code
                                    selectedTab = NavigationTab.KEYPAD
                                }
                            )
                        }
                        NavigationTab.KEYPAD -> DialpadScreen(
                            onOpenRecents = { selectedTab = NavigationTab.RECENTS },
                            onOpenContacts = { selectedTab = NavigationTab.CONTACTS },
                            onOpenSettings = { selectedTab = NavigationTab.SETTINGS },
                            onOpenBalance = {
                                subScreenReturn = NavigationTab.KEYPAD
                                selectedTab = NavigationTab.BALANCE
                            },
                            prefillNumber = keypadPrefill,
                            onPrefillConsumed = { keypadPrefill = null }
                        )
                        NavigationTab.CONTACTS -> ContactsScreen(
                            onBack = { selectedTab = NavigationTab.KEYPAD },
                            onOpenSettings = { selectedTab = NavigationTab.SETTINGS }
                        )
                        NavigationTab.SETTINGS -> CentredPane {
                            SettingsScreen(
                                onOpenRecordings = { selectedTab = NavigationTab.RECORDINGS },
                                onOpenBlockRules = { selectedTab = NavigationTab.BLOCK_RULES },
                                onOpenSpeedDial = { selectedTab = NavigationTab.SPEED_DIAL },
                                onOpenVoicemail = { selectedTab = NavigationTab.VOICEMAIL },
                                onOpenBackup = { selectedTab = NavigationTab.BACKUP },
                                onOpenQuietHours = { selectedTab = NavigationTab.QUIET_HOURS },
                                onOpenReminders = { selectedTab = NavigationTab.REMINDERS },
                                onOpenInsights = {
                                    subScreenReturn = NavigationTab.SETTINGS
                                    selectedTab = NavigationTab.INSIGHTS
                                },
                                onOpenBalance = {
                                    subScreenReturn = NavigationTab.SETTINGS
                                    selectedTab = NavigationTab.BALANCE
                                }
                            )
                        }
                        NavigationTab.QUIET_HOURS -> CentredPane {
                            QuietHoursScreen(onBack = { selectedTab = NavigationTab.SETTINGS })
                        }
                        NavigationTab.REMINDERS -> CentredPane {
                            RemindersScreen(onBack = { selectedTab = NavigationTab.SETTINGS })
                        }
                        NavigationTab.BACKUP -> CentredPane {
                            BackupScreen(onBack = { selectedTab = NavigationTab.SETTINGS })
                        }
                        NavigationTab.SPEED_DIAL -> CentredPane {
                            SpeedDialScreen(onBack = { selectedTab = NavigationTab.SETTINGS })
                        }
                        NavigationTab.BLOCK_RULES -> CentredPane {
                            BlockRulesScreen(onBack = { selectedTab = NavigationTab.SETTINGS })
                        }
                        NavigationTab.RECORDINGS -> RecordingsScreen(
                            onBack = { selectedTab = NavigationTab.SETTINGS }
                        )
                        NavigationTab.VOICEMAIL -> CentredPane {
                            VoicemailScreen(
                                onBack = { selectedTab = NavigationTab.SETTINGS },
                                onOpenKeypad = { code ->
                                    keypadPrefill = code
                                    selectedTab = NavigationTab.KEYPAD
                                }
                            )
                        }
                    }
                }
                // "Add a note" for a few seconds after a connected call ends.
                PostCallNoteCard()
            }
        }
    }
}

/** Single-column screens keep a readable width on a sideways or wide screen. */
@Composable
private fun CentredPane(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
        ) { content() }
    }
}

private fun checkDefaultDialer(context: Context): Boolean {
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    return context.packageName == telecomManager.defaultDialerPackage
}
