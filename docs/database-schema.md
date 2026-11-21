# Database Schema & Entity Relational Design

## 1. Schema Overview & Relational Architecture

**TakeBack** utilizes Android Room (SQLite) for structured local storage. The schema is organized around a central `ProfileEntity` with relational tables representing specific blocking constraints, schedules, and historical analytics.

```mermaid
erDiagram
    PROFILES ||--o{ PROFILE_TARGET_APPS : "restricts"
    PROFILES ||--o{ PROFILE_TARGET_WEBSITES : "restricts"
    PROFILES ||--o{ PROFILE_SCHEDULES : "governed by"
    PROFILES ||--o{ PROFILE_USAGE_LIMITS : "limited by"
    PROFILES ||--o{ PROFILE_GEOFENCES : "triggered by"
    PROFILES ||--o| PROFILE_STRICT_CONFIG : "protected by"
    
    ACTIVITY_TIMELINE_EVENTS {
        long id PK
        string eventType
        string targetIdentifier
        string profileId FK
        long timestampMillis
        string extraMetadata
    }
    
    APP_USAGE_SNAPSHOTS {
        long id PK
        string packageName
        string dateString
        long totalForegroundTimeMillis
        int launchCount
        long lastTimeUsedMillis
    }
```

---

## 2. Table Definitions & Room Entities

### Table 1: `profiles`
The primary entity representing a user restriction profile.
```sql
CREATE TABLE IF NOT EXISTS `profiles` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `name` TEXT NOT NULL,
    `icon_name` TEXT NOT NULL,
    `color_hex` TEXT NOT NULL,
    `is_enabled` INTEGER NOT NULL DEFAULT 1,
    `is_preset` INTEGER NOT NULL DEFAULT 0,
    `created_at_millis` INTEGER NOT NULL,
    `updated_at_millis` INTEGER NOT NULL
);
```

### Table 2: `profile_target_apps`
Junction table linking restricted or whitelisted applications to a profile.
```sql
CREATE TABLE IF NOT EXISTS `profile_target_apps` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `profile_id` TEXT NOT NULL,
    `package_name` TEXT NOT NULL,
    `app_name` TEXT NOT NULL,
    `is_allowlist` INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (`profile_id`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_profile_target_apps_profile_id` ON `profile_target_apps`(`profile_id`);
CREATE INDEX IF NOT EXISTS `index_profile_target_apps_package_name` ON `profile_target_apps`(`package_name`);
```

### Table 3: `profile_target_websites`
Blocked domains, wildcard expressions, and keywords per profile.
```sql
CREATE TABLE IF NOT EXISTS `profile_target_websites` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `profile_id` TEXT NOT NULL,
    `domain_pattern` TEXT NOT NULL,
    `rule_type` TEXT NOT NULL, -- 'EXACT_DOMAIN', 'WILDCARD', 'KEYWORD'
    `is_allowlist` INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (`profile_id`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_profile_target_websites_profile_id` ON `profile_target_websites`(`profile_id`);
```

### Table 4: `profile_schedules`
Recurring weekly or daily time schedules.
```sql
CREATE TABLE IF NOT EXISTS `profile_schedules` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `profile_id` TEXT NOT NULL,
    `days_of_week_mask` INTEGER NOT NULL, -- Bitmask: 1=Mon, 2=Tue, 4=Wed, 8=Thu, 16=Fri, 32=Sat, 64=Sun
    `start_time_seconds` INTEGER NOT NULL, -- Seconds from midnight (0 to 86399)
    `end_time_seconds` INTEGER NOT NULL,   -- Seconds from midnight (0 to 86399)
    `is_enabled` INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (`profile_id`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_profile_schedules_profile_id` ON `profile_schedules`(`profile_id`);
```

### Table 5: `profile_usage_limits`
Daily quotas and sliding hourly limits.
```sql
CREATE TABLE IF NOT EXISTS `profile_usage_limits` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `profile_id` TEXT NOT NULL,
    `target_package_name` TEXT, -- NULL denotes shared pool limit for all profile apps
    `daily_limit_minutes` INTEGER NOT NULL,
    `window_type` TEXT NOT NULL, -- 'FULL_DAY', 'PER_HOUR', 'PER_SESSION'
    `is_enabled` INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (`profile_id`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_profile_usage_limits_profile_id` ON `profile_usage_limits`(`profile_id`);
```

### Table 6: `profile_geofences`
Location triggers mapped to profiles.
```sql
CREATE TABLE IF NOT EXISTS `profile_geofences` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `profile_id` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `latitude` REAL NOT NULL,
    `longitude` REAL NOT NULL,
    `radius_meters` REAL NOT NULL,
    `trigger_on_enter` INTEGER NOT NULL DEFAULT 1,
    `trigger_on_exit` INTEGER NOT NULL DEFAULT 0,
    `is_enabled` INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (`profile_id`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_profile_geofences_profile_id` ON `profile_geofences`(`profile_id`);
```

### Table 7: `profile_strict_config`
Strict Mode lock parameters per profile.
```sql
CREATE TABLE IF NOT EXISTS `profile_strict_config` (
    `profile_id` TEXT NOT NULL PRIMARY KEY,
    `is_strict_active` INTEGER NOT NULL DEFAULT 0,
    `mechanism` TEXT NOT NULL, -- 'PIN', 'TIMED_LOCK', 'TEXT_CHALLENGE', 'DELAY'
    `salt_base64` TEXT,
    `password_hash` TEXT,
    `locked_until_epoch_millis` INTEGER,
    `delay_minutes` INTEGER DEFAULT 5,
    FOREIGN KEY (`profile_id`) REFERENCES `profiles`(`id`) ON DELETE CASCADE
);
```

### Table 8: `activity_timeline_events`
Local-first activity log for audit and timeline visualization.
```sql
CREATE TABLE IF NOT EXISTS `activity_timeline_events` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `event_type` TEXT NOT NULL, -- 'APP_OPENED', 'APP_BLOCKED', 'PROFILE_ACTIVATED', 'FOCUS_START', 'BREAK_START'
    `target_identifier` TEXT NOT NULL,
    `profile_id` TEXT,
    `timestamp_millis` INTEGER NOT NULL,
    `extra_metadata` TEXT
);
CREATE INDEX IF NOT EXISTS `index_activity_events_timestamp` ON `activity_timeline_events`(`timestamp_millis`);
```

---

## 3. Migration & Pruning Strategies

- **Automated Retention Pruning:** A periodic WorkManager task runs nightly to delete records from `activity_timeline_events` older than 30 days:
  ```sql
  DELETE FROM activity_timeline_events WHERE timestamp_millis < :thirtyDaysAgo;
  ```
- **Schema Evolution:** Room `autoMigrations` are enabled with explicit `Migration(from, to)` classes for table alterations to ensure zero user data loss across updates.
