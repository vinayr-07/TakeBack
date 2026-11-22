# Functional Requirements Specification (FRS)

## 1. Document Overview
This document specifies the complete, unambiguous functional requirements for **TakeBack**. Every requirement is identified with a unique, testable identifier conforming to standard IEEE 830-1998 guidelines.

---

## 2. Module Specifications

### Module A: Onboarding & Permission Management
- **FR-ONB-001:** The system shall present a multi-step onboarding carousel on first launch explaining the value proposition of digital wellbeing and distraction control.
- **FR-ONB-002:** The system shall request permissions incrementally at the point of functional need rather than in a batch on first launch.
- **FR-ONB-003:** The system shall provide a dedicated Permission Center displaying the live grant state of:
  - Usage Access (`PACKAGE_USAGE_STATS`)
  - Overlay Display (`SYSTEM_ALERT_WINDOW`)
  - Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`)
  - Notification Listener (`BIND_NOTIFICATION_LISTENER_SERVICE`)
  - Notification Post (`POST_NOTIFICATIONS`)
  - Geofence / Location Access (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`)
  - Battery Optimization Exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
  - Exact Alarm Scheduling (`SCHEDULE_EXACT_ALARM`)
- **FR-ONB-004:** The system shall provide direct one-tap navigation to the corresponding Android Settings screen for any ungranted permission.
- **FR-ONB-005:** The system shall detect permission revocations upon application resumption and immediately update active enforcement capabilities without crashing.

### Module B: Application Blocker
- **FR-APP-001:** The system shall discover all user-installed applications via `PackageManager` and display them with their localized display name and application icon.
- **FR-APP-002:** The system shall exclude critical system packages (Default Phone Dialer, Emergency SOS, Settings, Android System SystemUI, and TakeBack itself) from being accidentally blockable.
- **FR-APP-003:** The system shall allow users to search, multi-select, and categorize target applications into custom groups.
- **FR-APP-004:** The system shall detect foreground application switches within 50 milliseconds using a hybrid detection architecture combining `AccessibilityService` window-change events and `UsageStatsManager` polling deltas.
- **FR-APP-005:** When a restricted application is brought to the foreground, the system shall immediately present a non-bypassable full-screen `BlockShieldActivity` or system overlay.
- **FR-APP-006:** The `BlockShieldActivity` shall display the blocked application name, the triggering profile name, the specific reason (schedule, limit exhausted, or strict mode), the remaining duration of restriction, and a clean action to return to the Android Home launcher.

### Module C: Website & Content Blocker
- **FR-WEB-001:** The system shall support domain-level blocking (e.g., `reddit.com`, `instagram.com`) across all documented major Android browsers (Google Chrome, Samsung Internet, Brave, Opera, Microsoft Edge, Firefox, Kiwi, Vivaldi).
- **FR-WEB-002:** The system shall support wildcard subdomain blocking patterns (e.g., `*.tiktok.com`).
- **FR-WEB-003:** The system shall support keyword-in-URL filtering (e.g. blocking any URL containing `casino`, `gambling`, or `shorts`).
- **FR-WEB-004:** The system shall provide an offline, pre-compiled adult-content domain filter without transmitting visited URLs to any remote server.
- **FR-WEB-005:** When a restricted website is navigated to in a supported browser, the system shall either intercept the DNS resolution locally (via loopback VPN) or navigate the browser tab back / display the block screen via Accessibility view interaction.
- **FR-WEB-006:** The system shall maintain a website allowlist that overrides general category or domain block rules.

### Module D: Profile Management
- **FR-PRF-001:** The system shall support the creation, editing, duplication, and deletion of an unlimited number of custom restriction profiles.
- **FR-PRF-002:** The system shall pre-populate default starter profiles: *Study*, *Work*, *Sleep*, and *Personal*.
- **FR-PRF-003:** Each profile shall allow independent association of:
  - Custom profile name, icon identifier, and theme color.
  - Selected target application package names.
  - Selected target website domain names and keywords.
  - Weekly schedules (days of week, start time, end time).
  - Daily cumulative usage quotas (in minutes).
  - Sliding time-window limits (e.g., max 15 minutes per hour).
  - Geofenced physical locations.
  - Notification suppression rules.
  - Strict mode configuration.
