package com.vr.takeback.presentation.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vr.takeback.data.repository.PermissionRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCenterScreen(
    permissionRepository: PermissionRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val permissionStatus by permissionRepository.permissionStatus.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRepository.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Permission Health Center",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "TakeBack runs 100% locally on your device. It requires specific system access to monitor foreground apps, display blocking shields, and prevent OS background termination.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. Usage Stats Permission
            item {
                PermissionItemCard(
                    title = "Usage Access",
                    description = "Required to calculate real device screen time, track app opens, and enforce daily quotas.",
                    isGranted = permissionStatus.hasUsageStats,
                    onGrantClick = {
                        context.startActivity(permissionRepository.getUsageAccessIntent())
                    }
                )
            }

            // 2. Overlay Permission
            item {
                PermissionItemCard(
                    title = "Display Over Other Apps",
                    description = "Allows TakeBack to display an instant, non-bypassable shield when a restricted app is opened.",
                    isGranted = permissionStatus.hasOverlay,
                    onGrantClick = {
                        context.startActivity(permissionRepository.getOverlayIntent())
                    }
                )
            }

            // 3. Accessibility Service
            item {
                PermissionItemCard(
                    title = "Accessibility Service",
                    description = "Enables sub-50ms instant app switch detection, browser address bar URL inspection, and selective Shorts/Reels filtering.",
                    isGranted = permissionStatus.hasAccessibility,
                    onGrantClick = {
                        context.startActivity(permissionRepository.getAccessibilityIntent())
                    }
                )
            }

            // 4. Notification Listener
            item {
                PermissionItemCard(
                    title = "Notification Access",
                    description = "Mutes distracting alerts and buzzes from restricted apps during active focus sessions.",
                    isGranted = permissionStatus.hasNotificationListener,
                    onGrantClick = {
                        context.startActivity(permissionRepository.getNotificationListenerIntent())
                    }
                )
            }

            // 5. Battery Optimization Exemption
            item {
                PermissionItemCard(
                    title = "Battery Optimization Exemption",
                    description = "Prevents aggressive Android & OEM background task killers from terminating blocking services while screen is off.",
                    isGranted = permissionStatus.isBatteryOptimizationIgnored,
                    onGrantClick = {
                        context.startActivity(permissionRepository.getBatteryOptimizationIntent())
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isGranted) Color(0xFF064E3B) else MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isGranted) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color(0xFFA7F3D0),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isGranted) "GRANTED" else "REQUIRED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (isGranted) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!isGranted) {
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Enable in System Settings")
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Active & protecting your device",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }
    }
}
