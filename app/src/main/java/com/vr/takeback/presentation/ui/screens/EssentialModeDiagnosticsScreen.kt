package com.vr.takeback.presentation.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.window.Dialog
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.EssentialModeStatus
import com.vr.takeback.domain.security.ExemptionDiagnosticSummary
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EssentialModeDiagnosticsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val essentialRepo = remember { TakeBackApplication.essentialModeRepository }
    val exemptionResolver = remember { TakeBackApplication.essentialModeExemptionResolver }
    val strictRepo = remember { TakeBackApplication.strictModeRepository }
    val strictState by strictRepo.protectionState.collectAsState()

    val state by essentialRepo.state.collectAsState()
    var diagnosticSummary by remember { mutableStateOf<ExemptionDiagnosticSummary?>(null) }
    var showAddModuleDialog by remember { mutableStateOf(false) }
    var showSystemPackagesDialog by remember { mutableStateOf(false) }
    var customPackageInput by remember { mutableStateOf("") }
    var customPackageError by remember { mutableStateOf<String?>(null) }
    var systemAppSearchQuery by remember { mutableStateOf("") }

    var pendingMutation by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var showStrictChallenge by remember { mutableStateOf(false) }

    fun executeMutation(mutation: (String?) -> Unit) {
        if (strictState.isGlobalStrictActive) {
            pendingMutation = mutation
            showStrictChallenge = true
        } else {
            mutation(null)
        }
    }

    LaunchedEffect(state) {
        diagnosticSummary = exemptionResolver.getDiagnosticSummary(state.selectedAdditionalPackage)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Essential Mode Diagnostics",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Exemptions & Non-Interference Verification",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        val summary = diagnosticSummary
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Invariant / Non-Interference Guarantee Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Background Non-Interference Guarantee",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Essential Mode is strictly an application-access gate evaluated on foreground transitions. It NEVER force-stops background processes, cancels jobs/alarms, terminates services, drops notifications, or interrupts active media playback.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Status & Allowance",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val (badgeColor, textColor) = when (state.status) {
                                EssentialModeStatus.ACTIVE -> Pair(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                                EssentialModeStatus.EMERGENCY_EXIT_ACTIVE -> Pair(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                                EssentialModeStatus.MONITORING -> Pair(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                                else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = badgeColor
                            ) {
                                Text(
                                    text = state.status.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        val allowanceHours = state.dailyAllowanceMinutes / 60
                        val allowanceMinutes = state.dailyAllowanceMinutes % 60
                        Text(
                            text = "Daily Allowance: ${allowanceHours}h ${allowanceMinutes}m (${state.dailyAllowanceMinutes} mins)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.isEmergencyExitActive) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Emergency Exit is currently ACTIVE. Essential Mode restrictions are suspended until the next midnight daily cycle reset.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Core Essential Apps
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Core Essential Applications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Always exempt from Essential Mode. Emergency calling is never blocked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        summary?.coreApplications?.forEach { (appName, installed) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = appName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (appName == "WhatsApp") {
                                        Text(
                                            text = "Strictly com.whatsapp (w4b excluded)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = if (installed) "EXEMPT" else "NOT FOUND",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (installed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // User-Selected Additional App
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Selected Additional Application",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Package: ${state.selectedAdditionalPackage ?: "None configured"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Eligibility verified. Games, social media, and entertainment streaming are rejected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Samsung One Hand Operation+ & Good Lock
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Samsung Good Lock & OH+",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (state.status == EssentialModeStatus.ACTIVE) {
                                        Toast.makeText(context, "Cannot add exemptions while Essential Mode is active", Toast.LENGTH_SHORT).show()
                                    } else {
                                        customPackageError = null
                                        showAddModuleDialog = true
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Add Module")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "One Hand Operation+ gestures & background services are permanently exempt. All official and custom registered Good Lock modules remain fully operational.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // One Hand Operation+
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("One Hand Operation+", fontWeight = FontWeight.SemiBold)
                                Text("com.samsung.android.sidegesturepad", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("ALWAYS EXEMPT", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }

                        // Good Lock Hub
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Good Lock Hub", fontWeight = FontWeight.SemiBold)
                                Text("com.samsung.android.goodlock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val isHubInstalled = summary?.isGoodLockHubInstalled == true
                            Surface(shape = RoundedCornerShape(6.dp), color = if (isHubInstalled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                                Text(if (isHubInstalled) "EXEMPT" else "NOT INSTALLED", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isHubInstalled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // Detected Installed Modules
                        val detectedModules = summary?.detectedGoodLockModules ?: emptyList()
                        if (detectedModules.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Installed Good Lock Modules (${detectedModules.size}):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            detectedModules.forEach { mod ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mod.moduleName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text(mod.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text("EXEMPT", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }

                        // Custom Registered Modules
                        if (state.customGoodLockModules.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Custom Registered Modules (${state.customGoodLockModules.size}):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            state.customGoodLockModules.forEach { customPkg ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(customPkg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            if (state.status == EssentialModeStatus.ACTIVE) {
                                                Toast.makeText(context, "Cannot remove exemptions while Essential Mode is active", Toast.LENGTH_SHORT).show()
                                                return@IconButton
                                            }
                                            executeMutation { token ->
                                                val ok = essentialRepo.removeCustomGoodLockModule(customPkg, token)
                                                if (ok) {
                                                    exemptionResolver.synchronizeCustomGoodLockModules(essentialRepo.state.value.customGoodLockModules)
                                                    diagnosticSummary = exemptionResolver.getDiagnosticSummary(essentialRepo.state.value.selectedAdditionalPackage)
                                                    Toast.makeText(context, "Module removed", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Action failed or blocked by Strict Mode", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Android System Apps Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Android System Applications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "All system applications (FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP) are exempt to ensure phone stability and device integrity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("FLAG_SYSTEM packages exempt:", style = MaterialTheme.typography.bodySmall)
                            Text("${summary?.systemAppExemptionCount ?: 0}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("FLAG_UPDATED_SYSTEM_APP packages exempt:", style = MaterialTheme.typography.bodySmall)
                            Text("${summary?.updatedSystemAppExemptionCount ?: 0}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }

                        if (summary?.isPackageEnumerationSuccessful == false) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Package enumeration restricted by Android OS. TakeBack is using the verified fallback system package registry.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                systemAppSearchQuery = ""
                                showSystemPackagesDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View All System Packages (${summary?.systemAppPackagesList?.size ?: 0})")
                        }
                    }
                }
            }
        }

        // Dialog: Register Custom Good Lock Module
        if (showAddModuleDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddModuleDialog = false
                    customPackageInput = ""
                    customPackageError = null
                },
                title = { Text("Register Good Lock Module") },
                text = {
                    Column {
                        Text(
                            text = "Enter the exact package name of a Samsung Good Lock module installed on this device:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customPackageInput,
                            onValueChange = {
                                customPackageInput = it.trim()
                                customPackageError = null
                            },
                            label = { Text("Package name (e.g. com.samsung.android...)") },
                            singleLine = true,
                            isError = customPackageError != null,
                            supportingText = customPackageError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Must belong to Samsung namespace (com.samsung.* / com.sec.*) or official Good Lock registry. Games, social, and streaming apps are rejected.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val pkg = customPackageInput.trim()
                            if (pkg.isBlank()) {
                                customPackageError = "Package name cannot be blank"
                                return@Button
                            }
                            if (!exemptionResolver.isValidGoodLockCandidate(pkg)) {
                                customPackageError = "Package is ineligible (must be installed, belong to Samsung/Good Lock namespace, and not be games/social)"
                                return@Button
                            }
                            showAddModuleDialog = false
                            executeMutation { token ->
                                val ok = essentialRepo.addCustomGoodLockModule(pkg, token)
                                if (ok) {
                                    exemptionResolver.synchronizeCustomGoodLockModules(essentialRepo.state.value.customGoodLockModules)
                                    diagnosticSummary = exemptionResolver.getDiagnosticSummary(essentialRepo.state.value.selectedAdditionalPackage)
                                    Toast.makeText(context, "Module registered as exempt", Toast.LENGTH_SHORT).show()
                                    customPackageInput = ""
                                    customPackageError = null
                                } else {
                                    Toast.makeText(context, "Registration blocked by Strict Mode or invalid state", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddModuleDialog = false
                            customPackageInput = ""
                            customPackageError = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: View All System Packages
        if (showSystemPackagesDialog) {
            val allPackages = summary?.systemAppPackagesList ?: emptyList()
            val filteredPackages = allPackages.filter { it.contains(systemAppSearchQuery, ignoreCase = true) }

            Dialog(onDismissRequest = { showSystemPackagesDialog = false }) {
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
                        Text(
                            text = "Exempt System Packages",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${allPackages.size} system packages verified exempt to preserve device integrity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = systemAppSearchQuery,
                            onValueChange = { systemAppSearchQuery = it },
                            placeholder = { Text("Search system packages...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredPackages) { pkg ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "SYSTEM",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showSystemPackagesDialog = false }) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }

        if (showStrictChallenge) {
            StrictChallengeDialog(
                mechanism = strictState.mechanism,
                saltBase64 = strictState.saltBase64,
                expectedHash = strictState.passwordHash,
                mutationType = MutationType.UPDATE_ESSENTIAL_MODE,
                onTokenIssued = { token ->
                    showStrictChallenge = false
                    val action = pendingMutation
                    pendingMutation = null
                    action?.invoke(token)
                },
                onDismissRequest = {
                    showStrictChallenge = false
                    pendingMutation = null
                }
            )
        }
    }
}