- **FR-PRF-004:** The system shall support manual one-tap activation and deactivation of any profile, subject to active Strict Mode constraints.

### Module E: Central Restriction Engine
- **FR-ENG-001:** The restriction engine shall be implemented as a pure Kotlin domain evaluation component that receives an immutable snapshot of:
  - Active profiles and their associated rules
  - Current timestamp and day of week
  - Current foreground application package name
  - Current active browser URL / domain (if applicable)
  - Accumulated daily usage metrics for the application
  - Geofence residency state
  - Focus session state
  - Strict mode lock state
- **FR-ENG-002:** The engine shall output a deterministic `BlockDecision` enum: `Allowed` or `Blocked(reason, profileId, remainingDurationMillis)`.
- **FR-ENG-003:** When multiple profiles are active simultaneously, the engine shall evaluate the union of blocked resources (i.e. if any active profile mandates a block, the resource is blocked).
- **FR-ENG-004:** Emergency allowlist entries shall have absolute precedence over all blocking rules.
- **FR-ENG-005:** The engine shall correctly handle overnight schedules that span across midnight (e.g., 22:00 to 06:00) without gaps or false dismissals.
- **FR-ENG-006:** The engine shall support goal-based restrictions, where recreational applications remain blocked until a user achieves a configured minimum duration of productive application usage (e.g. 60 minutes in Duolingo).

### Module F: Strict Mode
- **FR-STR-001:** The system shall support locking active restrictions using one of four configurable mechanisms:
  - 4 to 6-digit numeric PIN
  - Timed expiration lock (immutable until specified target date and time)
  - Complex randomized text challenge (typing 100+ random characters without paste support)
  - Timed cooldown delay (requiring a 5 to 15-minute wait before changes take effect)
- **FR-STR-002:** While Strict Mode is active, the system shall prevent deleting, pausing, or editing restricted profiles, adding allowlist entries, or modifying schedules.
- **FR-STR-003:** The system shall require the user to complete the configured challenge before accessing TakeBack settings.
- **FR-STR-004:** The system shall provide an explicit, transparent warning modal before arming Strict Mode, detailing the consequences and confirming the user's informed consent.

### Module G: Quick Block & Digital Detox
- **FR-QCK-001:** The system shall provide a one-tap Quick Block widget from the Home dashboard allowing instant restriction of all distracting applications for a selectable duration (15m, 30m, 1h, 2h, 4h).
- **FR-QCK-002:** The system shall provide a Digital Detox mode that restricts all non-whitelisted communication apps for up to 48 hours.
- **FR-QCK-003:** Quick Block and Digital Detox shall execute through the unified Central Restriction Engine without duplicating enforcement logic.
- **FR-QCK-004:** When Strict Mode is armed, cancelling or prematurely dismissing an active Quick Block session shall require solving the configured Strict Mode verification challenge (`CANCEL_QUICK_BLOCK`).
- **FR-QCK-005:** Active Quick Block timers shall persist across application restarts, Low Memory Killer (LMK) process terminations, and device reboots, automatically recalculating elapsed time.

### Module H: Pomodoro & Focus Sessions
- **FR-POM-001:** The system shall include an integrated Pomodoro timer supporting custom focus durations (default: 25 mins) and short/long break durations (default: 5 mins / 15 mins).
- **FR-POM-002:** Starting a focus session shall automatically activate the designated focus profile and enforce application/website restrictions.
- **FR-POM-003:** During scheduled breaks, the system shall temporarily unlock designated break-whitelisted applications.
- **FR-POM-004:** When a break period expires, the system shall immediately re-engage full restrictions with an auditory alert.
- **FR-POM-005:** The focus timer state, cycle count, and whitelist apps shall persist across application restarts and background process recreation using atomic SharedPreferences records.

