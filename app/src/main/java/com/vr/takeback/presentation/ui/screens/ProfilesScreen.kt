package com.vr.takeback.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
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
import android.widget.Toast
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.data.repository.StrictModeRepository
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.model.Profile
import com.vr.takeback.domain.model.Schedule
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.presentation.ui.components.AppPickerDialog
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    profileRepository: ProfileRepository,
    strictModeRepository: StrictModeRepository,
    permissionRepository: PermissionRepository
) {
    val context = LocalContext.current
    val profiles by profileRepository.profiles.collectAsState()
    val strictState by strictModeRepository.protectionState.collectAsState()
    val permissionStatus by permissionRepository.permissionStatus.collectAsState()
    val isStrict = strictState.isCurrentlyLocked

    var showCreateDialog by remember { mutableStateOf(false) }
    var showAccessibilityRequiredDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var editingProfileForApps by remember { mutableStateOf<Profile?>(null) }
    var editingProfileForSchedule by remember { mutableStateOf<Profile?>(null) }
    var pendingStrictAction by remember { mutableStateOf<((token: String?) -> Unit)?>(null) }
    var pendingMutationType by remember { mutableStateOf(MutationType.SAVE_PROFILE) }
    var pendingTargetId by remember { mutableStateOf<String?>(null) }

    val engine = remember { CentralRestrictionEngine() }
    val nowTime = LocalTime.now()
    val today = LocalDate.now().dayOfWeek

    fun executeWithStrictCheck(
        mutationType: MutationType = MutationType.SAVE_PROFILE,
        targetId: String? = null,
        action: (token: String?) -> Unit
    ) {
        if (strictState.isCurrentlyLocked) {
            pendingMutationType = mutationType
            pendingTargetId = targetId
            pendingStrictAction = action
        } else {
            action(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Restriction Profiles",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New Profile") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
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
            // Persistent Accessibility Warning Banner
            if (!permissionStatus.hasAccessibility) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Accessibility Permission Missing",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "Profiles will not block apps until granted",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "TakeBack needs Accessibility permission to detect when restricted apps are launched and show the block shield. Turning on profiles will not block apps without this permission.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(permissionRepository.getAccessibilityIntent())
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Grant Permission in Settings")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Profiles group your restricted apps, schedules, and daily limits. Turn on a profile to enforce its restrictions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(profiles.size) { index ->
                val profile = profiles[index]
                val isEnforcingNow = profile.isEnabled && engine.isProfileCurrentlyEnforcing(profile, nowTime, today)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        // Profile Header with Color Icon, Name, and Master Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(
                                            try {
                                                Color(android.graphics.Color.parseColor(profile.colorHex)).copy(alpha = 0.2f)
                                            } catch (e: Exception) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = try {
                                            Color(android.graphics.Color.parseColor(profile.colorHex))
                                        } catch (e: Exception) {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            isEnforcingNow -> "● Actively Enforcing Now"
                                            profile.isEnabled -> "○ Enabled (Standby / Scheduled)"
                                            else -> "○ Inactive (Turn on to block)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isEnforcingNow) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isEnforcingNow -> Color(0xFF10B981)
                                            profile.isEnabled -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            Switch(
                                checked = profile.isEnabled,
                                onCheckedChange = { isChecked ->
                                    if (isChecked && !permissionStatus.hasAccessibility) {
                                        showAccessibilityRequiredDialog = true
                                    } else if (!isChecked && isStrict) {
                                        Toast.makeText(context, "Strict Mode is active: Active profiles cannot be disabled.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        profileRepository.toggleProfile(profile.id)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Restricted Apps Header & Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Restricted Apps (${profile.targetApps.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(
                                onClick = {
                                    editingProfileForApps = profile
                                }
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Apps")
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (profile.targetApps.isEmpty()) {
                            Text(
                                text = "No apps restricted in this profile yet. Tap '+ Add Apps' to select apps to block.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                profile.targetApps.forEach { app ->
                                    InputChip(
                                        selected = true,
                                        onClick = { /* Chip clicked */ },
                                        label = { Text(app.appName, fontSize = 12.sp) },
                                        trailingIcon = {
                                            if (!isStrict) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = "Remove app",
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clickable {
                                                            val updated = profile.targetApps.filter { it.packageName != app.packageName }
                                                            profileRepository.saveProfile(profile.copy(targetApps = updated))
                                                        }
                                                )
                                            }
                                        },
                                        colors = InputChipDefaults.inputChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))

                        // Schedule Timing Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (profile.schedules.isNotEmpty()) {
                                    val schedule = profile.schedules.first()
                                    val startH = schedule.startTimeSeconds / 3600
                                    val startM = (schedule.startTimeSeconds % 3600) / 60
                                    val endH = schedule.endTimeSeconds / 3600
                                    val endM = (schedule.endTimeSeconds % 3600) / 60
                                    Column {
                                        Text(
                                            text = String.format("%02d:%02d - %02d:%02d", startH, startM, endH, endM),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = formatDaysBitmask(schedule.daysOfWeekMask),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Always Active when turned on (No Schedule)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            FilledTonalButton(
                                onClick = {
                                    editingProfileForSchedule = profile
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(if (profile.schedules.isNotEmpty()) "Edit Schedule" else "+ Set Schedule", fontSize = 12.sp)
                            }
                        }

                        // Bottom Actions: Duplicate, Delete
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = {
                                executeWithStrictCheck(MutationType.DUPLICATE_PROFILE, profile.id) { token ->
                                    profileRepository.duplicateProfile(profile.id, token = token)
                                }
                            }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate Profile", modifier = Modifier.size(18.dp))
                            }
                            if (!profile.isPreset) {
                                IconButton(onClick = {
                                    executeWithStrictCheck(MutationType.DELETE_PROFILE, profile.id) { token ->
                                        profileRepository.deleteProfile(profile.id, token = token)
                                    }
                                }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete Profile", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // New Profile Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Create New Profile") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            label = { Text("Profile Name") },
                            placeholder = { Text("e.g. Deep Work, Coding, Reading") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newProfileName.isNotBlank()) {
                                val newProfile = Profile(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = newProfileName.trim(),
                                    colorHex = "#10B981",
                                    isEnabled = false
                                )
                                executeWithStrictCheck(MutationType.CREATE_PROFILE, newProfile.id) { token ->
                                    profileRepository.saveProfile(newProfile, token = token)
                                    newProfileName = ""
                                    showCreateDialog = false
                                }
                            }
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Schedule Editor Dialog
        editingProfileForSchedule?.let { profile ->
            val existingSchedule = profile.schedules.firstOrNull()
            var startHour by remember { mutableStateOf(existingSchedule?.let { it.startTimeSeconds / 3600 } ?: 9) }
            var startMin by remember { mutableStateOf(existingSchedule?.let { (it.startTimeSeconds % 3600) / 60 } ?: 0) }
            var endHour by remember { mutableStateOf(existingSchedule?.let { it.endTimeSeconds / 3600 } ?: 17) }
            var endMin by remember { mutableStateOf(existingSchedule?.let { (it.endTimeSeconds % 3600) / 60 } ?: 0) }
            var daysMask by remember { mutableStateOf(existingSchedule?.daysOfWeekMask ?: 31) } // Default Mon-Fri (31)

            AlertDialog(
                onDismissRequest = { editingProfileForSchedule = null },
                title = { Text("Configure Schedule: ${profile.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Choose the time window and days when this profile should automatically restrict applications:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Start Time
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Start Time:", fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NumberPickerStepper(value = startHour, min = 0, max = 23, onValueChange = { startHour = it }, label = "h")
                                Spacer(modifier = Modifier.width(6.dp))
                                NumberPickerStepper(value = startMin, min = 0, max = 59, step = 5, onValueChange = { startMin = it }, label = "m")
                            }
                        }

                        // End Time
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("End Time:", fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NumberPickerStepper(value = endHour, min = 0, max = 23, onValueChange = { endHour = it }, label = "h")
                                Spacer(modifier = Modifier.width(6.dp))
                                NumberPickerStepper(value = endMin, min = 0, max = 59, step = 5, onValueChange = { endMin = it }, label = "m")
                            }
                        }

                        // Days of week selector
                        Text("Active Days of the Week:", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
                            dayLabels.forEachIndexed { index, label ->
                                val bit = 1 shl index
                                val isSelected = (daysMask and bit) != 0
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        daysMask = if (isSelected) daysMask and bit.inv() else daysMask or bit
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.size(36.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        if (existingSchedule != null) {
                            TextButton(
                                onClick = {
                                    executeWithStrictCheck(MutationType.SAVE_PROFILE, profile.id) { token ->
                                        profileRepository.saveProfile(profile.copy(schedules = emptyList()), token = token)
                                        editingProfileForSchedule = null
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Remove Schedule (Block Always When On)")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val newSchedule = Schedule(
                                id = existingSchedule?.id ?: System.currentTimeMillis(),
                                profileId = profile.id,
                                daysOfWeekMask = if (daysMask == 0) 127 else daysMask,
                                startTimeSeconds = startHour * 3600 + startMin * 60,
                                endTimeSeconds = endHour * 3600 + endMin * 60,
                                isEnabled = true
                            )
                            executeWithStrictCheck(MutationType.SAVE_PROFILE, profile.id) { token ->
                                profileRepository.saveProfile(profile.copy(schedules = listOf(newSchedule)), token = token)
                                editingProfileForSchedule = null
                            }
                        }
                    ) {
                        Text("Save Schedule")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingProfileForSchedule = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // App Picker Dialog
        editingProfileForApps?.let { profile ->
            AppPickerDialog(
                initialSelectedPackages = profile.targetApps.map { it.packageName }.toSet(),
                lockedPackages = if (isStrict) profile.targetApps.map { it.packageName }.toSet() else emptySet(),
                onDismissRequest = { editingProfileForApps = null },
                onConfirmSelection = { updatedList ->
                    profileRepository.saveProfile(profile.copy(targetApps = updatedList))
                    editingProfileForApps = null
                }
            )
        }

        // Accessibility Missing Warning Dialog
        if (showAccessibilityRequiredDialog) {
            AlertDialog(
                onDismissRequest = { showAccessibilityRequiredDialog = false },
                icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Accessibility Permission Required") },
                text = {
                    Text("To block restricted applications when they are launched, TakeBack requires Accessibility permission in Android Settings.\n\nWithout Accessibility permission, turning on this profile cannot enforce restrictions.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showAccessibilityRequiredDialog = false
                            try {
                                context.startActivity(permissionRepository.getAccessibilityIntent())
                            } catch (e: Exception) {
                                // Fallback
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAccessibilityRequiredDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Strict Mode Challenge Dialog
        if (pendingStrictAction != null) {
            StrictChallengeDialog(
                mechanism = strictState.mechanism,
                saltBase64 = strictState.saltBase64,
                expectedHash = strictState.passwordHash,
                mutationType = pendingMutationType,
                targetId = pendingTargetId,
                onTokenIssued = { token ->
                    val action = pendingStrictAction
                    pendingStrictAction = null
                    action?.invoke(token)
                },
                onDismissRequest = { pendingStrictAction = null }
            )
        }
    }
}

@Composable
fun NumberPickerStepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    label: String,
    onValueChange: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        IconButton(
            onClick = {
                val next = if (value - step < min) max else value - step
                onValueChange(next)
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
        }

        Text(
            text = String.format("%02d%s", value, label),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        IconButton(
            onClick = {
                val next = if (value + step > max) min else value + step
                onValueChange(next)
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Increase", modifier = Modifier.size(16.dp))
        }
    }
}

fun formatDaysBitmask(mask: Int): String {
    if (mask == 127) return "Every day (Mon - Sun)"
    if (mask == 31) return "Weekdays (Mon - Fri)"
    if (mask == 96) return "Weekends (Sat - Sun)"

    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val selected = mutableListOf<String>()
    names.forEachIndexed { index, name ->
        if ((mask and (1 shl index)) != 0) {
            selected.add(name)
        }
    }
    return if (selected.isEmpty()) "No days" else selected.joinToString(", ")
}
