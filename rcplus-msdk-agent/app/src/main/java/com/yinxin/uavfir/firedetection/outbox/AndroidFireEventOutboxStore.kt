package com.yinxin.uavfir.firedetection.outbox

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AndroidFireEventOutboxStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    FireEventOutboxStore {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA busy_timeout=5000")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                event_id TEXT PRIMARY KEY NOT NULL,
                task_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                state TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                delivered_at INTEGER,
                last_error TEXT,
                last_failure_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_fire_event_outbox_due ON $TABLE_NAME(state, next_attempt_at)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    override fun enqueue(entry: FireEventOutboxEntry): Boolean {
        val values = ContentValues().apply {
            put("event_id", entry.eventId)
            put("task_id", entry.taskId)
            put("payload_json", entry.payloadJson)
            put("state", FireEventOutboxEntry.STATE_PENDING)
            put("attempt_count", 0)
            put("next_attempt_at", entry.nextAttemptAt)
            put("created_at", entry.createdAt)
            put("updated_at", entry.createdAt)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    @Synchronized
    override fun nextDue(nowMs: Long): FireEventOutboxEntry? {
        readableDatabase.query(
            TABLE_NAME,
            ENTRY_COLUMNS,
            "state = ? AND next_attempt_at <= ?",
            arrayOf(FireEventOutboxEntry.STATE_PENDING, nowMs.toString()),
            null,
            null,
            "next_attempt_at ASC, created_at ASC",
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toEntry() else null
        }
    }

    @Synchronized
    override fun markDelivered(eventId: String, deliveredAtMs: Long) {
        writableDatabase.update(
            TABLE_NAME,
            ContentValues().apply {
                put("state", FireEventOutboxEntry.STATE_DELIVERED)
                put("updated_at", deliveredAtMs)
                put("delivered_at", deliveredAtMs)
            },
            "event_id = ?",
            arrayOf(eventId),
        )
    }

    @Synchronized
    override fun markRetry(
        eventId: String,
        attemptCount: Int,
        nextAttemptAtMs: Long,
        error: String,
        failedAtMs: Long,
    ) {
        writableDatabase.update(
            TABLE_NAME,
            ContentValues().apply {
                put("state", FireEventOutboxEntry.STATE_PENDING)
                put("attempt_count", attemptCount)
                put("next_attempt_at", nextAttemptAtMs)
                put("updated_at", failedAtMs)
                put("last_error", error.take(MAX_ERROR_LENGTH))
                put("last_failure_at", failedAtMs)
            },
            "event_id = ?",
            arrayOf(eventId),
        )
    }

    @Synchronized
    override fun markRejected(eventId: String, attemptCount: Int, error: String, failedAtMs: Long) {
        writableDatabase.update(
            TABLE_NAME,
            ContentValues().apply {
                put("state", FireEventOutboxEntry.STATE_FAILED)
                put("attempt_count", attemptCount)
                put("updated_at", failedAtMs)
                put("last_error", error.take(MAX_ERROR_LENGTH))
                put("last_failure_at", failedAtMs)
            },
            "event_id = ?",
            arrayOf(eventId),
        )
    }

    @Synchronized
    override fun health(): FireEventOutboxHealth {
        var pendingCount = 0
        var oldestPendingAt: Long? = null
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(created_at) FROM $TABLE_NAME WHERE state <> ?",
            arrayOf(FireEventOutboxEntry.STATE_DELIVERED),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                pendingCount = cursor.getInt(0)
                oldestPendingAt = if (cursor.isNull(1)) null else cursor.getLong(1)
            }
        }
        val lastError = readableDatabase.rawQuery(
            "SELECT last_error FROM $TABLE_NAME WHERE last_error IS NOT NULL " +
                "ORDER BY last_failure_at DESC LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return FireEventOutboxHealth(pendingCount, oldestPendingAt, lastError)
    }

    @Synchronized
    override fun deleteDeliveredBefore(cutoffMs: Long) {
        writableDatabase.delete(
            TABLE_NAME,
            "state = ? AND delivered_at < ?",
            arrayOf(FireEventOutboxEntry.STATE_DELIVERED, cutoffMs.toString()),
        )
    }

    private fun Cursor.toEntry(): FireEventOutboxEntry = FireEventOutboxEntry(
        eventId = getString(getColumnIndexOrThrow("event_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        payloadJson = getString(getColumnIndexOrThrow("payload_json")),
        state = getString(getColumnIndexOrThrow("state")),
        attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
        nextAttemptAt = getLong(getColumnIndexOrThrow("next_attempt_at")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        lastError = getColumnIndexOrThrow("last_error").let { if (isNull(it)) null else getString(it) },
    )

    companion object {
        private const val DATABASE_NAME = "fire-event-outbox.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "fire_event_outbox"
        private const val MAX_ERROR_LENGTH = 500
        private val ENTRY_COLUMNS = arrayOf(
            "event_id",
            "task_id",
            "payload_json",
            "state",
            "attempt_count",
            "next_attempt_at",
            "created_at",
            "last_error",
        )
    }
}