### Module I: Focus Ambient Sounds
- **FR-SND-001:** The system shall provide an offline, procedural ambient sound synthesizer generating at least: Rain, Ocean Waves, Forest Wind, Bubbling Stream, Brown Noise, and White Noise in real-time via PCM `AudioTrack` with zero external audio assets.
- **FR-SND-002:** The system shall support multi-track audio mixing, allowing users to play up to three simultaneous ambient layers with individual volume sliders.
- **FR-SND-003:** Audio playback shall continue in the background with the device screen off via an Android `MediaSession` compatible foreground service.
- **FR-SND-004:** The audio service shall respect system audio focus, pausing or ducking automatically during phone calls or user-initiated media playback.

### Module J: Screen-Time Analytics & Reports
- **FR-ANL-001:** The system shall query `UsageStatsManager` to aggregate daily, weekly, and monthly screen time totals per application and per category.
- **FR-ANL-002:** The system shall compute hourly distribution histograms showing usage intensity across the 24 hours of each day.
- **FR-ANL-003:** The system shall record and display the count of application launches and blocked access attempts.
- **FR-ANL-004:** The system shall clearly indicate when usage data is partial or incomplete due to missing system permissions.

### Module K: Activity Timeline
- **FR-TML-001:** The system shall maintain an append-only local timeline of significant wellbeing events:
  - Application opened / closed
  - Access blocked (with target app and active profile)
  - Profile activated / deactivated
  - Focus session started / completed / aborted
  - Daily limit reached
- **FR-TML-002:** The system shall automatically prune timeline records older than 30 days to bound local storage growth.

### Module L: Shorts & Reels Content Blocker
- **FR-SHR-001:** The system shall provide selective blocking options for: YouTube Shorts, Instagram Reels, and Facebook Reels.
- **FR-SHR-002:** When selective blocking is enabled, the system shall identify short-form UI containers using Accessibility node inspection and execute a back navigation without terminating the host application.
- **FR-SHR-003:** The system shall explicitly label this feature as dependent on third-party application UI layouts, and provide graceful fallback without crashing if a host app modifies its view hierarchy.

### Module M: Notification Blocker
- **FR-NOT-001:** The system shall intercept and suppress incoming push notifications from restricted applications during active restriction windows using `NotificationListenerService`.
- **FR-NOT-002:** The system shall preserve critical system notifications, emergency calls, and incoming telephony alerts under all circumstances.

### Module N: Strict Alarm
- **FR-ALM-001:** The system shall provide an alarm clock capability requiring the user to solve a mathematical challenge (e.g. addition, multiplication of varying difficulty) before the alarm audio can be silenced.
- **FR-ALM-002:** The alarm shall trigger using exact alarms (`AlarmManager.setAlarmClock()`) with full-screen intent priority to ensure reliability in deep sleep (Doze mode).

### Module O: Adult Content & Keyword Filter
- **FR-FLT-001:** The system shall maintain an offline, local list of adult content domains and suppress access when enabled in any profile.
- **FR-FLT-002:** The system shall allow users to specify custom keywords; any web page URL containing a flagged keyword shall be blocked.

### Module P: Settings & System Management
- **FR-SET-001:** The system shall provide a Battery Optimization troubleshooting guide tailored to major OEM manufacturers (Samsung, Xiaomi, OnePlus, Huawei).
- **FR-SET-002:** The system shall support complete local data export in standard JSON format via Android Storage Access Framework.
- **FR-SET-003:** The system shall provide an irrevocable "Delete All Data" action that wipes all Room tables and DataStore preferences instantly.

### Module Q: Premium & Monetization Architecture
- **FR-MON-001:** The system shall implement an abstraction layer for Google Play Billing v7+ supporting Free, Monthly, Yearly, and Lifetime tiers.
- **FR-MON-002:** Core safety features, emergency allowlists, and basic app blocking shall remain permanently free and unpaywalled.
