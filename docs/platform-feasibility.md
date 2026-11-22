# Platform Feasibility & Android API Analysis

This document provides an engineering evaluation of Android platform APIs (API 29 through API 35-37+), technical feasibility, Google Play Store policy constraints, and architectural solutions for **TakeBack**.

---

## 1. Foreground Application Detection & Blocking Mechanisms

### Technical Challenge
Android has progressively restricted background processes from inspecting running tasks. `ActivityManager.getRunningTasks()` was deprecated in API 21, and Android 10+ strictly prohibits background activities from starting without explicit user interaction or high-priority notifications.

### Feasibility Analysis of Available Approaches
| Mechanism | Latency | Permissions Required | Google Play Policy Risk | Reliability across OEMs | Decision |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **A. UsageStatsManager Polling** | 500ms - 1500ms | `PACKAGE_USAGE_STATS` | Very Low (Standard Wellbeing API) | High | **Adopted for analytics & limit tracking** |
| **B. AccessibilityService Window Events** | < 30ms | `BIND_ACCESSIBILITY_SERVICE` | Medium (Requires prominent disclosure & justification for wellbeing/filtering) | Very High (Instant event when window state changes) | **Adopted as primary real-time block detector** |
| **C. NotificationListenerService** | Event-driven (notifications only) | `BIND_NOTIFICATION_LISTENER_SERVICE` | Low (Permitted for notification management apps) | High | **Adopted for Module M (Notification Blocker)** |

### Hybrid Detection Architecture
TakeBack implements a **Hybrid Detection Architecture**:
1. When Accessibility Service is enabled, `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` fires immediately upon any package switch, providing instant (< 30ms) package and class identification.
2. If Accessibility is not granted, the system falls back to a low-overhead foreground polling loop using `UsageStatsManager.queryEvents()` every 800ms.
3. For the blocking shield:
   - **Preferred:** Full-Screen `SYSTEM_ALERT_WINDOW` overlay displayed over the blocked app.
   - **Fallback:** High-priority `BlockShieldActivity` launched with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP` combined with an immediate intent to `Intent.ACTION_MAIN, CATEGORY_HOME`.

---

## 2. Website Blocking: Local DNS vs Accessibility Inspection

### Technical Challenge
HTTPS encrypts all web traffic, including URL paths, headers, and query parameters. A third-party app cannot inspect the URL path of an HTTPS request at the network layer without an intrusive, security-compromising custom CA certificate.

### Feasibility Analysis
| Approach | Domain Blocking | URL Path / Keyword Blocking | Browser Compatibility | Battery / Latency Overhead | Google Play Feasibility | Decision |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Local VPN Loopback (`VpnService`)** | 100% of all domains | 0% (Cannot decrypt HTTPS without MITM CA) | 100% of all apps & browsers | Low CPU, medium memory for packet tun loopback | High, subject to VPN Policy | **Adopted for robust domain & adult content filtering** |
| **Accessibility Node Inspection** | 100% in supported browsers | 100% (Inspects address bar text in plain text) | Dependent on browser UI view IDs | Near zero CPU when not browsing | Requires prominent disclosure in Google Play console | **Adopted for URL path & keyword filtering** |

### Dual-Layer Web Blocking Strategy
TakeBack combines both techniques:
1. **Layer 1 (Network Level):** An on-device local DNS server intercepts port 53 DNS queries. Any query matching a blocked domain (e.g., `reddit.com`, or adult domain lists) resolves to `127.0.0.1` instantly, with zero battery drain and zero impact on other traffic.
2. **Layer 2 (Browser UI Level):** For supported browsers (Chrome, Samsung Internet, Brave, Firefox, Edge, Opera), the `AccessibilityService` extracts the visible text from the browser's URL address bar node. If a keyword rule matches (e.g. `shorts` or `gambling`), the service automatically clicks the browser's back button or redirects to a local safe page.

---

## 3. Strict Mode & Anti-Bypass Feasibility

### Google Play Policy & Android OS Constraints
- **Uninstallation Prevention:** Android does not allow ordinary third-party applications to make themselves impossible to uninstall. While legacy apps abused `DeviceAdminReceiver` with `DEVICE_ADMIN_DISABLE_REQUESTED`, Google Play strictly forbids apps from abusing device admin privileges to prevent legitimate user uninstallation unless they are designated Enterprise Mobility Management (EMM) or Parental Control apps.
- **Accessibility Abuse:** Google Play policy states: *Accessibility APIs must not be used to prevent users from disabling or uninstalling the app.*

### TakeBack's Ethical & Robust Strict Mode Architecture
1. **Voluntary Friction, Not Malware:** TakeBack provides psychological and procedural friction rather than OS hijack.
2. **Text Challenge:** Generates a randomized 100-character alphanumeric text string that must be typed manually (clipboard paste disabled).
3. **Timed Delay:** Enforces a 5 to 15-minute cooldown timer before any profile can be turned off or edited.
4. **Emergency Settings Protection:** If the user attempts to enter TakeBack's settings during an active strict window, TakeBack prompts for the strict mode PIN or challenge before rendering the configuration UI.
5. **Emergency Whitelist:** Telephony dialer, Emergency SOS, and SMS remain accessible at all times for user safety.

---

## 4. OEM Aggressive Background Killers & Doze Optimization

### Technical Challenge
OEM manufacturers (Xiaomi HyperOS/MIUI, OnePlus OxygenOS, Samsung OneUI, Huawei EMUI) frequently kill background services and ignore `AlarmManager` triggers to artificially boost battery benchmarks.

### Solutions Implemented in TakeBack
1. **Foreground Service with Persistent Notification:** TakeBack runs a lightweight foreground service with a quiet, ongoing notification displaying active focus state, ensuring high OOM priority (`PROCESS_STATE_FOREGROUND_SERVICE`).
2. **Battery Optimization Whitelist:** Prominently prompts the user to grant `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
3. **OEM-Specific Setup Guides:** Custom intent launchers directly open OEM autostart / background management settings:
   - Xiaomi: `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`
   - Samsung: `com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity`
   - OnePlus: `com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity`
