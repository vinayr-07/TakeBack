# TakeBack 🛡️

> **Reclaim Your Attention, Time, and Autonomy.**
> An ethically engineered, 100% on-device Android digital wellbeing, app & website blocking, and deep focus system built with Jetpack Compose and Clean Architecture.

[![Android](https://img.shields.io/badge/Platform-Android%2010%2B%20%28API%2029--35%29-3DDC84.svg?logo=android&logoColor=white)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20Material%203-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Unit%20%26%20Integration%20Tests-128%20Passing%20%28100%25%29-brightgreen.svg)]()
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Telemetry-blue.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

---

## 📥 Direct Download & Installation

The compiled debug APK is included directly in this repository:

- 📦 **Download APK:** [`release/TakeBack.apk`](release/TakeBack.apk)
- **Minimum Android Version:** Android 10 (API Level 29)
- **Target Android Version:** Android 15 / 16 (API Level 35+)
- **Verification:** 100% clean build, signed with debug keystore.

---

## 🌟 Key Capabilities

### 1. Unified Central Restriction Engine
- **Deterministic State Evaluation:** A pure Kotlin domain evaluation machine with zero Android framework dependencies. Evaluates an immutable `SystemContextSnapshot` against multi-profile rules with strict precedence.
- **Schedule-Based Restrictions:** Define start/end active windows with weekday masks. Fully supports **overnight schedules** spanning midnight (e.g. 22:00 Friday to 06:00 Saturday).
- **Multi-Tier Usage Quotas:** Granular enforcement supporting `FULL_DAY` daily allowances, `PER_HOUR` hourly quotas, and `PER_SESSION` continuous usage limits.
- **Emergency Unblockable Allowlist:** Critical communication apps (e.g. Phone Dialer, Emergency Contacts) are evaluated at Priority Level 0 and can never be blocked.

### 2. Website & Domain Filtering
- **Boundary Host Matching:** Replaces fragile substring checks with RFC-compliant host normalization (`extractHost`). Lookalike domains (`notinstagram.com`), path attacks (`safe.test/instagram.com`), and query strings remain permitted while genuine targets are blocked.
- **Rule Types:**
  - `EXACT_DOMAIN`: Matches exact domain (`instagram.com`).
  - `WILDCARD`: Matches base domain and all subdomains (`*.tiktok.com` matches `m.tiktok.com`, `video.tiktok.com`).
  - `KEYWORD`: URL text matching for sensitive content.
- **Allowlist Precedence:** Allowlisted websites take absolute priority over blocklists.

### 3. Strict Mode Anti-Tamper & Mutation Gate
- **StrictMutationGate:** Centralized security gate protecting all profile and setting modifications.
  - Generates single-use, cryptographically verified authorization tokens.
  - Requires **exact target-ID and nullability matching** (tokens issued for a specific profile cannot alter other profiles or global settings).
  - Enforces atomic single-use consumption and automatic expiration.
- **Fail-Closed State Integrity:** Preference or storage corruption automatically transitions to `StrictProtectionStatus.STORAGE_ERROR` (fail-closed), preventing bypasses during storage errors.
- **Standard Device Administrator Integration:** Mandates standard Android Device Administrator (`TakeBackDeviceAdminReceiver`) before arming Strict Mode. If deactivated, transitions to `COMPROMISED` / `INTERRUPTED` with transparent user recovery.

### 4. Selective Short-Form Content Filter
- **Shorts & Reels Blocker:** Detects short-form algorithmic video feeds (YouTube Shorts, Instagram Reels) using accessibility node tree inspection and navigates back (`GLOBAL_ACTION_BACK`) without blocking the entire application.

### 5. Deep Focus Sprints & Ambient Audio
- **Pomodoro Focus Timer:** Automated state machine managing Focus $\rightarrow$ Short Break $\rightarrow$ Long Break intervals.
- **Real-Time Procedural Audio Synthesis:** Real-time PCM audio generator using `AudioTrack` producing procedural Rain, White Noise, and Brown Noise with zero disk storage footprint.

### 6. Strict Alarm Clock
- **Math Challenge Dismissal:** Keyguard-waking full-screen alarm requiring randomized mathematical problem solving to dismiss, preventing early-morning doom-scrolling.

### 7. 100% Privacy & Screen-Time Analytics
- **Zero Cloud, Zero Telemetry:** All usage stats, timeline logs, and configurations remain 100% on-device. No network permissions requested or used.
- **Audit Timeline:** Chronological event feed logging app openings, block events, and profile toggles with automated 30-day retention pruning.

---

## 🏛️ System Architecture

TakeBack is built following **Clean Architecture** principles and Android Architecture Guidelines with unidirectional data flow (UDF):

```mermaid
flowchart TD
    subgraph PresentationLayer [Presentation Layer (Jetpack Compose)]
        Screens[Composable Screens: Home, Profiles, Focus, Insights, Settings]
        ViewModels[MVI / MVVM ViewModels: HomeViewModel, etc.]
        UiState[Immutable UiState & UiEvents]
    end

    subgraph DomainLayer [Domain Layer (Pure Kotlin - Zero Android Dependencies)]
        Engine[Central Restriction Engine]
        SecurityGate[StrictMutationGate]
        DomainModels[Profile, Schedule, UsageLimit, BlockDecision]
    end

    subgraph DataLayer [Data Layer (Room + DataStore)]
        Repos[ProfileRepository, StrictModeRepository, UsageStatsRepository]
        Storage[Local SQLite DB, Encrypted Preferences]
    end

    subgraph PlatformLayer [Platform & Enforcement Infrastructure Layer]
        AccService[TakeBackAccessibilityService]
        DeviceAdmin[TakeBackDeviceAdminReceiver]
        FgService[FocusTimerService & AudioPlaybackService]
        Alarms[ScheduleCoordinator & ExactAlarmScheduler]
    end

    PresentationLayer --> DomainLayer
    DataLayer --> DomainLayer
    PlatformLayer --> DomainLayer
    PlatformLayer --> DataLayer
```

---

## 🔒 Ethical Boundaries & Platform Compliance

1. **Standard Device Administrator Only:** TakeBack relies strictly on standard Android Device Administrator APIs (`BIND_DEVICE_ADMIN`). It does **not** require enterprise Device Owner provisioning, Samsung Knox private APIs, or root access.
2. **Google Play Accessibility Policy Compliance:** The Accessibility Service is used solely to observe foreground window transitions and inspect browser URL bars. TakeBack does not implement deceptive overlays, hijack system dialogs, or bypass user confirmation flows.
3. **Honest Warning & Voluntary Friction:** On Android, ordinary apps cannot prevent their own Device Admin deactivation. TakeBack respects this OS boundary: `onDisableRequested()` provides an honest warning, records the transition, marks protection as `COMPROMISED`, and offers a legitimate recovery path.

---

## 🧪 Testing & Verification

TakeBack features a comprehensive automated testing suite with **128 unit, integration, and system tests** passing at a **100% success rate**:

```powershell
# Run the complete test suite
.\gradlew.bat test

# Assemble the debug APK
.\gradlew.bat assembleDebug
```

### Verified Test Suites:
- `CentralRestrictionEngineTest`: Emergency allowlists, schedule intervals, 24-hour enforcement, allowlist priority.
- `StrictMutationGateTest`: Token generation, single-use destruction, exact target-ID matching, nullability protection, expiration.
- `SettingsProtectionManagerTest`: Zero false positives on null nodes, Samsung One UI classification, unrelated admin activation.
- `ScheduleCoordinatorTest`: Multi-day schedule calculations, Saturday morning reboot during overnight schedule.
- `WebShieldRemovalTest`: Domain host extraction, wildcard boundaries, keyword matching, and lookalike domain security (`notinstagram.com`).
- `EndToEndUsageFlowTest`: Multi-app usage quotas, hourly allowances, and `PER_SESSION` quota accuracy.

---

## 📂 Project Structure

```
TakeBack/
├── app/
│   ├── src/main/java/com/vr/takeback/
│   │   ├── data/            # Local SQLite database & repositories
│   │   ├── domain/          # Pure Kotlin entities, engine & security gates
│   │   │   ├── engine/      # CentralRestrictionEngine
│   │   │   ├── model/       # Profile, Schedule, BlockDecision
│   │   │   └── security/    # StrictMutationGate, SettingsProtectionManager
│   │   ├── platform/        # Android Services, Receivers & Alarms
│   │   │   ├── alarm/       # ScheduleCoordinator, ExactAlarmScheduler
│   │   │   ├── receiver/    # DeviceAdminReceiver, BootReceiver
│   │   │   └── service/     # TakeBackAccessibilityService
│   │   └── presentation/    # Jetpack Compose UI, ViewModels, Screens
│   └── src/test/            # 128 automated JUnit test suites
├── docs/                    # 15 detailed technical specification documents
│   ├── architecture.md
│   ├── feature-parity-matrix.md
│   ├── restriction-engine-specification.md
│   └── ...
├── release/
│   └── TakeBack.apk         # Compiled, signed Android APK artifact
├── build.gradle.kts         # Root Gradle build configuration
└── README.md                # Project documentation & overview
```

---

## 📄 License

This project is licensed under the Apache License 2.0. All trademarks, service names, and brand names belong to their respective owners.
