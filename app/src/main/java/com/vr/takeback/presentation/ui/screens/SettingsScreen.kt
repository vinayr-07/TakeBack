package com.vr.takeback.presentation.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.AppDiscoveryRepository
import com.vr.takeback.data.repository.EssentialModeStatus
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.StrictModeRepository
import com.vr.takeback.data.repository.StrictProtectionStatus
import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.domain.model.TargetApp
import com.vr.takeback.domain.security.EssentialModeExemptionResolver
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.platform.receiver.TakeBackDeviceAdminReceiver
import com.vr.takeback.platform.util.OemBatteryOptimizer
import com.vr.takeback.presentation.ui.components.EssentialModeExitDialog
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    permissionRepository: PermissionRepository,
    strictModeRepository: StrictModeRepository,
    onNavigateToPermissions: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToEssentialDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val permissionStatus by permissionRepository.permissionStatus.collectAsState()
    val strictState by strictModeRepository.protectionState.collectAsState()

    val essentialRepo = remember { TakeBackApplication.essentialModeRepository }
    val exemptionResolver = remember { TakeBackApplication.essentialModeExemptionResolver }
    val essentialState by essentialRepo.state.collectAsState()

    var showArmDialog by remember { mutableStateOf(false) }
    var showAdminRequiredDialog by remember { mutableStateOf(false) }
    var showDisarmChallenge by remember { mutableStateOf(false) }
    var selectedMechanism by remember { mutableStateOf(StrictMechanism.TEXT_CHALLENGE) }
    var pinToSet by remember { mutableStateOf("") }

    // Essential Mode state & dialogs
    var showAllowanceDialog by remember { mutableStateOf(false) }
    var allowanceHoursInput by remember { mutableStateOf("") }
    var allowanceMinutesInput by remember { mutableStateOf("") }

    var showAdditionalAppDialog by remember { mutableStateOf(false) }
    var additionalAppInput by remember { mutableStateOf("") }
    var additionalAppError by remember { mutableStateOf<String?>(null) }

    var showPinRequiredDialog by remember { mutableStateOf(false) }
    var pendingEnableEssentialAfterPin by remember { mutableStateOf(false) }
    var showSettingsEmergencyExitPinDialog by remember { mutableStateOf(false) }

    var pendingEssentialMutation by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var showEssentialChallenge by remember { mutableStateOf(false) }

    fun executeEssentialMutation(mutation: (String?) -> Unit) {
        if (strictState.isCurrentlyLocked) {
            pendingEssentialMutation = mutation
            showEssentialChallenge = true
        } else {
            mutation(null)
        }
    }

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
                        if (strictState.isCompromised) {
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

            // Essential Mode Card
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
                                Icon(
                                    imageVector = Icons.Filled.HourglassBottom,
                                    contentDescription = null,
                                    tint = if (essentialState.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Essential Mode",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Daily limit & emergency allowlist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = essentialState.isEnabled && !essentialState.isEmergencyExitActive,
                                onCheckedChange = { targetState ->
                                    if (targetState) {
                                        if (!essentialRepo.hasConfiguredPin()) {
                                            pendingEnableEssentialAfterPin = true
                                            showPinRequiredDialog = true
                                            return@Switch
                                        }
                                    } else {
                                        if (essentialState.status == EssentialModeStatus.ACTIVE) {
                                            showSettingsEmergencyExitPinDialog = true
                                            return@Switch
                                        }
                                    }
                                    executeEssentialMutation { token ->
                                        val success = essentialRepo.setEnabled(targetState, token)
                                        if (!success) {
                                            Toast.makeText(context, "Action blocked by Strict Mode or invalid state", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }

                        if (essentialState.isEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // Status Indicator
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Current State", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (essentialState.status) {
                                        EssentialModeStatus.ACTIVE -> MaterialTheme.colorScheme.errorContainer
                                        EssentialModeStatus.EMERGENCY_EXIT_ACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
                                        EssentialModeStatus.MONITORING -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = essentialState.status.name,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when (essentialState.status) {
                                            EssentialModeStatus.ACTIVE -> MaterialTheme.colorScheme.onErrorContainer
                                            EssentialModeStatus.EMERGENCY_EXIT_ACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
                                            EssentialModeStatus.MONITORING -> MaterialTheme.colorScheme.onPrimaryContainer
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            if (essentialState.isEnabled && !essentialState.isEmergencyExitActive) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        showSettingsEmergencyExitPinDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Exit Essential Mode (Password)", fontWeight = FontWeight.Bold)
                                }
                            } else if (essentialState.isEnabled && essentialState.isEmergencyExitActive) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Paused for today via Emergency Exit. Automatically resumes tomorrow at midnight, or toggle ON above to resume now.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = {
                                        executeEssentialMutation { token ->
                                            val success = essentialRepo.setEnabled(false, token)
                                            if (!success) {
                                                Toast.makeText(context, "Action blocked by Strict Mode or invalid state", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                ) {
                                    Text("Turn Off Auto-Resume")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Today's Measured Usage
                            val usageSnapshot = remember(essentialState) {
                                runCatching { TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot() }.getOrNull()
                            }
                            val deviceScreenTimeMillis = usageSnapshot?.deviceScreenTimeMillis
                            val isScreenTimeAvailable = usageSnapshot?.isDeviceScreenTimeAvailable == true && deviceScreenTimeMillis != null
                            val measuredMinutes = if (isScreenTimeAvailable) {
                                ((deviceScreenTimeMillis ?: 0L) / 60000L).toInt()
                            } else {
                                ((usageSnapshot?.foregroundUsageByPackage?.values?.sumOf { it } ?: 0L) / 60000L).toInt()
                            }
                            val measuredH = measuredMinutes / 60
                            val measuredM = measuredMinutes % 60

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Today's Measured Screen Time", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${measuredH}h ${measuredM}m" + if (isScreenTimeAvailable) " (Device Interactive)" else " (App Sum)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (measuredMinutes >= essentialState.dailyAllowanceMinutes) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Daily Allowance
                            val hours = essentialState.dailyAllowanceMinutes / 60
                            val mins = essentialState.dailyAllowanceMinutes % 60
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Daily Usage Allowance", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text("${hours}h ${mins}m (${essentialState.dailyAllowanceMinutes} mins)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    onClick = {
                                        if (essentialState.status == EssentialModeStatus.ACTIVE) {
                                            Toast.makeText(context, "Cannot modify allowance while Essential Mode is active", Toast.LENGTH_SHORT).show()
                                        } else {
                                            allowanceHoursInput = hours.toString()
                                            allowanceMinutesInput = mins.toString()
                                            showAllowanceDialog = true
                                        }
                                    }
                                ) {
                                    Text("Change")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Additional Allowed Applications (up to 2)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Allowed Apps (${essentialState.selectedAdditionalPackages.size}/${EssentialModeExemptionResolver.MAX_ADDITIONAL_PACKAGES} User Selected)",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = essentialState.selectedAdditionalPackages
                                            .takeIf { it.isNotEmpty() }
                                            ?.joinToString(", ")
                                            ?: "None configured (AI chatbots auto-exempt)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        if (essentialState.status == EssentialModeStatus.ACTIVE) {
                                            Toast.makeText(context, "Cannot change allowed apps while Essential Mode is active", Toast.LENGTH_SHORT).show()
                                        } else {
                                            additionalAppInput = essentialState.selectedAdditionalPackages.firstOrNull() ?: ""
                                            additionalAppError = null
                                            showAdditionalAppDialog = true
                                        }
                                    }
                                ) {
                                    Text("Select")
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedButton(
                                onClick = onNavigateToEssentialDiagnostics,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Exemptions & Diagnostics")
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Restricts non-essential phone access automatically once your total daily screen time exceeds the configured threshold. Dialer, SMS, Camera, WhatsApp, Payments, AI chatbots (ChatGPT, Gemini, Claude, etc.), Good Lock, and up to 2 selected apps remain available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                            Text("Password / PIN (min 4 characters)")
                        }
                        if (selectedMechanism == StrictMechanism.PIN) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pinToSet,
                                onValueChange = { if (it.length <= 32) pinToSet = it },
                                label = { Text("Enter password or PIN (4+ chars)") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                                    val enteredPin = pinToSet
                                    val armed = strictModeRepository.armStrictWithPin(enteredPin)
                                    pinToSet = ""
                                    showArmDialog = false
                                    if (armed && pendingEnableEssentialAfterPin) {
                                        pendingEnableEssentialAfterPin = false
                                        val token = strictModeRepository.verifyAndIssueToken(
                                            input = enteredPin,
                                            mutationType = MutationType.UPDATE_ESSENTIAL_MODE
                                        )
                                        essentialRepo.setEnabled(true, token)
                                    }
                                }
                            } else {
                                pendingEnableEssentialAfterPin = false
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
                    TextButton(onClick = {
                        pendingEnableEssentialAfterPin = false
                        showArmDialog = false
                    }) {
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

        // Dialog: Edit Daily Allowance
        if (showAllowanceDialog) {
            AlertDialog(
                onDismissRequest = { showAllowanceDialog = false },
                title = { Text("Configure Daily Allowance") },
                text = {
                    Column {
                        if (strictState.isCurrentlyLocked) {
                            Text(
                                text = "Strict Mode active: You can decrease your allowance to tighten focus. Increasing allowance is locked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Text(
                            text = "Set total daily phone screen time limit before Essential Mode restricts distracting apps:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = allowanceHoursInput,
                                onValueChange = { allowanceHoursInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Hours") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = allowanceMinutesInput,
                                onValueChange = { allowanceMinutesInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Minutes") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val h = allowanceHoursInput.toIntOrNull() ?: 0
                            val m = allowanceMinutesInput.toIntOrNull() ?: 0
                            val totalMins = (h * 60) + m
                            if (totalMins in 15..1440) {
                                val currentAllowance = essentialState.dailyAllowanceMinutes
                                val isStrict = strictState.isCurrentlyLocked
                                if (isStrict && totalMins > currentAllowance) {
                                    Toast.makeText(context, "Strict Mode is active: You cannot increase the daily allowance ($totalMins > $currentAllowance mins).", Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                showAllowanceDialog = false
                                executeEssentialMutation { token ->
                                    val ok = essentialRepo.setDailyAllowanceMinutes(totalMins, token)
                                    if (ok) {
                                        Toast.makeText(context, "Allowance updated to ${h}h ${m}m", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Update failed: blocked by Strict Mode or invalid state", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "Allowance must be between 15 mins and 24 hours", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAllowanceDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Select Up to 2 Additional Applications (Installed App Picker)
        if (showAdditionalAppDialog) {
            val appDiscoveryRepo = remember { AppDiscoveryRepository(context) }
            var installedApps by remember { mutableStateOf<List<TargetApp>>(emptyList()) }
            var searchQuery by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(true) }
            var draftSelection by remember(essentialState.selectedAdditionalPackages) {
                mutableStateOf(essentialState.selectedAdditionalPackages)
            }

            LaunchedEffect(Unit) {
                installedApps = appDiscoveryRepo.getInstalledApplications()
                isLoading = false
            }

            val filteredApps = installedApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }

            Dialog(onDismissRequest = { showAdditionalAppDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Select Allowed Apps",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${draftSelection.size}/${EssentialModeExemptionResolver.MAX_ADDITIONAL_PACKAGES}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Select up to 2 additional productivity apps to allow during Essential Mode. AI chatbots, payments, phone, SMS, camera & WhatsApp are already exempt by default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search installed applications...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    val eligibility = exemptionResolver.evaluateAdditionalAppEligibility(app.packageName)
                                    val isEligible = eligibility.isEligible
                                    val isSelected = draftSelection.contains(app.packageName)

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = isEligible || isSelected) {
                                                if (isSelected) {
                                                    draftSelection = draftSelection.filter { it != app.packageName }
                                                } else if (draftSelection.size < EssentialModeExemptionResolver.MAX_ADDITIONAL_PACKAGES) {
                                                    draftSelection = draftSelection + app.packageName
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        "Maximum ${EssentialModeExemptionResolver.MAX_ADDITIONAL_PACKAGES} additional apps allowed",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = app.appName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isEligible || isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                                Text(
                                                    text = app.packageName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isEligible || isSelected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                )
                                                Text(
                                                    text = eligibility.reason,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    color = if (isEligible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (isEligible) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                                ) {
                                                    Text(
                                                        text = if (isEligible) "ELIGIBLE" else "INELIGIBLE",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isEligible) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { draftSelection = emptyList() }) {
                                Text("Clear All")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showAdditionalAppDialog = false }) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        val finalSelection = draftSelection
                                        showAdditionalAppDialog = false
                                        executeEssentialMutation { token ->
                                            val ok = essentialRepo.setSelectedAdditionalPackages(finalSelection, token)
                                            if (ok) {
                                                Toast.makeText(
                                                    context,
                                                    "Saved ${finalSelection.size} allowed app(s)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Update failed: blocked by Strict Mode or ineligible app",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                ) {
                                    Text("Save (${draftSelection.size}/2)")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dialog: Strict Mode PIN Required to Enable Essential Mode
        if (showPinRequiredDialog) {
            AlertDialog(
                onDismissRequest = { showPinRequiredDialog = false },
                icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Strict Mode PIN Required") },
                text = {
                    Text(
                        "Essential Mode requires a configured Strict Mode PIN before it can be enabled.\n\n" +
                        "This ensures you have a secure credential for Emergency Exit if your daily usage reaches the allowance limit."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPinRequiredDialog = false
                            selectedMechanism = StrictMechanism.PIN
                            showArmDialog = true
                        }
                    ) {
                        Text("Configure PIN")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingEnableEssentialAfterPin = false
                        showPinRequiredDialog = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Exit Essential Mode from Settings (Password-only)
        if (showSettingsEmergencyExitPinDialog) {
            EssentialModeExitDialog(
                essentialRepository = essentialRepo,
                onExited = {
                    showSettingsEmergencyExitPinDialog = false
                    Toast.makeText(context, "Essential Mode exited until midnight reset", Toast.LENGTH_SHORT).show()
                },
                onDismissRequest = { showSettingsEmergencyExitPinDialog = false }
            )
        }

        // Dialog: Strict Challenge for Essential Mode Mutation
        if (showEssentialChallenge) {
            StrictChallengeDialog(
                mechanism = strictState.mechanism,
                saltBase64 = strictState.saltBase64,
                expectedHash = strictState.passwordHash,
                mutationType = MutationType.UPDATE_ESSENTIAL_MODE,
                onTokenIssued = { token ->
                    showEssentialChallenge = false
                    val action = pendingEssentialMutation
                    pendingEssentialMutation = null
                    action?.invoke(token)
                },
                onDismissRequest = {
                    showEssentialChallenge = false
                    pendingEssentialMutation = null
                }
            )
        }
    }
}
