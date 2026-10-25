package com.example.allinonedailer.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.IconButton
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.allinonedailer.service.CallService
import com.example.allinonedailer.ui.contacts.ContactsScreen
import com.example.allinonedailer.ui.dialer.DialpadScreen
import com.example.allinonedailer.ui.history.CallLogScreen
import com.example.allinonedailer.ui.incall.InCallScreen
import com.example.allinonedailer.ui.settings.SettingsScreen
import com.example.allinonedailer.ui.theme.AllinoneDailerTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Sent by the Answer action on the incoming-call notification. */
        const val ACTION_ANSWER_CALL = "com.example.allinonedailer.action.ANSWER_CALL"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    private val isDefaultDialer = mutableStateOf(false)

    // The role request must be launched for a result from this activity;
    // starting it with FLAG_ACTIVITY_NEW_TASK makes Android cancel it.
    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        isDefaultDialer.value = checkDefaultDialer(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleCallAction(intent)
        requestRequiredPermissions()

        setContent {
            AllinoneDailerTheme {
                MainAppScreen(
                    isDefaultDialer = isDefaultDialer.value,
                    onRequestDefaultDialer = ::requestDefaultDialer
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallAction(intent)
    }

    override fun onResume() {
        super.onResume()
        isDefaultDialer.value = checkDefaultDialer(this)
    }

    private fun handleCallAction(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER_CALL) {
            CallService.answerCurrentCall()
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
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

enum class NavigationTab(val title: String, val icon: ImageVector) {
    RECENTS("Recents", Icons.Default.History),
    KEYPAD("Keypad", Icons.Default.Dialpad),
    CONTACTS("Contacts", Icons.Default.Contacts),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun MainAppScreen(
    isDefaultDialer: Boolean,
    onRequestDefaultDialer: () -> Unit
) {
    val activeCallData by CallService.callData.collectAsStateWithLifecycle()
    // The keypad is home; Recents, Contacts and Settings open over it and
    // the system back gesture returns to the keypad (no tab bar, as in the design).
    var selectedTab by remember { mutableStateOf(NavigationTab.KEYPAD) }
    BackHandler(enabled = selectedTab != NavigationTab.KEYPAD) {
        selectedTab = NavigationTab.KEYPAD
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
                    if (selectedTab == NavigationTab.KEYPAD) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = { selectedTab = NavigationTab.SETTINGS }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
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
                                        text = "Set as Default Dialer",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "Required for incoming-call screen, caller ID and call recording",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Button(onClick = onRequestDefaultDialer) {
                                    Text("Set Default")
                                }
                            }
                        }
                    }

                    // Content Screen Tab
                    when (selectedTab) {
                        NavigationTab.RECENTS -> CallLogScreen()
                        NavigationTab.KEYPAD -> DialpadScreen(
                            onOpenRecents = { selectedTab = NavigationTab.RECENTS },
                            onOpenContacts = { selectedTab = NavigationTab.CONTACTS }
                        )
                        NavigationTab.CONTACTS -> ContactsScreen(
                            onBack = { selectedTab = NavigationTab.KEYPAD },
                            onOpenSettings = { selectedTab = NavigationTab.SETTINGS }
                        )
                        NavigationTab.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }
}

private fun checkDefaultDialer(context: Context): Boolean {
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    return context.packageName == telecomManager.defaultDialerPackage
}
