package com.vr.takeback.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Timer
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vr.takeback.data.repository.DailyUsageSummary
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.data.repository.QuickBlockRepository
import com.vr.takeback.data.repository.UsageStatsRepository
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.model.Profile
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    profileRepository: ProfileRepository,
    quickBlockRepository: QuickBlockRepository,
    usageStatsRepository: UsageStatsRepository,
    permissionRepository: PermissionRepository,
    onNavigateToFocus: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToInsights: () -> Unit
) {
    val context = LocalContext.current
    val profiles by profileRepository.profiles.collectAsState()
    val quickBlockState by quickBlockRepository.state.collectAsState()
    val permissionStatus by permissionRepository.permissionStatus.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRepository.refreshPermissions()
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Real screen time query - re-queries on resume or permission change
    val usageSummary by produceState<DailyUsageSummary?>(
        initialValue = null,
        key1 = permissionStatus.hasUsageStats,
        key2 = refreshTrigger
    ) {
        value = usageStatsRepository.getTodayUsageSummary()
    }

    // Persistent daily screen time allowance
    var dailyAllowanceMinutes by remember { mutableStateOf(usageStatsRepository.getDailyAllowanceMinutes()) }
    LaunchedEffect(refreshTrigger) {
        dailyAllowanceMinutes = usageStatsRepository.getDailyAllowanceMinutes()
    }
    var showAllowanceDialog by remember { mutableStateOf(false) }

    // Evaluate active blocking status dynamically
    val engine = remember { CentralRestrictionEngine() }
    val nowTime = LocalTime.now()
    val today = LocalDate.now().dayOfWeek

    val activelyEnforcingProfiles = profiles.filter { profile ->
        profile.isEnabled && engine.isProfileCurrentlyEnforcing(profile, nowTime, today)
    }

    val isQuickBlockActive = quickBlockState.isActive
    val hasEnabledProfile = profiles.any { it.isEnabled }
    val isProtectionConfigured = isQuickBlockActive || activelyEnforcingProfiles.isNotEmpty() || hasEnabledProfile
    val isProtectionActive = isQuickBlockActive || activelyEnforcingProfiles.isNotEmpty()
    val isAccessibilityMissing = !permissionStatus.hasAccessibility

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "TakeBack",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Distraction Control & Digital Wellbeing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            // Persistent Missing Permissions Alert Banner
            if (isAccessibilityMissing) {
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
                                    imageVector = Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Accessibility Permission Missing",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "ACTION REQUIRED: APPS CANNOT BE BLOCKED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "TakeBack cannot detect opened apps or enforce restrictions until Accessibility is enabled in your Android Settings. Turning on profiles or schedules will not block apps without this permission.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(permissionRepository.getAccessibilityIntent())
                                        } catch (e: Exception) {
                                            onNavigateToPermissions()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Grant Accessibility")
                                }
                                OutlinedButton(
                                    onClick = onNavigateToPermissions,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text("Setup Center")
                                }
                            }
                        }
                    }
                }
            } else if (!permissionStatus.hasUsageStats) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Usage Access Required",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Grant Usage Access so TakeBack can track screen time accurately instead of estimating.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onNavigateToPermissions,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Grant Usage Access")
                            }
                        }
                    }
                }
            }

            // Master Protection Status Indicator
            item {
                val isPausedDueToPermission = isAccessibilityMissing && isProtectionConfigured
                val cardBgColor = when {
                    isPausedDueToPermission -> Color(0xFF78350F) // Amber/Brown warning
                    isProtectionActive && !isAccessibilityMissing -> Color(0xFF064E3B) // Emerald active
                    else -> Color(0xFF450A0A) // Red idle
                }
                val iconBgColor = when {
                    isPausedDueToPermission -> Color(0xFFF59E0B) // Amber
                    isProtectionActive && !isAccessibilityMissing -> Color(0xFF10B981) // Green
                    else -> Color(0xFFEF4444) // Red
                }
                val titleColor = when {
                    isPausedDueToPermission -> Color(0xFFFEF3C7)
                    isProtectionActive && !isAccessibilityMissing -> Color(0xFFA7F3D0)
                    else -> Color(0xFFFECACA)
                }
                val subtitleColor = when {
                    isPausedDueToPermission -> Color(0xFFFDE68A)
                    isProtectionActive && !isAccessibilityMissing -> Color(0xFFD1FAE5)
                    else -> Color(0xFFFEE2E2)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(iconBgColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        isPausedDueToPermission -> Icons.Filled.Warning
                                        isProtectionActive && !isAccessibilityMissing -> Icons.Filled.Shield
                                        else -> Icons.Filled.ShieldMoon
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        isPausedDueToPermission -> "BLOCKER PAUSED (PERMISSION OFF)"
                                        isProtectionActive && !isAccessibilityMissing -> "PROTECTION ACTIVE"
                                        else -> "PROTECTION INACTIVE (IDLE)"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = titleColor
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        isPausedDueToPermission -> {
                                            "Protection is configured/on, but TakeBack CANNOT block apps because Accessibility permission is OFF. Tap below to enable."
                                        }
                                        isQuickBlockActive -> {
                                            val mins = quickBlockState.remainingSeconds / 60
                                            val secs = quickBlockState.remainingSeconds % 60
                                            "Quick Block active (${mins}m ${secs}s left) • Guarding distractions"
                                        }
                                        activelyEnforcingProfiles.isNotEmpty() -> {
                                            val totalApps = activelyEnforcingProfiles.flatMap { it.targetApps }.distinctBy { it.packageName }.size
                                            "${activelyEnforcingProfiles.size} profile(s) actively blocking $totalApps app(s)"
                                        }
                                        else -> {
                                            "No restrictions running. Blocker is idle. Enable a profile below or start Quick Block."
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = subtitleColor
                                )
                            }
                        }

                        if (isPausedDueToPermission) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(permissionRepository.getAccessibilityIntent())
                                    } catch (e: Exception) {
                                        onNavigateToPermissions()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B),
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enable Accessibility in Settings to Activate Blocker", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Real Screen Time Overview & User-Configured Daily Allowance
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToInsights() },
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
                            Text(
                                text = "Today's Screen Time",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (usageSummary?.isRealDataAvailable == true) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        text = usageSummary?.dataQuality?.displayLabel ?: "Usage Data",
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (!permissionStatus.hasUsageStats) {
                            Text(
                                text = "Usage Access Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Grant Usage Access to display your actual device screen time without guessing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(onClick = onNavigateToPermissions) {
                                Text("Grant Permission")
                            }
                        } else if (usageSummary == null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Loading real screen time...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val totalMillis = usageSummary!!.deviceScreenTimeMillis
                            val usedHours = totalMillis / (1000 * 3600)
                            val usedMins = (totalMillis % (1000 * 3600)) / (1000 * 60)
                            val allowanceMillis = dailyAllowanceMinutes * 60 * 1000L
                            val progress = if (allowanceMillis > 0) (totalMillis.toFloat() / allowanceMillis).coerceIn(0f, 1f) else 0f
                            val remainingMinutes = maxOf(0L, (allowanceMillis - totalMillis) / (1000 * 60))

                            Text(
                                text = "${usedHours}h ${usedMins}m",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAllowanceDialog = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Daily Allowance: ${dailyAllowanceMinutes / 60}h ${if (dailyAllowanceMinutes % 60 > 0) "${dailyAllowanceMinutes % 60}m" else "00m"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Edit Allowance",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = if (remainingMinutes > 0) "${remainingMinutes / 60}h ${remainingMinutes % 60}m remaining" else "Allowance reached!",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (remainingMinutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Quick Block Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (quickBlockState.isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Timer,
                                    contentDescription = null,
                                    tint = if (quickBlockState.isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (quickBlockState.isActive) "Quick Block Running" else "Quick Distraction Block",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (quickBlockState.isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (quickBlockState.isActive) {
                                Button(
                                    onClick = { quickBlockRepository.cancelQuickBlock() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Stop", color = Color.White)
                                }
                            }
                        }

                        if (quickBlockState.isActive) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val mins = quickBlockState.remainingSeconds / 60
                            val secs = quickBlockState.remainingSeconds % 60
                            Text(
                                text = String.format("%02d:%02d remaining", mins, secs),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Distracting apps are actively blocked. Opening them will trigger the protection shield.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Instantly mute distracting apps for a focused sprint.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(15, 30, 60, 120).forEach { minutes ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            quickBlockRepository.startQuickBlock(minutes)
                                        },
                                        label = {
                                            Text(if (minutes >= 60) "${minutes / 60}h" else "${minutes}m")
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Profiles Overview Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Your Restriction Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onNavigateToProfiles) {
                        Text("Manage All")
                    }
                }
            }

            items(profiles.size) { index ->
                val profile = profiles[index]
                val isProfileEnforcingNow = profile.isEnabled && engine.isProfileCurrentlyEnforcing(profile, nowTime, today)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
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
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = try {
                                            Color(android.graphics.Color.parseColor(profile.colorHex))
                                        } catch (e: Exception) {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (isProfileEnforcingNow) "Actively Enforcing Now" else if (profile.isEnabled) "Enabled (Standby/Scheduled)" else "Disabled (Off)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isProfileEnforcingNow) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isProfileEnforcingNow) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = profile.isEnabled,
                                onCheckedChange = { profileRepository.toggleProfile(profile.id) }
                            )
                        }

                        // App summary preview
                        if (profile.targetApps.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Restricts: ${profile.targetApps.take(4).joinToString { it.appName }}${if (profile.targetApps.size > 4) " (+${profile.targetApps.size - 4} more)" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Daily Allowance Configuration Dialog
        if (showAllowanceDialog) {
            AlertDialog(
                onDismissRequest = { showAllowanceDialog = false },
                title = { Text("Set Daily Screen Time Allowance") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Choose your target daily screen time limit. TakeBack will track your progress against this goal:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf(60, 120, 180, 240, 300).forEach { mins ->
                            val hours = mins / 60
                            OutlinedButton(
                                onClick = {
                                    dailyAllowanceMinutes = mins
                                    usageStatsRepository.setDailyAllowanceMinutes(mins)
                                    showAllowanceDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (dailyAllowanceMinutes == mins) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("$hours hours per day", fontWeight = if (dailyAllowanceMinutes == mins) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAllowanceDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
