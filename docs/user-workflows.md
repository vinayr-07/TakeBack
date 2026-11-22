# User Workflows & Journey Architecture

This document defines the core user journeys, state transitions, interaction models, and edge-case recoveries for **TakeBack**.

---

## Workflow 1: First-Time Onboarding & Progressive Permission Escalation

```mermaid
flowchart TD
    Start([User Installs & Launches TakeBack]) --> W1[Welcome & Mission: Reclaim Your Attention]
    W1 --> W2[Philosophy: 100% Local, Zero Telemetry]
    W2 --> P1{Request Usage Access?}
    P1 -->|Grant| P1_OK[Usage Access Enabled]
    P1 -->|Skip/Deny| P1_WARN[Show Degraded Functionality Notice]
    P1_OK --> P2{Request Overlay Permission?}
    P2 -->|Grant| P2_OK[Overlay Alert Window Enabled]
    P2 -->|Skip/Deny| P2_FALLBACK[Task-Switching Fallback Activated]
    P2_OK --> P3{Request Accessibility Access?}
    P3 -->|Explain Justification| P3_USER[User Confirms Need for Website/Shorts Blocking]
    P3_USER --> P3_SYSTEM[Navigate to System Accessibility Settings]
    P3_SYSTEM --> P3_OK[Accessibility Verified]
    P3_OK --> W_FINISH[Generate Default Profiles: Study, Work, Sleep]
    W_FINISH --> HomeDashboard([Enter Main Home Dashboard])
```

### Key Principles of Onboarding:
1. **Never dump permissions all at once:** Permissions are grouped logically by capability.
2. **Usage Access is Tier 1:** Required for basic screen time analytics and app launch limits.
3. **Overlay Access is Tier 2:** Required for instantaneous, polite blocking shields over restricted apps.
4. **Accessibility is Tier 3:** Only requested if the user explicitly opts into website filtering or selective short-form video blocking.
5. **No Wall of Refusal:** If a user denies a permission, TakeBack gracefully disables only the affected sub-features, providing clear status pills in the Permission Center.

---

## Workflow 2: Creating a Restriction Profile (e.g. "Deep Study")

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as Profiles Screen / Compose
    participant VM as ProfileViewModel
    participant Repo as ProfileRepository
    participant Engine as RestrictionEngine
    participant Alarm as ScheduleAlarmManager

    User->>UI: Tap "+ New Profile"
    UI->>User: Display Profile Builder (Name, Icon, Color)
    User->>UI: Enter "Deep Study", select Indigo, select Books icon
    User->>UI: Select Target Apps (Instagram, TikTok, YouTube, Reddit)
    User->>UI: Configure Schedule (Mon-Fri, 14:00 - 18:00)
    User->>UI: Configure Daily Limit (Total 30 mins recreational allowance)
    User->>UI: Add Blocked Websites ("reddit.com", "x.com", "*.gaming.com")
    User->>UI: Tap "Save Profile"
    UI->>VM: saveProfile(profile, rules, schedule, limits)
    VM->>Repo: insertProfileWithRelations()
    Repo-->>VM: Profile ID generated
    VM->>Engine: reevaluateRestrictions()
    VM->>Alarm: scheduleNextWindowAlarms(profile.id)
    Alarm-->>User: Schedule registered with OS exact alarms
    VM-->>UI: Navigation back to Profiles list with success snackbar
```

---

## Workflow 3: Application Blocking & Enforcement Interaction

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant TargetApp as Instagram (Blocked App)
    participant Detector as HybridDetector (Accessibility/UsageStats)
    participant Engine as Central RestrictionEngine
    participant Shield as BlockShieldActivity / Overlay
    participant DB as EventTimelineDB

    User->>TargetApp: User taps Instagram app icon
    TargetApp->>TargetApp: Activity comes to foreground
    Detector->>Detector: Detect package "com.instagram.android" in foreground
    Detector->>Engine: evaluateForegroundPackage("com.instagram.android")
    Engine->>Engine: Check active profiles, schedules, and daily limits
    Note over Engine: Result: BLOCKED (Profile "Deep Study", Schedule Active)
    Engine-->>Detector: BlockDecision.Blocked(reason="Deep Study Active", remainingTime=0)
    Detector->>Shield: Launch BlockShield (Flag_ACTIVITY_NEW_TASK, CLEAR_TOP)
    Shield->>User: Display Calm Material 3 Shield UI: "Deep Study in Progress"
    Shield->>User: Show motivating quote, time remaining until 18:00
    Shield->>User: Allowed Actions: "Go to Home Screen", "Take 5m Break (if enabled)"
    Detector->>DB: Log ActivityEvent(APP_BLOCKED, "com.instagram.android", timestamp)
    User->>Shield: Taps "Go Home"
    Shield->>User: Navigates cleanly to Android Launcher
```

