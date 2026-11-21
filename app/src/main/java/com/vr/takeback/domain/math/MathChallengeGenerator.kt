package com.vr.takeback.domain.math

import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import java.util.Random

data class MathProblem(
    val question: String,
    val answer: Int
)

object MathChallengeGenerator {

    private val random = Random()

    fun generateProblem(difficulty: AlarmChallengeDifficulty): MathProblem {
        return when (difficulty) {
            AlarmChallengeDifficulty.EASY -> {
                val a = random.nextInt(40) + 10 // 10..49
                val b = random.nextInt(40) + 10 // 10..49
                MathProblem("$a + $b = ?", a + b)
            }
            AlarmChallengeDifficulty.MEDIUM -> {
                val a = random.nextInt(12) + 3  // 3..14
                val b = random.nextInt(8) + 4   // 4..11
                val c = random.nextInt(30) + 10 // 10..39
                MathProblem("($a × $b) + $c = ?", (a * b) + c)
            }
            AlarmChallengeDifficulty.HARD -> {
                val a = random.nextInt(30) + 15 // 15..44
                val b = random.nextInt(20) + 11 // 11..30
                MathProblem("$a × $b = ?", a * b)
            }
        }
    }

    fun verifyAnswer(input: String, expected: Int): Boolean {
        val parsed = input.trim().toIntOrNull() ?: return false
        return parsed == expected
    }
}
