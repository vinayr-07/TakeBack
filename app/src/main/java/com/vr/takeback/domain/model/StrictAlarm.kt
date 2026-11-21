package com.vr.takeback.domain.model

data class StrictAlarm(
    val id: String,
    val hour: Int,       // 0 to 23
    val minute: Int,     // 0 to 59
    val daysOfWeekMask: Int = 127, // 1=Mon ... 64=Sun (127 = all 7 days)
    val isEnabled: Boolean = true,
    val difficulty: AlarmChallengeDifficulty = AlarmChallengeDifficulty.MEDIUM,
    val label: String = "Morning Focus"
) {
    val formattedTime: String
        get() {
            val period = if (hour >= 12) "PM" else "AM"
            val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            return String.format("%02d:%02d %s", h, minute, period)
        }
}

enum class AlarmChallengeDifficulty {
    EASY,    // 2-digit addition (e.g. 24 + 37)
    MEDIUM,  // Addition & multiplication (e.g. 14 x 6 + 19)
    HARD     // 2-digit multiplication (e.g. 27 x 18)
}
