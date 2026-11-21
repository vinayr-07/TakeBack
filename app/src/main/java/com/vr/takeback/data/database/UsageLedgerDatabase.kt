package com.vr.takeback.data.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.vr.takeback.domain.usage.UsageDataQuality
import java.time.LocalDate

data class PersistedAppRecord(
    val dateIso: String,
    val packageName: String,
    val appLabel: String,
    val foregroundDurationMillis: Long,
    val reconstructedSessionCount: Int,
    val lastTimeUsedMillis: Long,
    val dataSource: String,
    val qualityStatus: UsageDataQuality,
    val queryTimestamp: Long,
    val lastSyncEventTimestamp: Long,
    val deviceTimezone: String
) {
    @Deprecated("Renamed to reconstructedSessionCount", ReplaceWith("reconstructedSessionCount"))
    val launchCount: Int get() = reconstructedSessionCount
}

data class PersistedDeviceRecord(
    val dateIso: String,
    val deviceInteractiveMillis: Long,
    val totalTrackedAppMillis: Long,
    val dataSource: String,
    val qualityStatus: UsageDataQuality,
    val queryTimestamp: Long,
    val lastSyncEventTimestamp: Long,
    val deviceTimezone: String,
    val isFinalized: Boolean
)

/**
 * Robust, lightweight, native SQLite persistent ledger for durable usage statistics.
 *
 * Stores immutable finalized past-day summaries and idempotent intraday checkpoints.
 * Ensures that when Android's UsageEvents buffer expires (> 7-10 days), historical
 * data remains available with full traceability.
 */
class UsageLedgerDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val TAG = "UsageLedgerDatabase"
        const val DATABASE_NAME = "takeback_usage_ledger.db"
        const val DATABASE_VERSION = 2

        const val TABLE_DEVICE_LEDGER = "daily_device_ledger"
        const val TABLE_APP_LEDGER = "daily_app_usage_ledger"

        /**
         * Defect 6: Sentinel value written to device_interactive_ms when interactive time
         * is genuinely unknown / unavailable. Must never be shown to the user as 0.
         */
        const val DEVICE_INTERACTIVE_UNAVAILABLE_SENTINEL = Long.MIN_VALUE

        private const val CREATE_TABLE_DEVICE_LEDGER = """
            CREATE TABLE IF NOT EXISTS $TABLE_DEVICE_LEDGER (
                date_iso TEXT PRIMARY KEY,
                device_interactive_ms INTEGER NOT NULL,
                total_tracked_app_ms INTEGER NOT NULL,
                data_source TEXT NOT NULL,
                quality_status TEXT NOT NULL,
                query_timestamp INTEGER NOT NULL,
                last_sync_event_timestamp INTEGER NOT NULL,
                device_timezone TEXT NOT NULL,
                is_finalized INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_TABLE_APP_LEDGER = """
            CREATE TABLE IF NOT EXISTS $TABLE_APP_LEDGER (
                date_iso TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                foreground_duration_ms INTEGER NOT NULL,
                reconstructed_session_count INTEGER NOT NULL DEFAULT 0,
                last_time_used_ms INTEGER NOT NULL DEFAULT 0,
                data_source TEXT NOT NULL,
                quality_status TEXT NOT NULL,
                query_timestamp INTEGER NOT NULL,
                last_sync_event_timestamp INTEGER NOT NULL,
                device_timezone TEXT NOT NULL,
                PRIMARY KEY (date_iso, package_name)
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_DEVICE_LEDGER)
        db.execSQL(CREATE_TABLE_APP_LEDGER)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_app_date ON $TABLE_APP_LEDGER(date_iso)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_APP_LEDGER")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DEVICE_LEDGER")
            onCreate(db)
        }
    }

    /**
     * Idempotently saves or updates a daily usage ledger entry in a single atomic transaction.
     * Prevents overwriting a finalized record with an unfinalized or partial snapshot.
     * Returns true on success, false on error.
     */
    fun persistDailyUsage(
        deviceRecord: PersistedDeviceRecord,
        appRecords: List<PersistedAppRecord>
    ): Boolean {
        val existing = getDeviceRecord(LocalDate.parse(deviceRecord.dateIso))
        if (existing != null && existing.isFinalized && !deviceRecord.isFinalized) {
            Log.w(TAG, "Prevented overwrite of finalized record for ${deviceRecord.dateIso} with non-finalized record")
            return false
        }

        val db = writableDatabase
        db.beginTransaction()
        return try {
            // 1. Insert or update device interactive record
            val deviceValues = ContentValues().apply {
                put("date_iso", deviceRecord.dateIso)
                put("device_interactive_ms", deviceRecord.deviceInteractiveMillis)
                put("total_tracked_app_ms", deviceRecord.totalTrackedAppMillis)
                put("data_source", deviceRecord.dataSource)
                put("quality_status", deviceRecord.qualityStatus.name)
                put("query_timestamp", deviceRecord.queryTimestamp)
                put("last_sync_event_timestamp", deviceRecord.lastSyncEventTimestamp)
                put("device_timezone", deviceRecord.deviceTimezone)
                put("is_finalized", if (deviceRecord.isFinalized) 1 else 0)
            }
            db.insertWithOnConflict(
                TABLE_DEVICE_LEDGER,
                null,
                deviceValues,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            // 2. Clear old app records for this date
            db.delete(TABLE_APP_LEDGER, "date_iso = ?", arrayOf(deviceRecord.dateIso))

            // 3. Insert fresh app records
            for (app in appRecords) {
                val appValues = ContentValues().apply {
                    put("date_iso", app.dateIso)
                    put("package_name", app.packageName)
                    put("app_label", app.appLabel)
                    put("foreground_duration_ms", app.foregroundDurationMillis)
                    put("reconstructed_session_count", app.reconstructedSessionCount)
                    put("last_time_used_ms", app.lastTimeUsedMillis)
                    put("data_source", app.dataSource)
                    put("quality_status", app.qualityStatus.name)
                    put("query_timestamp", app.queryTimestamp)
                    put("last_sync_event_timestamp", app.lastSyncEventTimestamp)
                    put("device_timezone", app.deviceTimezone)
                }
                db.insertWithOnConflict(
                    TABLE_APP_LEDGER,
                    null,
                    appValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist daily usage for ${deviceRecord.dateIso}", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Reads the persisted daily device record for a given date if present.
     * Defect 6: Long.MIN_VALUE stored in device_interactive_ms is interpreted as "unavailable"
     * (not genuine 0). Callers should check PersistedDeviceRecord.deviceInteractiveMillis against
     * DEVICE_INTERACTIVE_UNAVAILABLE_SENTINEL.
     */
    fun getDeviceRecord(date: LocalDate): PersistedDeviceRecord? {
        val db = readableDatabase
        val dateIso = date.toString()
        var cursor: Cursor? = null
        return try {
            cursor = db.query(
                TABLE_DEVICE_LEDGER,
                null,
                "date_iso = ?",
                arrayOf(dateIso),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                PersistedDeviceRecord(
                    dateIso = cursor.getString(cursor.getColumnIndexOrThrow("date_iso")),
                    deviceInteractiveMillis = cursor.getLong(cursor.getColumnIndexOrThrow("device_interactive_ms")),
                    totalTrackedAppMillis = cursor.getLong(cursor.getColumnIndexOrThrow("total_tracked_app_ms")),
                    dataSource = cursor.getString(cursor.getColumnIndexOrThrow("data_source")),
                    qualityStatus = try {
                        UsageDataQuality.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("quality_status")))
                    } catch (e: Exception) {
                        UsageDataQuality.UNKNOWN
                    },
                    queryTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("query_timestamp")),
                    lastSyncEventTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("last_sync_event_timestamp")),
                    deviceTimezone = cursor.getString(cursor.getColumnIndexOrThrow("device_timezone")),
                    isFinalized = cursor.getInt(cursor.getColumnIndexOrThrow("is_finalized")) == 1
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query device record for $dateIso", e)
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Reads all persisted app usage records for a given date.
     */
    fun getAppRecords(date: LocalDate): List<PersistedAppRecord> {
        val db = readableDatabase
        val dateIso = date.toString()
        val list = mutableListOf<PersistedAppRecord>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                TABLE_APP_LEDGER,
                null,
                "date_iso = ?",
                arrayOf(dateIso),
                null,
                null,
                "foreground_duration_ms DESC"
            )
            while (cursor.moveToNext()) {
                list.add(
                    PersistedAppRecord(
                        dateIso = cursor.getString(cursor.getColumnIndexOrThrow("date_iso")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                        appLabel = cursor.getString(cursor.getColumnIndexOrThrow("app_label")),
                        foregroundDurationMillis = cursor.getLong(cursor.getColumnIndexOrThrow("foreground_duration_ms")),
                        reconstructedSessionCount = cursor.getInt(cursor.getColumnIndexOrThrow("reconstructed_session_count")),
                        lastTimeUsedMillis = cursor.getLong(cursor.getColumnIndexOrThrow("last_time_used_ms")),
                        dataSource = cursor.getString(cursor.getColumnIndexOrThrow("data_source")),
                        qualityStatus = try {
                            UsageDataQuality.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("quality_status")))
                        } catch (e: Exception) {
                            UsageDataQuality.UNKNOWN
                        },
                        queryTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("query_timestamp")),
                        lastSyncEventTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("last_sync_event_timestamp")),
                        deviceTimezone = cursor.getString(cursor.getColumnIndexOrThrow("device_timezone"))
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query app records for $dateIso", e)
        } finally {
            cursor?.close()
        }
        return list
    }
}
