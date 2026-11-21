# Non-Functional Requirements Specification (NFRS)

This document establishes the measurable quality attributes, technical constraints, security benchmarks, and reliability criteria for **TakeBack**.

---

## 1. Performance & Latency Requirements

| ID | Requirement Category | Specification | Verification Method |
| :--- | :--- | :--- | :--- |
| **NFR-PERF-001** | Foreground Block Latency | When a restricted application is launched, the restriction engine shall evaluate policy and display the block overlay within **< 50 milliseconds** on reference devices. | Automated benchmark using `Macrobenchmark` and Accessibility event timestamps. |
| **NFR-PERF-002** | App Cold Startup Time | Cold start to interactive home dashboard shall be **< 800 milliseconds** on mid-range Android hardware (e.g. Snapdragon 680 / 6GB RAM). | Android Vitals `TimeToInitialDisplay` measurement. |
| **NFR-PERF-003** | UI Frame Rate | All Jetpack Compose list scrolling, animations, and transitions shall maintain a steady **60 fps** (or 120 fps on high refresh displays) with < 1% dropped frames. | Perfetto trace analysis during deep profile list scroll. |
| **NFR-PERF-004** | Database Query Latency | All Room DAO queries for active profiles and schedule evaluation shall complete in **< 10 milliseconds**. | Room SQL execution logging and index validation. |

---

## 2. Power Consumption & Battery Efficiency

| ID | Requirement Category | Specification | Verification Method |
| :--- | :--- | :--- | :--- |
| **NFR-BAT-001** | Daily Background Battery Drain | The continuous background enforcement service, location geofences, and usage monitoring shall consume **< 2.0%** of total battery capacity over a 24-hour cycle. | Battery Historian analysis over 24-hour standard usage profile. |
| **NFR-BAT-002** | Doze Mode Compatibility | The system shall maintain scheduled restrictions without preventing the device from entering Android deep Doze mode when idle. | Verification that wakelocks are acquired only transiently (< 200ms) for exact alarm evaluations. |
| **NFR-BAT-003** | Geofence Optimization | Location-based restrictions shall exclusively use `GeofencingClient` hardware-assisted geofences, with zero continuous GPS polling. | Code audit ensuring no continuous `requestLocationUpdates` calls are made. |

---

## 3. Reliability, Resilience & Recovery

| ID | Requirement Category | Specification | Verification Method |
| :--- | :--- | :--- | :--- |
| **NFR-REL-001** | Boot Persistence | Following a device reboot, all active restriction profiles, ongoing Pomodoro timers, and exact alarms shall be restored within **15 seconds** of `BOOT_COMPLETED` broadcast. | Automated boot test verifying state restoration in Room DB. |
| **NFR-REL-002** | Process Termination Recovery | If the background enforcement service is killed by the Android low-memory killer (LMK) or OEM task killer, it shall be automatically recreated by the system via `START_STICKY` foreground service contract. | Automated `kill -9` process kill test in Android emulator. |
| **NFR-REL-003** | Midnight & Timezone Rollover | The restriction engine shall update daily usage limits, hourly quotas, and active weekday schedules at 00:00:00 local time and upon `TIMEZONE_CHANGED` broadcasts with zero missed blocks. | Clock manipulation unit tests covering DST transitions and midnight rollover. |
| **NFR-REL-004** | Crash-Free Session Rate | The application shall maintain a **> 99.9%** crash-free session rate under continuous usage. | Static analysis and extensive automated regression test suite. |

---

## 4. Security & Privacy Benchmarks

| ID | Requirement Category | Specification | Verification Method |
| :--- | :--- | :--- | :--- |
| **NFR-SEC-001** | Zero Remote Telemetry | No user usage data, visited URLs, installed app inventories, or location coordinates shall ever be transmitted over the network. | Network traffic inspection during 100% of app workflows (zero unauthorized socket connections). |
| **NFR-SEC-002** | Strict Mode Secrets Protection | Strict mode PINs and passwords shall be salted with CSPRNG entropy and hashed with PBKDF2/SHA-256 before storage in `EncryptedSharedPreferences` backed by the Android Keystore. | Security code audit and memory dump analysis. |
| **NFR-SEC-003** | Emergency Bypass Safeguard | Emergency numbers (e.g. 911, 112, 999) and the native Telephony phone dialer shall remain 100% accessible under all strict modes and restrictions. | Automated test asserting emergency package exclusion from all blocking decisions. |
| **NFR-SEC-004** | Local Data Encryption | All exported user archives and sensitive preference keys shall be encrypted at rest. | File system inspection on rooted test device. |

---

## 5. Usability, Accessibility & Design

| ID | Requirement Category | Specification | Verification Method |
| :--- | :--- | :--- | :--- |
| **NFR-USE-001** | Accessibility Standards | All UI elements shall conform to WCAG 2.1 Level AA standards, including a minimum color contrast ratio of 4.5:1 and minimum touch target size of 48x48 dp. | Accessibility Scanner audit on all Compose screens. |
| **NFR-USE-002** | Screen Reader Support | All icons, graphs, and interactive widgets shall provide clear, descriptive `contentDescription` attributes for Android TalkBack. | Manual TalkBack navigation walkthrough across all primary flows. |
| **NFR-USE-003** | Dynamic Theming & Dark Mode | The UI system shall fully support system Dark Mode, Light Mode, and Android 12+ Material You Dynamic Color palettes. | Visual validation in both light and dark themes. |

---

## 6. Software Architecture & Maintainability

| ID | Requirement Category | Specification | Verification Method |
| :--- | :--- | :--- | :--- |
| **NFR-ARC-001** | Clean Architecture Separation | Presentation, Domain, Data, and Platform layers shall be strictly decoupled with unidirectional dependencies. The domain layer shall contain zero Android framework imports. | ArchUnit / Gradle module dependency boundary validation. |
| **NFR-ARC-002** | Test Coverage | The core policy evaluation engine and business domain logic shall achieve **>= 90%** automated unit test line coverage. | JaCoCo test coverage report. |
| **NFR-ARC-003** | iOS Portability Abstraction | All restriction models and policy evaluation algorithms shall be abstracted behind platform-agnostic interfaces to facilitate future Swift/ScreenTime porting. | Interface audit in domain package. |
