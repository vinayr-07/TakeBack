package com.vr.takeback.presentation.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.vr.takeback.data.repository.AlarmRepository
import com.vr.takeback.data.repository.FocusSessionRepository
import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import com.vr.takeback.domain.model.FocusSessionState
import com.vr.takeback.domain.model.StrictAlarm
import com.vr.takeback.platform.audio.FocusAudioPlayer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    focusSessionRepository: FocusSessionRepository,
    alarmRepository: AlarmRepository
) {
    val context = LocalContext.current
    val session by focusSessionRepository.session.collectAsState()
    val alarms by alarmRepository.alarms.collectAsState()
    val audioPlayer = remember { FocusAudioPlayer(context) }
    var selectedSound by remember { mutableStateOf<String?>(null) }
    var isSoundPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.stopSound()
        }
    }

    // Simulated countdown timer when running
    var remainingSeconds by remember { mutableStateOf(session.focusDurationMinutes * 60) }

    LaunchedEffect(session.state) {
        if (session.state == FocusSessionState.FOCUS_RUNNING) {
            while (remainingSeconds > 0 && session.state == FocusSessionState.FOCUS_RUNNING) {
                delay(1000L)
                remainingSeconds--
            }
            if (remainingSeconds == 0) {
                focusSessionRepository.transitionToBreak()
                remainingSeconds = session.breakDurationMinutes * 60
            }
        } else if (session.state == FocusSessionState.IDLE) {
            remainingSeconds = session.focusDurationMinutes * 60
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val formattedTime = String.format("%02d:%02d", minutes, seconds)
    val totalSeconds = if (session.state == FocusSessionState.BREAK_RUNNING) {
        session.breakDurationMinutes * 60
    } else {
        session.focusDurationMinutes * 60
    }
    val progress = 1f - (remainingSeconds.toFloat() / totalSeconds.toFloat().coerceAtLeast(1f))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Focus & Pomodoro",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Timer Card with Circular Progress
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Badge(
                            containerColor = if (session.state == FocusSessionState.BREAK_RUNNING) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Text(
                                text = when (session.state) {
                                    FocusSessionState.IDLE -> "READY TO FOCUS"
                                    FocusSessionState.FOCUS_RUNNING -> "DEEP SPRINT ACTIVE"
                                    FocusSessionState.BREAK_RUNNING -> "RECOVERY BREAK"
                                    FocusSessionState.PAUSED -> "SESSION PAUSED"
                                    FocusSessionState.COMPLETED -> "CYCLES COMPLETE"
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (session.state == FocusSessionState.BREAK_RUNNING) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Large Digital Clock Display
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(220.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 10.dp,
                                color = if (session.state == FocusSessionState.BREAK_RUNNING) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Cycle ${session.currentCycle} of ${session.totalCycles}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // Controls: Play/Pause/Stop
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedIconButton(
                                onClick = {
                                    focusSessionRepository.stopSession()
                                    remainingSeconds = session.focusDurationMinutes * 60
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(24.dp))
                            }

                            FilledIconButton(
                                onClick = {
                                    if (session.state == FocusSessionState.FOCUS_RUNNING) {
                                        focusSessionRepository.pauseSession()
                                    } else {
                                        focusSessionRepository.startSession()
                                    }
                                },
                                modifier = Modifier.size(68.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = if (session.state == FocusSessionState.FOCUS_RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "Toggle",
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            OutlinedIconButton(
                                onClick = {
                                    focusSessionRepository.transitionToBreak()
                                    remainingSeconds = session.breakDurationMinutes * 60
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "Skip to Break", modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }

            // Ambient Focus Sounds
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
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
                                Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Ambient Soundscape",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (isSoundPlaying) {
                                TextButton(onClick = {
                                    audioPlayer.stopSound()
                                    isSoundPlaying = false
                                    selectedSound = null
                                }) {
                                    Text("Mute", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Calming ambient soundscapes playing locally in background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        val sounds = listOf("Rain", "Ocean Waves", "Forest Wind", "Stream", "Brown Noise", "White Noise")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sounds) { sound ->
                                FilterChip(
                                    selected = selectedSound == sound && isSoundPlaying,
                                    onClick = {
                                        if (selectedSound == sound && isSoundPlaying) {
                                            audioPlayer.stopSound()
                                            isSoundPlaying = false
                                            selectedSound = null
                                        } else {
                                            selectedSound = sound
                                            audioPlayer.playSound(sound)
                                            isSoundPlaying = true
                                        }
                                    },
                                    label = { Text(sound) },
                                    leadingIcon = if (selectedSound == sound && isSoundPlaying) {
                                        { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Strict Alarm Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Strict Wake-Up Alarms",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    text = "ANTI-SNOOZE",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Requires solving a mathematical puzzle before audio can be silenced.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        alarms.forEach { alarm ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = alarm.formattedTime,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${alarm.label} • ${alarm.difficulty.name} Challenge",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Switch(
                                    checked = alarm.isEnabled,
                                    onCheckedChange = { alarmRepository.toggleAlarm(alarm.id) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
