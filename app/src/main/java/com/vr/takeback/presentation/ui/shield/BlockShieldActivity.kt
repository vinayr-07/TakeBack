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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
                    var showAuthDialog by remember { mutableStateOf(false) }

                    BlockShieldContent(
                        appName = appName,
                        profileName = profileName,
                        reason = reason,
                        isSettingsShield = isSettingsShield,
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
                        }
                    )

                    if (showAuthDialog) {
                        StrictChallengeDialog(
                            mechanism = strictState.mechanism,
                            saltBase64 = strictState.saltBase64,
                            expectedHash = strictState.passwordHash,
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
    onGoHomeClick: () -> Unit,
    onAuthenticateClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(
                    if (isSettingsShield) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSettingsShield) Icons.Filled.Lock else Icons.Filled.Shield,
                contentDescription = null,
                tint = if (isSettingsShield) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = if (isSettingsShield) "Settings Access Locked" else "$appName is Paused",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Badge(
            containerColor = if (isSettingsShield) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = "GUARDED BY: $profileName",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isSettingsShield) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
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

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onGoHomeClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Return to Home Screen", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        if (isSettingsShield) {
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onAuthenticateClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Authenticate for Settings Access", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}
