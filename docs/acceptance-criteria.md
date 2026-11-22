# Acceptance Criteria (BDD Given-When-Then Specification)

This document specifies the exact Behavior-Driven Development (BDD) acceptance criteria for **TakeBack**. These criteria define the completion standard for automated unit, integration, and UI tests.

---

## Scenario 1: Scheduled App Blocking Activation
```gherkin
Feature: Scheduled Application Blocking
  As a student
  I want my distracting applications blocked during my study schedule
  So that I can focus on my university coursework

  Scenario: Scheduled start time reached for active profile
    Given a profile named "Study" is enabled
    And "com.instagram.android" is in the restricted apps list for "Study"
    And a schedule exists for Monday from 14:00 to 18:00
    And the current day is Monday and the current time is 14:00:01
    When the user attempts to launch "com.instagram.android"
    Then the Central Restriction Engine evaluates the state as BLOCKED
    And the reason is "ScheduleActive"
    And the BlockShield overlay is displayed on the screen
    And the overlay displays "Study in Progress" and remaining time until 18:00
```

---

## Scenario 2: Daily Usage Limit Exhaustion & Enforcement
```gherkin
Feature: Daily Cumulative Usage Limit
  As a knowledge worker
  I want a daily limit of 30 minutes on recreational apps
  So that I do not waste excessive time during the work day

  Scenario: Usage exceeds 30 minutes
    Given a profile named "Work" is enabled
    And "com.reddit.frontpage" has a daily limit of 30 minutes
    And the accumulated usage for "com.reddit.frontpage" is 29 minutes and 59 seconds
    When the user uses "com.reddit.frontpage" for an additional 2 seconds
    Then the accumulated usage reaches 30 minutes and 1 second
    And the Central Restriction Engine immediately changes the decision to BLOCKED
    And the reason is "DailyLimitExceeded"
    And the BlockShield overlay is displayed over Reddit
    And the remaining usage quota is displayed as "0 minutes remaining"
    And the block remains enforced until 00:00:00 midnight reset
```

---

## Scenario 3: Midnight Rollover & Daily Limit Reset
```gherkin
Feature: Midnight Limit Reset
  As a user with exhausted daily limits
  I want my allowances to reset automatically at midnight
  So that I have fresh allowances for the new day

  Scenario: Clock passes midnight
    Given "com.reddit.frontpage" was BLOCKED due to daily limit exhaustion
    When the device clock transitions from 23:59:59 to 00:00:00
    Then the daily usage counter for the new calendar date is initialized to 0 minutes
    And the Central Restriction Engine re-evaluates the restriction
    And "com.reddit.frontpage" becomes ALLOWED (assuming no overnight schedule applies)
```

---

## Scenario 4: Overnight Schedule Continuous Enforcement
```gherkin
Feature: Overnight Restriction Schedule
  As a user seeking better sleep
  I want social media blocked from 22:30 at night until 06:30 in the morning
  So that I am not disturbed before sleeping or immediately upon waking

  Scenario: Launching restricted app at 01:15 AM
    Given a profile named "Sleep" has an overnight schedule from 22:30 to 06:30
    And "com.twitter.android" is on the blocked list
    And the current time is 01:15 AM
    When the user opens "com.twitter.android"
    Then the Central Restriction Engine identifies 01:15 as within the normalized overnight interval
    And the decision is BLOCKED
    And the BlockShield displays time remaining until 06:30
```

---

## Scenario 5: Multi-Profile Conflict Resolution (Union Rule)
```gherkin
Feature: Multi-Profile Union Rule
  As a user with multiple overlapping profiles
  I want the strictest restriction to apply
  So that my focus is not compromised by secondary profiles

  Scenario: App is blocked in Profile A but not in Profile B
    Given Profile "Work" is ACTIVE (blocking "com.youtube.android")
    And Profile "Evening" is ACTIVE (allowing "com.youtube.android")
    When the user attempts to launch "com.youtube.android"
    Then the Central Restriction Engine evaluates the union of all active profiles
    And the decision is BLOCKED because Profile "Work" mandates restriction

---

## Scenario 6: Strict Mode Protected Quick Block Cancellation
```gherkin
Feature: Strict Quick Block Cancellation
  As a user committed to deep focus
  I want my active Quick Block detox session protected by Strict Mode
  So that I cannot impulsively cancel my block during moments of weak discipline

  Scenario: User taps Stop on active Quick Block while Strict Mode is armed
    Given an active Quick Block of 30 minutes is running
    And Strict Mode is armed with a PIN challenge
    When the user taps "Stop" on the Quick Block dashboard card
    Then the system displays the StrictChallengeDialog
    And the Quick Block timer continues running in the background
    When the user completes the PIN verification successfully
    Then a one-time CANCEL_QUICK_BLOCK mutation token is issued and consumed
    And the Quick Block is cleanly disarmed and settled
```

---

## Scenario 7: Cold-Boot State Restoration
```gherkin
Feature: Cold-Boot State Resilience
  As a user whose phone reboots or terminates apps under memory pressure
  I want my active Quick Block timers and custom alarms restored
  So that protection never silently drops

  Scenario: Device reboots while Quick Block is active
    Given a 30-minute Quick Block was initiated at 14:00:00 (target: 14:30:00)
    And the device is turned off and rebooted at 14:10:00
    When the system sends the ACTION_BOOT_COMPLETED broadcast
    Then BootCompletedReceiver triggers state restoration
    And QuickBlockRepository calculates elapsed time as 10 minutes
    And QuickBlock resumes with exactly 20 minutes remaining
    And active Strict Alarms are re-registered with system AlarmManager
```
```

---

## Scenario 6: Strict Mode PIN Challenge Verification
```gherkin
Feature: Strict Mode Protection
  As an impulsive user
  I want my settings protected by a Strict Mode PIN
  So that I cannot cancel my blocking rules during a moment of weakness

  Scenario: Correct PIN entered
    Given Strict Mode is ARMED with a secure 6-digit PIN
    When the user attempts to delete or disable the "Study" profile
    Then the system intercepts the request and displays the Strict Mode PIN Challenge
    When the user enters the correct 6-digit PIN
    Then the system grants a temporary 10-minute unlocked maintenance window
    And the user is permitted to edit the profile
```

---

## Scenario 7: Pomodoro Focus Session & Break Whitelist
```gherkin
Feature: Pomodoro Session with Temporary Break
  As a student
  I want a 5-minute break where only messaging is allowed
  So that I can check important messages without opening social media

  Scenario: Focus session transitions to break
    Given a 25-minute focus session completes
    And the user initiates a 5-minute break
    And "com.whatsapp" is in the Break Whitelist
    And "com.instagram.android" is NOT in the Break Whitelist
    When the user opens "com.whatsapp"
    Then the decision is ALLOWED
    When the user opens "com.instagram.android"
    Then the decision is BLOCKED
    When the 5-minute break timer expires
    Then WhatsApp access is immediately revoked and the next focus session begins
```

---

## Scenario 8: Emergency Allowlist Invariance
```gherkin
Feature: Emergency Allowlist Safety Guarantee
  As any user in an emergency
  I want the phone dialer and emergency services to never be blocked under any circumstances
  So that my personal safety is never compromised

  Scenario: Emergency call during active Strict Mode and all-app lock
    Given all profiles are active and Strict Mode is armed
    And an all-apps restriction is currently active
    When the user launches the native phone dialer ("com.google.android.dialer")
    Or dials an emergency number (e.g. 911 / 112)
    Then the Central Restriction Engine identifies the package as an Emergency Whitelist entity
    And the decision is ALLOWED with zero delay
```
