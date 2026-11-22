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
import com.vr.takeback.presentation.ui.components.StrictChallengeDialog
import com.vr.takeback.ui.theme.TakeBackTheme

class BlockShieldActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appName = intent.getStringExtra("EXTRA_APP_NAME") ?: "This Application"
        val profileName = intent.getStringExtra("EXTRA_PROFILE_NAME") ?: "Focus Profile"
        val reason = intent.getStringExtra("EXTRA_REASON") ?: "Restricted Focus Window"
        val isSettingsShield = intent.getBooleanExtra("EXTRA_IS_SETTINGS_SHIELD", false)
        val isEssentialShield = intent.getBooleanExtra("EXTRA_IS_ESSENTIAL_SHIELD", false)
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

                    val allowanceMins = if (rawAllowanceMins > 0) rawAllowanceMins else essentialState.dailyAllowanceMinutes
                    val usedMins = if (rawUsedMins > 0) rawUsedMins else essentialState.usageAtActivationMinutes

                    var showAuthDialog by remember { mutableStateOf(false) }
                    var showEmergencyExitPinDialog by remember { mutableStateOf(false) }
                    var showEmergencyExitChallengeDialog by remember { mutableStateOf(false) }
                    var rateLimitTick by remember { mutableStateOf(0) }

                    // Lockout countdown tick
                    LaunchedEffect(showEmergencyExitPinDialog, rateLimitTick) {
                        if (essentialRepo.isRateLimited()) {
                            kotlinx.coroutines.delay(1000L)
                            rateLimitTick++
                        }
                    }

                    BlockShieldContent(
                        appName = appName,
                        profileName = profileName,
                        reason = reason,
                        isSettingsShield = isSettingsShield,
                        isEssentialShield = isEssentialShield,
                        targetLabel = targetLabel,
                        allowanceMinutes = allowanceMins,
                        usedMinutes = usedMins,
                        selectedAdditionalPackage = essentialState.selectedAdditionalPackage,
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
                            if (strictState.mechanism == com.vr.takeback.domain.model.StrictMechanism.TEXT_CHALLENGE) {
                                showEmergencyExitChallengeDialog = true
                            } else {
                                showEmergencyExitPinDialog = true
                            }
                        }
                    )

                    if (showEmergencyExitPinDialog) {
                        var enteredPin by remember { mutableStateOf("") }
                        var pinError by remember { mutableStateOf<String?>(null) }
                        val isLockedOut = essentialRepo.isRateLimited()
                        val lockoutRemaining = essentialRepo.getLockoutRemainingSeconds()

                        AlertDialog(
                            onDismissRequest = { showEmergencyExitPinDialog = false },
                            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            title = { Text("Emergency Exit", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text(
                                        "Authenticate with your Strict Mode PIN to pause Essential Mode for the remainder of today. " +
                                                "Restrictions will automatically resume at tomorrow's daily reset.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    if (isLockedOut) {
                                        Text(
                                            "Too many failed attempts. Locked out for $lockoutRemaining seconds.",
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    } else {
                                        OutlinedTextField(
                                            value = enteredPin,
                                            onValueChange = { enteredPin = it; pinError = null },
                                            label = { Text("Strict Mode PIN") },
                                            isError = pinError != null,
                                            supportingText = pinError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                                            visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (isLockedOut) return@Button
                                        val success = essentialRepo.authenticateAndTriggerEmergencyExit(enteredPin)
                                        if (success) {
                                            showEmergencyExitPinDialog = false
                                            finish()
                                        } else {
                                            pinError = if (essentialRepo.isRateLimited()) {
                                                "Too many failed attempts. Locked out for ${essentialRepo.getLockoutRemainingSeconds()}s."
                                            } else {
                                                "Incorrect Strict Mode PIN."
                                            }
                                        }
                                    },
                                    enabled = !isLockedOut && enteredPin.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Authorize Exit")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showEmergencyExitPinDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    if (showEmergencyExitChallengeDialog) {
                        StrictChallengeDialog(
                            mechanism = strictState.mechanism,
                            saltBase64 = strictState.saltBase64,
                            expectedHash = strictState.passwordHash,
                            mutationType = com.vr.takeback.domain.security.MutationType.UPDATE_ESSENTIAL_MODE,
                            onTokenIssued = { token ->
                                val success = essentialRepo.authenticateAndTriggerEmergencyExitWithToken(token)
                                if (success) {
                                    showEmergencyExitChallengeDialog = false
                                    finish()
                                }
                            },
                            onDismissRequest = { showEmergencyExitChallengeDialog = false }
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
    targetLabel: String? = null,
    allowanceMinutes: Int = 0,
    usedMinutes: Int = 0,
    selectedAdditionalPackage: String? = null,
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
                    if (isSettingsShield || isEssentialShield) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSettingsShield || isEssentialShield) Icons.Filled.Lock else Icons.Filled.Shield,
                contentDescription = null,
                tint = if (isSettingsShield || isEssentialShield) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = when {
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
            containerColor = if (isSettingsShield || isEssentialShield) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = when {
                    isEssentialShield -> "ESSENTIAL MODE ACTIVE"
                    isSettingsShield -> "STRICT ANTI-TAMPER GUARD"
                    else -> "GUARDED BY: $profileName"
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isSettingsShield || isEssentialShield) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isEssentialShield) {
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Today's Screen Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${usedH}h ${usedM}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Daily Allowance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${allowH}h ${allowM}m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    if (!selectedAdditionalPackage.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val cleanAppName = selectedAdditionalPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        Text(
                            text = "+ User-Selected App: $cleanAppName ($selectedAdditionalPackage)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Samsung One Hand Operation+ gestures and Good Lock modules remain operational.",
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
                    text = if (isRateLimited) "Emergency Exit Locked (${lockoutRemainingSeconds}s)" else "Emergency Exit",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