4. **AlarmManager.setAlarmClock():** Used for strict alarms and schedule boundary transitions; this API bypasses Doze mode restrictions and is exempt from standard exact alarm throttles. Defensive `try-catch (e: SecurityException)` guards handle Android 12+ (API 31+) exact alarm restrictions, falling back gracefully to `setAndAllowWhileIdle` when exact alarm scheduling is not granted.

---

## 5. Shorts & Reels Content Blocker Feasibility

### Technical Feasibility Analysis
- **YouTube Shorts:** YouTube displays shorts in a distinct view container with specific accessibility resource IDs or content descriptions (`Shorts`, `shorts_container`). When detected, TakeBack injects `GLOBAL_ACTION_BACK`.
- **Instagram Reels / Stories:** Instagram uses distinct tab identifiers (`Reels`, `clips_tab`).
- **Multi-Window & Pop-up Floating Windows (Split-Screen & Freeform):** Android Window Manager and Samsung OneUI support simultaneous execution in split-screen and floating pop-up windows. TakeBack leverages `AccessibilityService.windows` with `flagRetrieveInteractiveWindows` and `typeWindowsChanged` event triggers to audit all visible interactive windows. When a restricted package is detected in any visible application window, TakeBack requests transient audio focus pause, injects `GLOBAL_ACTION_HOME`, and launches full-screen un-dockable `BlockShieldActivity` (`android:resizeableActivity="false"`).
- **Picture-in-Picture (PiP) Window & Background Audio Interception:** For video players and browsers running in PiP mode (`AccessibilityWindowInfo.isInPictureInPictureMode`), TakeBack automatically executes `AccessibilityNodeInfo.ACTION_DISMISS` on the PiP root node and triggers `AudioManager.requestAudioFocus` with `AUDIOFOCUS_GAIN_TRANSIENT` to immediately pause video audio.
- **OEM System App Bloatware Disqualification:** In Essential Mode, preinstalled OEM bloatware packages (Samsung Gallery, Samsung My Files, Samsung Video Player, Samsung Internet Browser, Google Photos) that carry `FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP` are disqualified via pattern and category analysis (categories 0–4), preventing them from bypassing focus enforcement.
- **Limitation & Disclosure:** Because proprietary third-party apps frequently change their layout resource IDs during updates, selective DOM element filters cannot guarantee 100% lifetime stability. TakeBack explicitly surfaces this technical reality to users and provides rapid-update pattern definitions stored in local DataStore.