---

## Workflow 4: Strict Mode Arming & Challenge-Based Unlocking

```mermaid
flowchart TD
    SM_START([User Configures Strict Mode]) --> SM_SELECT{Choose Protection Mechanism}
    SM_SELECT -->|PIN / Password| SM_PIN[Set 4-6 Digit Secure PIN]
    SM_SELECT -->|Timed Lock| SM_TIME[Select Lock Until Date/Time]
    SM_SELECT -->|Text Challenge| SM_TXT[Configure 100-character complex text challenge]
    SM_SELECT -->|Emergency Delay| SM_DELAY[Set 5-minute cooldown timer before unlocking]
    
    SM_PIN & SM_TIME & SM_TXT & SM_DELAY --> SM_WARN[Display Solemn Warning: Restrictions cannot be easily canceled]
    SM_WARN --> SM_CONFIRM[Explicit Dual-Check Confirmation]
    SM_CONFIRM --> SM_ACTIVE[Strict Mode Armed & Encrypted in DataStore]
    
    SM_ACTIVE --> USER_EDIT[User Attempts to Edit Profile or Disable Restriction]
    SM_ACTIVE --> USER_QB[User Attempts to Stop Active Quick Block]
    USER_EDIT & USER_QB --> EVAL_STRICT{Is Strict Mode Active?}
    EVAL_STRICT -->|Yes| CHALLENGE[Present Configured Challenge UI]
    
    CHALLENGE --> VALIDATE{User Solves Challenge?}
    VALIDATE -->|No / Cancel| ABORT[Operation Canceled, Restrictions remain enforced]
    VALIDATE -->|Yes| UNLOCK_TEMP[Single-Use Mutation Token Issued and Consumed]
    UNLOCK_TEMP --> USER_EDIT_OK[Modification or Quick Block Dismissal Approved]
```

---

## Workflow 5: Pomodoro Focus Session & Take-a-Break Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Idle: User on Focus Tab
    Idle --> FocusSprint: User selects 25m Focus / 5m Break & taps "Start"
    
    state FocusSprint {
        [*] --> SprintActive
        SprintActive: Engine enforces strict focus restrictions
        SprintActive: Ambient sound plays (optional Rain/Brown noise)
        SprintActive: Notification blocker silences selected apps
        SprintActive --> SprintComplete: 25 minutes elapsed
    }
    
    FocusSprint --> PromptBreak: Chime alert sounds
    
    state PromptBreak {
        [*] --> AwaitingUserDecision
        AwaitingUserDecision --> BreakActive: User confirms break
        AwaitingUserDecision --> NextSprint: User skips break
    }
    
    state BreakActive {
        [*] --> BreakRunning
        BreakRunning: Temporary Break Whitelist active
        BreakRunning: User can access selected communication apps
        BreakRunning --> BreakExpired: 5 minutes elapsed
    }
    
    BreakActive --> NextSprint: Break concludes
    NextSprint --> FocusSprint: Auto-advance or user confirmation
    FocusSprint --> SessionTerminated: User ends session
    BreakActive --> SessionTerminated: User ends session
    SessionTerminated --> Idle: Log session statistics to Room DB
```

---

## Workflow 6: Strict Alarm Wake-Up Workflow

```mermaid
flowchart TD
    AlarmTrigger([AlarmManager Triggers at 06:30]) --> FullScreen[Launch StrictAlarmActivity via Full-Screen Intent]
    FullScreen --> PlaySound[Play High-Priority Alarm Tone over Audio Manager]
    FullScreen --> PresentChallenge[Display Challenge UI: Math Puzzle or Memory Game]
    
    PresentChallenge --> UserSolve[User Enters Answer: e.g. 34 x 17 = 578]
    UserSolve --> CheckAnswer{Is Answer Correct?}
    
    CheckAnswer -->|Incorrect| VibrateError[Haptic feedback, generate new problem, volume persists]
    CheckAnswer -->|Correct| SilenceAlarm[Silence Audio Stream]
    
    SilenceAlarm --> LogSuccess[Record Alarm Dismissal in Timeline]
    LogSuccess --> ShowMorningGreeting[Display Morning Productivity Summary]
    ShowMorningGreeting --> FinishAlarm([Exit Activity cleanly])
```
