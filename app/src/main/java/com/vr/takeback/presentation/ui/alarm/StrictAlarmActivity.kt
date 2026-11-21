package com.vr.takeback.presentation.ui.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vr.takeback.domain.math.MathChallengeGenerator
import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import com.vr.takeback.ui.theme.TakeBackTheme

class StrictAlarmActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen and display above keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val label = intent.getStringExtra("EXTRA_ALARM_LABEL") ?: "Morning Focus"
        val difficultyStr = intent.getStringExtra("EXTRA_DIFFICULTY") ?: "MEDIUM"
        val difficulty = try {
            AlarmChallengeDifficulty.valueOf(difficultyStr)
        } catch (e: Exception) {
            AlarmChallengeDifficulty.MEDIUM
        }

        startAlarmAudioAndVibration()

        setContent {
            TakeBackTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StrictAlarmContent(
                        label = label,
                        difficulty = difficulty,
                        onSolved = {
                            stopAlarmAudioAndVibration()
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun startAlarmAudioAndVibration() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            // Audio fallback
        }
    }

    private fun stopAlarmAudioAndVibration() {
        try {
            ringtone?.stop()
            vibrator?.cancel()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmAudioAndVibration()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent dismissing alarm with back button
    }
}

@Composable
fun StrictAlarmContent(
    label: String,
    difficulty: AlarmChallengeDifficulty,
    onSolved: () -> Unit
) {
    var mathProblem by remember { mutableStateOf(MathChallengeGenerator.generateProblem(difficulty)) }
    var answerInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Alarm,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Badge(containerColor = MaterialTheme.colorScheme.error) {
            Text(
                text = "SOLVE MATH TO DISMISS",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onError
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Calculate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Challenge: ${difficulty.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = mathProblem.question,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedTextField(
                    value = answerInput,
                    onValueChange = {
                        answerInput = it
                        errorMessage = null
                    },
                    placeholder = { Text("Your answer") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (MathChallengeGenerator.verifyAnswer(answerInput, mathProblem.answer)) {
                            onSolved()
                        } else {
                            errorMessage = "Incorrect! Generated new challenge."
                            answerInput = ""
                            mathProblem = MathChallengeGenerator.generateProblem(difficulty)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Silence Alarm", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
