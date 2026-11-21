package com.vr.takeback.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.domain.security.StrictModeManager

@Composable
fun StrictChallengeDialog(
    mechanism: StrictMechanism,
    saltBase64: String? = null,
    expectedHash: String? = null,
    onChallengeSolved: () -> Unit,
    onDismissRequest: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Generate random text challenge once per dialog instance
    val challengeText = remember { StrictModeManager.generateTextChallenge(length = 60) }
    var textChallengeInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Strict Mode Verification",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (mechanism == StrictMechanism.PIN) {
                        "Enter your secure PIN to open a 10-minute maintenance window."
                    } else {
                        "Type the exact verification challenge below to ensure this change is deliberate."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (mechanism == StrictMechanism.PIN) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 8) {
                                pinInput = it
                                errorMessage = null
                            }
                        },
                        label = { Text("Secure PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Text Challenge
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = challengeText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = textChallengeInput,
                        onValueChange = {
                            textChallengeInput = it
                            errorMessage = null
                        },
                        placeholder = { Text("Type the challenge above...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${textChallengeInput.length}/${challengeText.length} characters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (mechanism == StrictMechanism.PIN) {
                                val salt = saltBase64
                                val hash = expectedHash
                                if (salt != null && hash != null && StrictModeManager.verifyPin(pinInput, salt, hash)) {
                                    onChallengeSolved()
                                } else {
                                    errorMessage = "Incorrect PIN. Please try again."
                                }
                            } else {
                                if (StrictModeManager.verifyTextChallenge(textChallengeInput, challengeText)) {
                                    onChallengeSolved()
                                } else {
                                    errorMessage = "Challenge does not match. Please verify character by character."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Verify & Unlock")
                    }
                }
            }
        }
    }
}
