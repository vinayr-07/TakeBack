package com.vr.takeback.presentation.ui.screens

import android.content.Intent
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.data.repository.QuickBlockRepository
import com.vr.takeback.data.repository.UsageStatsRepository
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog
import com.vr.takeback.presentation.viewmodel.HomeUiEvent
import com.vr.takeback.presentation.viewmodel.HomeUiState
import com.vr.takeback.presentation.viewmodel.HomeViewModel

/**
 * State-aware entry point for HomeScreen.
 * Connects HomeViewModel to the stateless HomeScreenContent.
 */
@Composable
fun HomeScreen(
    profileRepository: ProfileRepository,
    quickBlockRepository: QuickBlockRepository,
    usageStatsRepository: UsageStatsRepository,
    permissionRepository: PermissionRepository,
    onNavigateToFocus: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToInsights: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onEvent(HomeUiEvent.Refresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    HomeScreenContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        getAccessibilityIntent = { permissionRepository.getAccessibilityIntent() },
        onNavigateToFocus = onNavigateToFocus,
        onNavigateToProfiles = onNavigateToProfiles,
        onNavigateToPermissions = onNavigateToPermissions,
        onNavigateToInsights = onNavigateToInsights
    )
}

/**
 * Pure, stateless, declarative UI implementation of HomeScreen.
 * Easily previewable and decoupled from repository singletons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onEvent: (HomeUiEvent) -> Unit,
    getAccessibilityIntent: () -> Intent,
    onNavigateToFocus: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToInsights: () -> Unit
) {
    val context = LocalContext.current

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
            // 1. Missing Permissions Alert Banner
            if (uiState.isAccessibilityMissing) {
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
                                            context.startActivity(getAccessibilityIntent())
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
            } else if (uiState.isUsageStatsMissing) {
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

            // 2. Master Protection Status Indicator
            item {
                val isPausedDueToPermission = uiState.isAccessibilityMissing && uiState.isProtectionConfigured
                val cardBgColor = when {
                    isPausedDueToPermission -> Color(0xFF78350F)
                    uiState.isProtectionActive && !uiState.isAccessibilityMissing -> Color(0xFF064E3B)
                    else -> Color(0xFF450A0A)
                }
                val iconBgColor = when {
                    isPausedDueToPermission -> Color(0xFFF59E0B)
                    uiState.isProtectionActive && !uiState.isAccessibilityMissing -> Color(0xFF10B981)
                    else -> Color(0xFFEF4444)
                }
                val titleColor = when {
                    isPausedDueToPermission -> Color(0xFFFEF3C7)
                    uiState.isProtectionActive && !uiState.isAccessibilityMissing -> Color(0xFFA7F3D0)
                    else -> Color(0xFFFECACA)
                }
                val subtitleColor = when {
                    isPausedDueToPermission -> Color(0xFFFDE68A)
                    uiState.isProtectionActive && !uiState.isAccessibilityMissing -> Color(0xFFD1FAE5)
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
                                        uiState.isProtectionActive && !uiState.isAccessibilityMissing -> Icons.Filled.Shield
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
                                        uiState.isProtectionActive && !uiState.isAccessibilityMissing -> "PROTECTION ACTIVE"
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
                                        uiState.isQuickBlockActive -> {
                                            val mins = uiState.quickBlockState.remainingSeconds / 60
                                            val secs = uiState.quickBlockState.remainingSeconds % 60
                                            "Quick Block active (${mins}m ${secs}s left) • Guarding distractions"
                                        }
                                        uiState.activelyEnforcingProfiles.isNotEmpty() -> {
                                            val totalApps = uiState.activelyEnforcingProfiles.flatMap { it.targetApps }.distinctBy { it.packageName }.size
                                            "${uiState.activelyEnforcingProfiles.size} profile(s) actively blocking $totalApps app(s)"
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
                                        context.startActivity(getAccessibilityIntent())
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

            // 3. Screen Time Overview & Daily Allowance
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

                            if (uiState.usageSummary?.isRealDataAvailable == true) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        text = uiState.usageSummary.dataQuality.displayLabel,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (uiState.isUsageStatsMissing) {
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
                        } else if (uiState.isUsageLoading && uiState.usageSummary == null) {
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
                        } else if (uiState.usageSummary != null) {
                            val summary = uiState.usageSummary
                            val isInteractiveAvailable = summary.isDeviceInteractiveAvailable && summary.deviceScreenTimeMillis != null
                            val deviceMillis = summary.deviceScreenTimeMillis
                            val totalAppMillis = summary.totalTimeMillis

                            val totalMillis = deviceMillis ?: totalAppMillis
                            val usedHours = totalMillis / (1000 * 3600)
                            val usedMins = (totalMillis % (1000 * 3600)) / (1000 * 60)
                            val allowanceMillis = uiState.dailyAllowanceMinutes * 60 * 1000L
                            val progress = if (allowanceMillis > 0) (totalMillis.toFloat() / allowanceMillis).coerceIn(0f, 1f) else 0f
                            val remainingMinutes = maxOf(0L, (allowanceMillis - totalMillis) / (1000 * 60))

                            if (isInteractiveAvailable) {
                                Text(
                                    text = "${usedHours}h ${usedMins}m",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else if (summary.isRealDataAvailable && totalAppMillis > 0L) {
                                Column {
                                    Text(
                                        text = "${usedHours}h ${usedMins}m",
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Tracked Apps (Display interactive time unavailable)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Column {
                                    Text(
                                        text = "Unavailable",
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Screen state events unrecorded by OS",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

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
                                    .clickable { onEvent(HomeUiEvent.SetAllowanceDialogVisible(true)) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Daily Allowance: ${uiState.dailyAllowanceMinutes / 60}h ${if (uiState.dailyAllowanceMinutes % 60 > 0) "${uiState.dailyAllowanceMinutes % 60}m" else "00m"}",
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

            // 4. Quick Block Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.isQuickBlockActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
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
                                    tint = if (uiState.isQuickBlockActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.isQuickBlockActive) "Quick Block Running" else "Quick Distraction Block",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (uiState.isQuickBlockActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (uiState.isQuickBlockActive) {
                                Button(
                                    onClick = { onEvent(HomeUiEvent.CancelQuickBlock) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Stop", color = Color.White)
                                }
                            }
                        }

                        if (uiState.isQuickBlockActive) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val mins = uiState.quickBlockState.remainingSeconds / 60
                            val secs = uiState.quickBlockState.remainingSeconds % 60
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
                                        onClick = { onEvent(HomeUiEvent.StartQuickBlock(minutes)) },
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

            // 5. Profiles Overview Section
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

            items(uiState.profiles.size) { index ->
                val profile = uiState.profiles[index]
                val isProfileEnforcingNow = uiState.activelyEnforcingProfiles.any { it.id == profile.id }

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
                                onCheckedChange = {
                                    onEvent(HomeUiEvent.RequestToggleProfile(profile.id))
                                }
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

        // Dialog: Daily Allowance Configuration
        if (uiState.showAllowanceDialog) {
            AlertDialog(
                onDismissRequest = { onEvent(HomeUiEvent.SetAllowanceDialogVisible(false)) },
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
                                    onEvent(HomeUiEvent.SetDailyAllowance(mins))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (uiState.dailyAllowanceMinutes == mins) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("$hours hours per day", fontWeight = if (uiState.dailyAllowanceMinutes == mins) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onEvent(HomeUiEvent.SetAllowanceDialogVisible(false)) }) {
                        Text("Close")
                    }
                }
            )
        }

        // Strict Mode Challenge Dialog for Profile Toggle
        if (uiState.pendingToggleProfileId != null) {
            StrictChallengeDialog(
                mechanism = uiState.strictState.mechanism,
                saltBase64 = uiState.strictState.saltBase64,
                expectedHash = uiState.strictState.passwordHash,
                mutationType = MutationType.TOGGLE_PROFILE,
                targetId = uiState.pendingToggleProfileId,
                onTokenIssued = { token ->
                    val pid = uiState.pendingToggleProfileId ?: return@StrictChallengeDialog
                    onEvent(HomeUiEvent.ConfirmToggleProfileWithToken(pid, token))
                },
                onDismissRequest = {
                    onEvent(HomeUiEvent.DismissToggleChallenge)
                }
            )
        }
    }
}
