package com.vr.takeback.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.EssentialModeRepository

/**
 * Shared password-only authentication dialog to exit Essential Mode until the next midnight reset.
 */
@Composable
fun EssentialModeExitDialog(
    essentialRepository: EssentialModeRepository = TakeBackApplication.essentialModeRepository,
    onExited: () -> Unit,
    onDismissRequest: () -> Unit
) {
    var enteredPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var rateLimitTick by remember { mutableStateOf(0) }

    val hasPin = remember(rateLimitTick) { essentialRepository.hasConfiguredPin() }
    val canRecoverPassword = remember(rateLimitTick) { essentialRepository.canRecoverMissingPassword() }
    val isLockedOut = remember(rateLimitTick, canRecoverPassword) {
        !canRecoverPassword && essentialRepository.isRateLimited()
    }
    val lockoutRemaining = remember(rateLimitTick) { essentialRepository.getLockoutRemainingSeconds() }

    LaunchedEffect(rateLimitTick, isLockedOut) {
        if (!canRecoverPassword && essentialRepository.isRateLimited()) {
            kotlinx.coroutines.delay(1000L)
            rateLimitTick++
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = if (canRecoverPassword) "Set Password & Exit Essential Mode" else "Exit Essential Mode",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = when {
                        canRecoverPassword ->
                            "No password was saved after Strict Mode was turned off. Set a new password (at least 4 characters) to save it and pause Essential Mode until tomorrow's daily reset."
                        !hasPin ->
                            "Strict Mode currently has no password configured. Configure a Password / PIN in Settings first to exit Essential Mode."
                        else ->
                            "Enter your password to pause Essential Mode for the remainder of today. " +
                                    "Restrictions will automatically resume at tomorrow's daily reset."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isLockedOut) {
                    Text(
                        text = "Too many failed attempts. Locked out for $lockoutRemaining seconds.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (hasPin || canRecoverPassword) {
                    OutlinedTextField(
                        value = enteredPassword,
                        onValueChange = {
                            if (it.length <= 32) {
                                enteredPassword = it
                                passwordError = null
                            }
                        },
                        label = {
                            Text(if (canRecoverPassword) "New Password / PIN (4+ chars)" else "Password / PIN")
                        },
                        isError = passwordError != null,
                        supportingText = passwordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
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
                    if (canRecoverPassword) {
                        if (enteredPassword.length < 4) {
                            passwordError = "Password / PIN must be at least 4 characters."
                            return@Button
                        }
                        val recovered = essentialRepository.setRecoveryPasswordAndEmergencyExit(enteredPassword)
                        if (recovered) {
                            onExited()
                        } else {
                            passwordError = "Unable to save recovery password."
                        }
                        return@Button
                    }

                    if (!hasPin) {
                        passwordError = "No Password / PIN is configured in Strict Mode settings."
                        return@Button
                    }

                    if (essentialRepository.isRateLimited()) {
                        rateLimitTick++
                        return@Button
                    }
                    val success = essentialRepository.authenticateAndTriggerEmergencyExit(enteredPassword)
                    if (success) {
                        onExited()
                    } else {
                        rateLimitTick++
                        passwordError = if (essentialRepository.isRateLimited()) {
                            "Too many failed attempts. Locked out for ${essentialRepository.getLockoutRemainingSeconds()}s."
                        } else {
                            "Incorrect password."
                        }
                    }
                },
                enabled = !isLockedOut && (canRecoverPassword || hasPin) && enteredPassword.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(if (canRecoverPassword) "Save & Exit" else "Exit Essential Mode")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
