package com.vr.takeback

import com.vr.takeback.domain.math.MathChallengeGenerator
import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import org.junit.Assert.*
import org.junit.Test

class MathChallengeGeneratorTest {

    @Test
    fun easyProblem_generatesValidAddition() {
        for (i in 0 until 20) {
            val problem = MathChallengeGenerator.generateProblem(AlarmChallengeDifficulty.EASY)
            assertTrue("Problem must contain '+'", problem.question.contains("+"))
            assertTrue("Answer must be positive and between 20 and 100", problem.answer in 20..100)
            assertTrue("verifyAnswer must accept correct answer", MathChallengeGenerator.verifyAnswer(problem.answer.toString(), problem.answer))
        }
    }

    @Test
    fun mediumProblem_generatesValidArithmetic() {
        for (i in 0 until 20) {
            val problem = MathChallengeGenerator.generateProblem(AlarmChallengeDifficulty.MEDIUM)
            assertTrue("Problem must contain multiplication symbol", problem.question.contains("×"))
            assertTrue("verifyAnswer must validate correct result", MathChallengeGenerator.verifyAnswer(problem.answer.toString(), problem.answer))
        }
    }

    @Test
    fun hardProblem_generatesValidMultiplication() {
        for (i in 0 until 20) {
            val problem = MathChallengeGenerator.generateProblem(AlarmChallengeDifficulty.HARD)
            assertTrue("Problem must contain multiplication symbol", problem.question.contains("×"))
            assertTrue("Answer must be >= 165", problem.answer >= 165)
            assertTrue("verifyAnswer must validate correct result", MathChallengeGenerator.verifyAnswer(problem.answer.toString(), problem.answer))
        }
    }

    @Test
    fun verifyAnswer_rejectsInvalidAndNonNumericInput() {
        val expected = 42
        assertTrue(MathChallengeGenerator.verifyAnswer("42", expected))
        assertTrue(MathChallengeGenerator.verifyAnswer(" 42 ", expected)) // Whitespace tolerant
        assertFalse(MathChallengeGenerator.verifyAnswer("41", expected))
        assertFalse(MathChallengeGenerator.verifyAnswer("abc", expected))
        assertFalse(MathChallengeGenerator.verifyAnswer("", expected))
    }
}
