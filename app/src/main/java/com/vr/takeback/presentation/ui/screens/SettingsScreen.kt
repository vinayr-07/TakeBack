package com.vr.takeback.presentation.ui.screens

import android.app.Activity
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.StrictModeRepository
import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.platform.service.LocalDnsVpnService
import com.vr.takeback.platform.util.OemBatteryOptimizer
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    permissionRepository: PermissionRepository,
    strictModeRepository: StrictModeRepository,
    onNavigateToPermissions: () -> Unit
) {
    val context = LocalContext.current
    val permissionStatus by permissionRepository.permissionStatus.collectAsState()
    val strictState by strictModeRepository.protectionState.collectAsState()

    var showArmDialog by remember { mutableStateOf(false) }
    var showDisarmChallenge by remember { mutableStateOf(false) }
    var selectedMechanism by remember { mutableStateOf(StrictMechanism.TEXT_CHALLENGE) }
    var pinToSet by remember { mutableStateOf("") }
    var isDnsShieldActive by remember {
        mutableStateOf(LocalDnsVpnService.isFilterRunning || LocalDnsVpnService.isPersistedEnabled(context))
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            LocalDnsVpnService.start(context)
            isDnsShieldActive = true
        }
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

            // Strict Mode Card
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
                                        text = "Prevent impulsive modifications",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = strictState.isGlobalStrictActive,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        showArmDialog = true
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

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (strictState.isGlobalStrictActive) {
                                "Strict Mode is currently ARMED (${strictState.mechanism.name}). All profile pausing, editing, and rule modifications require verification."
                            } else {
                                "When armed, restriction rules, schedules, and active profiles cannot be edited or paused without completing a 60-character complex text challenge or PIN."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (strictState.isGlobalStrictActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (strictState.isGlobalStrictActive) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Web Shield (Local DNS VPN Sinkhole) Card
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
                                Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Web Shield (Local DNS)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Local loopback domain filter",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isDnsShieldActive,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val vpnIntent = VpnService.prepare(context)
                                        if (vpnIntent != null) {
                                            vpnPrepareLauncher.launch(vpnIntent)
                                        } else {
                                            LocalDnsVpnService.start(context)
                                            isDnsShieldActive = true
                                        }
                                    } else {
                                        LocalDnsVpnService.stop(context)
                                        isDnsShieldActive = false
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Blocks distracting websites and adult content at the DNS level. Runs 100% locally on your device without routing through external proxy servers.",
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
                        Button(
                            onClick = { OemBatteryOptimizer.launchOptimizationSettings(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Configure Battery Settings")
                        }
                    }
                }
            }

            // 100% Free & Open Source Forever Card (Zero Paywalls)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Code, contentDescription = null, tint = Color(0xFF10B981))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "100% Free & Open Source",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TakeBack is completely free and open source. All features — including unlimited profiles, schedules, ambient audio synthesis, Strict Mode, and offline data export — are permanently unlocked for everyone. No paywalls, no ads, zero tracking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Privacy & Local Storage
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "100% Local-First Data",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "TakeBack never sends your app usage, visited websites, or settings to any remote server. Zero tracking, zero telemetry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { /* Export SAF */ }) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export JSON")
                            }
                            OutlinedButton(
                                onClick = { /* Wipe DB */ },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Wipe All Data")
                            }
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

        if (showDisarmChallenge) {
            StrictChallengeDialog(
                mechanism = strictState.mechanism,
                saltBase64 = strictState.saltBase64,
                expectedHash = strictState.passwordHash,
                onChallengeSolved = {
                    strictModeRepository.disarmStrict()
                    showDisarmChallenge = false
                },
                onDismissRequest = { showDisarmChallenge = false }
            )
        }
    }
}
