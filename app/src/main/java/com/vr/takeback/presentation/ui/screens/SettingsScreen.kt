package com.vr.takeback.presentation.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.StrictModeRepository
import com.vr.takeback.data.repository.StrictProtectionStatus
import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.platform.receiver.TakeBackDeviceAdminReceiver
import com.vr.takeback.platform.util.OemBatteryOptimizer
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    permissionRepository: PermissionRepository,
    strictModeRepository: StrictModeRepository,
    onNavigateToPermissions: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val permissionStatus by permissionRepository.permissionStatus.collectAsState()
    val strictState by strictModeRepository.protectionState.collectAsState()

    var showArmDialog by remember { mutableStateOf(false) }
    var showAdminRequiredDialog by remember { mutableStateOf(false) }
    var showDisarmChallenge by remember { mutableStateOf(false) }
    var selectedMechanism by remember { mutableStateOf(StrictMechanism.TEXT_CHALLENGE) }
    var pinToSet by remember { mutableStateOf("") }

    // Recheck actual DevicePolicyManager administrator status upon resume
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        strictModeRepository.refreshAdminStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Protection",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Health Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (permissionStatus.isCoreProtected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = if (permissionStatus.isCoreProtected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Permission Center",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (permissionStatus.isCoreProtected) "All core permissions active" else "Action required for blocking",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(onClick = onNavigateToPermissions) {
                            Text("Review")
                        }
                    }
                }
            }

            // Strict Mode Card (Mandatory Device Administrator Prerequisite)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Strict Mode",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Mandatory Device Administrator protection",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = strictState.isGlobalStrictActive,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        val adminActive = strictModeRepository.isDeviceAdminActive()
                                        if (!adminActive) {
                                            showAdminRequiredDialog = true
                                        } else {
                                            showArmDialog = true
                                        }
                                    } else {
                                        if (strictState.isCurrentlyLocked) {
                                            showDisarmChallenge = true
                                        } else {
                                            strictModeRepository.disarmStrict()
                                        }
                                    }
                                }
                            )
                        }

                        // Protection Compromised Warning Banner if Device Admin was deactivated
                        if (strictState.status == StrictProtectionStatus.COMPROMISED) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Protection Compromised",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Android Device Administrator was deactivated. Strict Mode protection is degraded. Reactivate Device Administrator to restore full anti-tamper security.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            val adminIntent = TakeBackDeviceAdminReceiver.createAddDeviceAdminIntent(context)
                                            context.startActivity(adminIntent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Reactivate Device Administrator")
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (strictState.isGlobalStrictActive) {
                                "Strict Mode is currently ARMED (${strictState.mechanism.name}) with Standard Device Administrator. All profile modifications, schedule edits, and settings access require challenge verification."
                            } else {
                                "Requires Standard Device Administrator. When armed, restriction rules, schedules, and active profiles cannot be edited or paused without completing verification. Settings tampering and uninstallation are deterred."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (strictState.isGlobalStrictActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (strictState.isGlobalStrictActive) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Usage Tracking & Diagnostics Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Usage Diagnostics",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Compare OS raw stats vs reconstructed screen time",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalButton(onClick = onNavigateToDiagnostics) {
                                Text("Inspect")
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Examine raw Android UsageStatsManager queries, interval file rotation, active filters, and exact session boundaries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // OEM Background Reliability Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "OEM Background Reliability",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Detected Brand: ${OemBatteryOptimizer.manufacturer}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = OemBatteryOptimizer.getDeviceAdvice(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { OemBatteryOptimizer.openAutoStartSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open OEM Optimization Settings")
                        }
                    }
                }
            }

            // About & Version
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "TakeBack v1.0 • Open Source",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Original, privacy-first distraction control engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Dialog: Device Administrator Mandatory Activation
        if (showAdminRequiredDialog) {
            AlertDialog(
                onDismissRequest = { showAdminRequiredDialog = false },
                icon = { Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Device Administrator Required") },
                text = {
                    Text(
                        "Standard Android Device Administrator is a mandatory prerequisite for enabling Strict Mode.\n\n" +
                        "This grants TakeBack official presence in 'Device admin apps' to deter unauthorized settings tampering and casual uninstallation.\n\n" +
                        "TakeBack uses Standard Device Administrator only. It never wipes device data or performs factory resets."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAdminRequiredDialog = false
                            val adminIntent = TakeBackDeviceAdminReceiver.createAddDeviceAdminIntent(context)
                            context.startActivity(adminIntent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Activate Device Admin")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAdminRequiredDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Arm Strict Mode
        if (showArmDialog) {
            AlertDialog(
                onDismissRequest = { showArmDialog = false },
                icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Arm Strict Mode?") },
                text = {
                    Column {
                        Text("While Strict Mode is armed, you cannot pause profiles, delete restrictions, or edit schedules without solving a challenge. Choose protection mechanism:")
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedMechanism == StrictMechanism.TEXT_CHALLENGE,
                                onClick = { selectedMechanism = StrictMechanism.TEXT_CHALLENGE }
                            )
                            Text("Random Text Challenge")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedMechanism == StrictMechanism.PIN,
                                onClick = { selectedMechanism = StrictMechanism.PIN }
                            )
                            Text("Secure PIN (4-6 digits)")
                        }
                        if (selectedMechanism == StrictMechanism.PIN) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pinToSet,
                                onValueChange = { if (it.length <= 6) pinToSet = it },
                                label = { Text("Enter 4-6 digit PIN") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (!strictModeRepository.isDeviceAdminActive()) {
                                showArmDialog = false
                                showAdminRequiredDialog = true
                                return@Button
                            }

                            if (selectedMechanism == StrictMechanism.PIN) {
                                if (pinToSet.length >= 4) {
                                    strictModeRepository.armStrictWithPin(pinToSet)
                                    pinToSet = ""
                                    showArmDialog = false
                                }
                            } else {
                                strictModeRepository.armStrictWithTextChallenge()
                                showArmDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Confirm & Arm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showArmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Disarm Strict Mode Challenge
        if (showDisarmChallenge) {
            StrictChallengeDialog(
                mechanism = strictState.mechanism,
                saltBase64 = strictState.saltBase64,
                expectedHash = strictState.passwordHash,
                mutationType = MutationType.DISARM_STRICT_MODE,
                onTokenIssued = { token ->
                    strictModeRepository.disarmStrict(token)
                    showDisarmChallenge = false
                },
                onDismissRequest = { showDisarmChallenge = false }
            )
        }
    }
}
