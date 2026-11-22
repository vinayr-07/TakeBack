# Privacy, Security & Anti-Tamper Model

## 1. Zero-Trust Local-First Privacy Architecture

**TakeBack** is built on an uncompromising local-first data privacy model. In an era where digital wellbeing applications frequently collect sensitive device telemetry, app inventories, and browsing history for behavioral advertising or cloud analytics, TakeBack enforces the following architectural guarantees:

```mermaid
flowchart TD
    subgraph DeviceBoundary ["Protected Device Boundary - 100% On-Device"]
        AppUsage["UsageStats / App Screen Time"]
        BrowserURL["Extracted URL / Address Bar"]
        Geofence["GPS Coordinates / Geofence Cache"]
        StrictPIN["PIN and Protection Secrets"]
        RoomDB[("Local SQLite Room DB")]
        DataStore[("Encrypted Preferences")]
        
        AppUsage --> RoomDB
        BrowserURL --> TransientMemory["Transient String Matcher"]
        TransientMemory -->|Discard immediately| MemoryClean["Cleared Memory"]
        Geofence --> RoomDB
        StrictPIN --> DataStore
    end
    
    subgraph ExternalWorld ["External World / Network"]
        Cloud["Third-Party Trackers and Cloud Servers"]
    end
    
    DeviceBoundary -.x|ZERO Network Sockets Opened| Cloud
```

### Absolute Privacy Guarantees:
1. **Zero External Sockets:** The core restriction engine, analytics repository, and timeline database contain zero network calls. Visited domains and application package names are never transmitted off the device.
2. **Transient URL Processing:** When the accessibility service inspects a browser address bar, the string is evaluated in transient memory against the local blocklist. Visited URLs are never written to permanent disk storage unless a blocking violation occurs (and then only the domain is recorded locally in the user's private timeline).
3. **Storage Access Framework:** Data export is handled exclusively via Android's native system file picker (`Intent.ACTION_CREATE_DOCUMENT`), placing exported files under the direct custody of the user.

---

## 2. Cryptographic Security & Credential Protection

### Strict Mode Credential Storage
To prevent users from circumventing active Strict Mode restrictions by inspecting local app files:
1. **No Plaintext PINs:** Strict mode PINs are never stored in plaintext.
2. **Key Derivation:** When a PIN or password is set, the system generates a 128-bit cryptographically secure random salt using `java.security.SecureRandom`.
3. **PBKDF2-HMAC-SHA256:** The PIN is hashed using PBKDF2 with 10,000 iterations:
   $$\text{Hash} = \text{PBKDF2WithHmacSHA256}(\text{PIN}, \text{Salt}, 10000, 256)$$
4. **Android Keystore Integration:** The salt and hash are stored in `EncryptedSharedPreferences` backed by the hardware-backed Android Keystore Master Key (`MasterKeys.AES256_GCM_SPEC`).

---

## 3. Anti-Tamper & Bypass Resistance Mechanics

While complying fully with Google Play policies (which prohibit preventing normal app uninstallation or hijacking device admin privileges), TakeBack provides psychological and procedural anti-tamper mechanisms:

| Attack Vector / Bypass Attempt | TakeBack Defense Mechanism | Compliance & Ethical Assessment |
| :--- | :--- | :--- |
| **User impulsively tries to disable profile** | Configured Strict Mode Challenge: Randomized 100-character text challenge without clipboard paste, or mandatory 10-minute cooldown delay. | 100% Policy-compliant; leverages deliberate friction to break impulsive dopamine loops. |
| **User changes system clock to bypass schedule** | Restriction engine cross-references `SystemClock.elapsedRealtime()` (monotonic hardware timer that cannot be manipulated by manual clock changes) against `System.currentTimeMillis()`. | Detects clock jumps and maintains restrictions. |
| **User kills background process via Recent Apps** | Foreground service runs with `START_STICKY` and an ongoing low-priority notification; the OS automatically restarts the service within seconds. | Standard Android service resilience contract. |
| **Device restarts / phone reboot** | `BootCompletedReceiver` registered with `RECEIVE_BOOT_COMPLETED` immediately re-evaluates active profile database records and arms exact alarms within seconds of boot. | Certified platform behavior. |
| **Emergency access need** | Native Telephony phone dialer, Emergency SOS (911/112), and essential system settings remain permanently accessible on the unblockable allowlist. | Essential safety requirement. |
