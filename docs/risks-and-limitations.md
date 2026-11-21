# Risks, Platform Limitations & Mitigation Strategies

This document provides a transparent engineering appraisal of technical risks, OS restrictions, and platform limitations encountered when developing a digital-wellbeing and distraction control app on modern Android.

---

## 1. Aggressive OEM Background Task Killing

### Risk Description
Manufacturers such as Xiaomi (MIUI/HyperOS), Samsung (OneUI), OnePlus/Oppo (ColorOS/OxygenOS), and Huawei implement aggressive proprietary battery-saving software that kills background services, discards pending `AlarmManager` alarms, and revokes foreground service states without warning.

### Severity: High
If TakeBack's background service is killed, active restrictions will temporarily fail to block apps until the user manually relaunches TakeBack.

### Mitigation Strategy
1. **Persistent Notification with Foreground Service:** Use `Service.startForeground()` with a small, high-priority ongoing notification (`PRIORITY_MIN` or `PRIORITY_LOW` on Android 13+) ensuring the process runs in `PROCESS_STATE_FOREGROUND_SERVICE`.
2. **Auto-Start & Battery Exemption Guide:** Implement dedicated, brand-specific deep link intents directly opening the manufacturer's specific settings page:
   - Xiaomi Autostart: `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`
   - Samsung Sleeping Apps: `com.samsung.android.lool`
   - OnePlus Battery Optimization: `com.coloros.safecenter`
3. **AlarmManager.setAlarmClock():** Use `setAlarmClock()` rather than `setExact()` for scheduled boundary changes. `setAlarmClock()` is treated as a high-priority user-visible alarm by Android OS and OEM power managers, guaranteeing wakeups even in deep Doze mode.
4. **Boot Completed Receiver:** Immediately restore all state upon device reboot.

---

## 2. Google Play Accessibility Service Policy Restrictions

### Risk Description
Google Play has strict policies governing the use of `AccessibilityService`. Apps using accessibility capabilities for purposes other than assisting users with disabilities or explicitly approved enterprise/wellbeing scenarios face rejection or removal if they do not provide prominent in-app disclosure and clear user consent. Furthermore, policies strictly forbid using Accessibility to block app uninstallation.

### Severity: Medium-High

### Mitigation Strategy
1. **Full Policy Compliance:**
   - Prominently display a full-screen, unskippable disclosure modal *before* invoking `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
   - Explicitly detail what data is processed (window package name, browser address bar text) and that zero personal data or passwords are ever accessed or transmitted.
2. **Zero Deceptive Practices:**
   - TakeBack will never attempt to hijack the system package installer or settings to block uninstallation via Accessibility events.
   - Strict Mode relies on psychological and voluntary friction (random text challenges, timed delay cooldowns) rather than malicious system locks.
3. **Modular Graceful Degradation:**
   - Accessibility is strictly optional. If the user denies Accessibility access, TakeBack remains fully functional using `UsageStatsManager` for app time tracking and limits, and local DNS for domain-level website blocking.

---

## 3. Dynamic UI Changes in Social Media Apps (Shorts / Reels)

### Risk Description
Selective blocking of YouTube Shorts, Instagram Reels, and Facebook Reels relies on inspecting the view hierarchy for specific resource IDs, text descriptions, or class names. When these third-party platforms update their client apps, view identifiers may change, temporarily breaking selective feed detection.

### Severity: Medium

### Mitigation Strategy
1. **Transparent User Expectation Setting:**
   - Clearly label the feature as "Experimental / Best-Effort Selective Content Filter".
   - Include an explanatory note informing users that social media updates may necessitate rapid pattern definition updates.
2. **Local Dynamic Pattern Definitions:**
   - Store UI identifiers (e.g., node content descriptions, view tags) in a configurable Room/DataStore structure rather than hardcoding them in code. This enables rapid updates without requiring code refactoring.
3. **Fallback to Application Level Block:**
   - Provide a toggle allowing users to optionally block the entire application if selective short-form filtering becomes temporarily unavailable.

---

## 4. HTTPS Website Filtering Limitations

### Risk Description
Because modern web traffic uses end-to-end TLS encryption (HTTPS), a local VPN service cannot inspect the HTTP path (e.g. `example.com/blocked-page`) without acting as an intrusive Man-in-the-Middle (MITM) proxy requiring the installation of an untrusted custom root certificate.

### Severity: Low-Medium

### Mitigation Strategy
1. **Clear Architectural Separation:**
   - **Domain-Level Blocking:** Enforced reliably for 100% of apps and browsers via lightweight on-device DNS resolution loopback (`127.0.0.1`).
   - **URL Path & Keyword Filtering:** Enforced exclusively in supported web browsers (Chrome, Samsung Internet, Brave, Firefox, Edge, Opera) via Accessibility text inspection of the browser address bar.
2. **No Fake Security Claims:**
   - TakeBack will never claim that local DNS can block arbitrary URL paths on HTTPS websites.
   - The user interface transparently distinguishes "Domain Blocking" from "Keyword / Path Blocking in Supported Browsers".

---

## 5. User Emergency Access & Personal Safety

### Risk Description
If an aggressive blocking app erroneously blocks the phone dialer, messaging, or emergency SOS services during a life-threatening crisis, the consequences can be catastrophic.

### Severity: Critical

### Mitigation Strategy
1. **Immutable Emergency Whitelist:**
   - The Central Restriction Engine maintains an unalterable, hardcoded whitelist containing:
     - `com.android.phone` (AOSP Dialer)
     - `com.google.android.dialer` (Google Phone)
     - `com.samsung.android.dialer` (Samsung Phone)
     - All packages handling `Intent.ACTION_CALL_PRIVILEGED`, `Intent.ACTION_EMERGENCY_ASSISTANCE`, and `Intent.ACTION_DIAL`
     - Emergency SOS and system Settings
2. **Absolute Priority in Decision Hierarchy:**
   - In the pure evaluation function, the Emergency Whitelist check occurs at Step 0, immediately short-circuiting all active profiles, strict modes, and Pomodoro sessions to guarantee 100% unimpeded emergency access.
