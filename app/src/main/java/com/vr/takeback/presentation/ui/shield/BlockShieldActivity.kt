package com.vr.takeback.presentation.ui.shield

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.ValidatedUsageSnapshot
import com.vr.takeback.presentation.ui.components.EssentialModeExitDialog
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog
import com.vr.takeback.ui.theme.TakeBackTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BlockShieldActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appName = intent.getStringExtra("EXTRA_APP_NAME") ?: "This Application"
        val profileName = intent.getStringExtra("EXTRA_PROFILE_NAME") ?: "Focus Profile"
        val reason = intent.getStringExtra("EXTRA_REASON") ?: "Restricted Focus Window"
        val isSettingsShield = intent.getBooleanExtra("EXTRA_IS_SETTINGS_SHIELD", false)
        val isEssentialShield = intent.getBooleanExtra("EXTRA_IS_ESSENTIAL_SHIELD", false)
        val isCooldownShield = intent.getBooleanExtra("EXTRA_IS_COOLDOWN_SHIELD", false)
        val cooldownRemainingMins = intent.getIntExtra("EXTRA_COOLDOWN_REMAINING_MINS", 0)
        val targetCategory = intent.getStringExtra("EXTRA_TARGET_CATEGORY") ?: "Social Media"
        val targetLabel = intent.getStringExtra("EXTRA_TARGET_LABEL")
        val rawAllowanceMins = intent.getIntExtra("EXTRA_ALLOWANCE_MINS", 0)
        val rawUsedMins = intent.getIntExtra("EXTRA_USED_MINS", 0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finish()
            }
        })

        setContent {
            TakeBackTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val strictRepo = TakeBackApplication.strictModeRepository
                    val strictState by strictRepo.protectionState.collectAsState()
                    val essentialRepo = remember { TakeBackApplication.essentialModeRepository }
                    val essentialState by essentialRepo.state.collectAsState()
                    val usageRepo = remember { TakeBackApplication.usageStatsRepository }

                    var liveSnapshot by remember { mutableStateOf<ValidatedUsageSnapshot?>(null) }

                    // Periodically refresh live usage snapshot while shield is displayed
                    LaunchedEffect(Unit) {
                        while (true) {
                            val snap = withContext(Dispatchers.IO) {
                                try {
                                    usageRepo.refreshUsageSnapshot()
                                } catch (e: Exception) {
                                    usageRepo.getActiveUsageSnapshot()
                                }
                            }
                            liveSnapshot = snap
                            kotlinx.coroutines.delay(10_000L)
                        }
                    }

                    val allowanceMins = if (rawAllowanceMins > 0) rawAllowanceMins else essentialState.dailyAllowanceMinutes
                    val liveScreenTimeMinutes = liveSnapshot?.let { snap ->
                        if (snap.isDeviceScreenTimeAvailable && snap.deviceScreenTimeMillis != null) {
                            (snap.deviceScreenTimeMillis / 60000L).toInt()
                        } else {
                            (snap.foregroundUsageByPackage.values.sumOf { it } / 60000L).toInt()
                        }
                    } ?: if (rawUsedMins > 0) rawUsedMins else essentialState.usageAtActivationMinutes

                    var showAuthDialog by remember { mutableStateOf(false) }
                    var showEmergencyExitDialog by remember { mutableStateOf(false) }

                    BlockShieldContent(
                        appName = appName,
                        profileName = profileName,
                        reason = reason,
                        isSettingsShield = isSettingsShield,
                        isEssentialShield = isEssentialShield,
                        isCooldownShield = isCooldownShield,
                        cooldownRemainingMinutes = cooldownRemainingMins,
                        targetCategory = targetCategory,
                        targetLabel = targetLabel,
                        allowanceMinutes = allowanceMins,
                        usedMinutes = liveScreenTimeMinutes,
                        usageAtActivationMinutes = essentialState.usageAtActivationMinutes,
                        liveSnapshot = liveSnapshot,
                        selectedAdditionalPackages = essentialState.selectedAdditionalPackages,
                        isRateLimited = essentialRepo.isRateLimited(),
                        lockoutRemainingSeconds = essentialRepo.getLockoutRemainingSeconds(),
                        onGoHomeClick = {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                            finish()
                        },
                        onAuthenticateClick = {
                            showAuthDialog = true
                        },
                        onEmergencyExitClick = {
                            showEmergencyExitDialog = true
                        }
                    )

                    if (showEmergencyExitDialog) {
                        EssentialModeExitDialog(
                            essentialRepository = essentialRepo,
                            onExited = {
                                showEmergencyExitDialog = false
                                finish()
                            },
                            onDismissRequest = { showEmergencyExitDialog = false }
                        )
                    }

                    if (showAuthDialog) {
                        StrictChallengeDialog(
                            mechanism = strictState.mechanism,
                            saltBase64 = strictState.saltBase64,
                            expectedHash = strictState.passwordHash,
                            mutationType = com.vr.takeback.domain.security.MutationType.SETTINGS_MAINTENANCE,
                            onTokenIssued = { token ->
                                strictRepo.grantTemporarySettingsUnlock(durationMinutes = 5)
                                showAuthDialog = false
                                finish()
                            },
                            onChallengeSolved = {
                                strictRepo.grantTemporarySettingsUnlock(durationMinutes = 5)
                                showAuthDialog = false
                                finish()
                            },
                            onDismissRequest = {
                                showAuthDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BlockShieldContent(
    appName: String,
    profileName: String,
    reason: String,
    isSettingsShield: Boolean = false,
    isEssentialShield: Boolean = false,
    isCooldownShield: Boolean = false,
    cooldownRemainingMinutes: Int = 0,
    targetCategory: String = "Social Media",
    targetLabel: String? = null,
    allowanceMinutes: Int = 0,
    usedMinutes: Int = 0,
    usageAtActivationMinutes: Int = 0,
    liveSnapshot: ValidatedUsageSnapshot? = null,
    selectedAdditionalPackages: List<String> = emptyList(),
    isRateLimited: Boolean = false,
    lockoutRemainingSeconds: Int = 0,
    onGoHomeClick: () -> Unit,
    onAuthenticateClick: () -> Unit = {},
    onEmergencyExitClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isSettingsShield || isEssentialShield -> MaterialTheme.colorScheme.errorContainer
                        isCooldownShield -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isSettingsShield || isEssentialShield -> Icons.Filled.Lock
                    isCooldownShield -> Icons.Filled.HourglassTop
                    else -> Icons.Filled.Shield
                },
                contentDescription = null,
                tint = when {
                    isSettingsShield || isEssentialShield -> MaterialTheme.colorScheme.error
                    isCooldownShield -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = when {
                isCooldownShield -> "$targetCategory Resting"
                isEssentialShield -> "Daily Allowance Exhausted"
                isSettingsShield -> (targetLabel ?: "Settings Access Locked")
                else -> "$appName is Paused"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Badge(
            containerColor = when {
                isSettingsShield || isEssentialShield -> MaterialTheme.colorScheme.errorContainer
                isCooldownShield -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        ) {
            Text(
                text = when {
                    isCooldownShield -> "MANDATORY 55-MIN COOLDOWN"
                    isEssentialShield -> "ESSENTIAL MODE ACTIVE"
                    isSettingsShield -> "STRICT ANTI-TAMPER GUARD"
                    else -> "GUARDED BY: $profileName"
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = when {
                    isSettingsShield || isEssentialShield -> MaterialTheme.colorScheme.onErrorContainer
                    isCooldownShield -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isCooldownShield) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$cooldownRemainingMinutes min remaining",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "5-minute session limit reached across $targetCategory apps. Next session unlocks after a 55-minute cooldown.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Take a break, stretch, or focus on offline tasks.",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (isEssentialShield) {
            // Essential Mode Usage Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val usedH = usedMinutes / 60
                    val usedM = usedMinutes % 60
                    val allowH = allowanceMinutes / 60
                    val allowM = allowanceMinutes % 60

                    val now = System.currentTimeMillis()
                    val isStale = liveSnapshot != null && (now - liveSnapshot.capturedAtMillis > 60_000L)
                    val qualityText = when {
                        isStale -> "DATA STALE (>1m ago)"
                        liveSnapshot?.isDeviceScreenTimeAvailable == true -> "Device Screen Time (Authoritative)"
                        else -> "App Usage Sum (Heuristic)"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Today's Screen Time (Live)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${usedH}h ${usedM}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            if (usageAtActivationMinutes > 0 && usageAtActivationMinutes != usedMinutes) {
                                val actH = usageAtActivationMinutes / 60
                                val actM = usageAtActivationMinutes % 60
                                Text("Triggered at: ${actH}h ${actM}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Daily Allowance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${allowH}h ${allowM}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isStale) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = qualityText,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = if (isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val progress = if (allowanceMinutes > 0) (usedMinutes.toFloat() / allowanceMinutes.toFloat()).coerceIn(0f, 1f) else 1f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Available Applications:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Phone", "SMS", "Camera", "WhatsApp").forEach { appTitle ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = appTitle,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    if (selectedAdditionalPackages.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val formattedApps = selectedAdditionalPackages.joinToString(", ") { pkg ->
                            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        }
                        Text(
                            text = "+ User-Selected Apps (${selectedAdditionalPackages.size}/2): $formattedApps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "AI chatbots (ChatGPT, Gemini, Claude, etc.), payment apps, Samsung One Hand Operation+, and Good Lock remain operational.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.HourglassTop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Restriction In Effect",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onGoHomeClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Return to Home Screen", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        if (isSettingsShield) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onAuthenticateClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Authenticate for Settings Access", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }

        if (isEssentialShield) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onEmergencyExitClick,
                enabled = !isRateLimited,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRateLimited) "Exit Locked (${lockoutRemainingSeconds}s)" else "Exit Essential Mode (Password)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
