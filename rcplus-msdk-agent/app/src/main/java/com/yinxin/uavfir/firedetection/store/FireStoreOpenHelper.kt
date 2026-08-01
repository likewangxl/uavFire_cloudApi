package com.yinxin.uavfir.firedetection.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FireStoreOpenHelper(
    context: Context,
    name: String = FireStoreContract.DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context, name, null, FireStoreContract.SCHEMA_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fire_session (
                session_id TEXT NOT NULL PRIMARY KEY,
                event_id TEXT NOT NULL UNIQUE,
                drone_sn TEXT NOT NULL,
                task_id TEXT NOT NULL,
                source_generation INTEGER NOT NULL,
                coordinator_generation INTEGER NOT NULL,
                roi_left REAL NOT NULL,
                roi_top REAL NOT NULL,
                roi_right REAL NOT NULL,
                roi_bottom REAL NOT NULL,
                recovery_proof_version INTEGER,
                recovery_proof_payload TEXT,
                recovery_proof_sha256 TEXT,
                state TEXT NOT NULL,
                detection_kind TEXT NOT NULL,
                confidence REAL NOT NULL,
                policy_version TEXT NOT NULL,
                model_version TEXT NOT NULL,
                model_hash TEXT NOT NULL,
                input_size INTEGER NOT NULL,
                runtime TEXT NOT NULL,
                initial_request_id TEXT NOT NULL,
                terminal_request_id TEXT,
                pending_terminal_request_id TEXT,
                pending_location_status TEXT,
                pending_geo_method TEXT,
                location_status TEXT NOT NULL,
                geo_method TEXT,
                created_at_wall_ms INTEGER NOT NULL,
                updated_at_wall_ms INTEGER NOT NULL,
                UNIQUE(session_id, event_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE fire_evidence (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                report_sequence INTEGER NOT NULL,
                path TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                media_type TEXT NOT NULL,
                captured_at_wall_ms INTEGER NOT NULL,
                byte_size INTEGER NOT NULL,
                width_pixels INTEGER NOT NULL,
                height_pixels INTEGER NOT NULL,
                rotation_degrees INTEGER NOT NULL,
                UNIQUE(event_id, report_sequence, path),
                FOREIGN KEY(session_id, event_id)
                    REFERENCES fire_session(session_id, event_id) ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE report_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                event_timestamp_wall_ms INTEGER NOT NULL,
                state TEXT NOT NULL,
                payload TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_elapsed_ms INTEGER NOT NULL,
                monotonic_epoch TEXT NOT NULL,
                lease_token TEXT,
                lease_until_elapsed_ms INTEGER,
                last_error TEXT,
                created_at_wall_ms INTEGER NOT NULL,
                updated_at_wall_ms INTEGER NOT NULL,
                UNIQUE(event_id, sequence),
                FOREIGN KEY(session_id, event_id)
                    REFERENCES fire_session(session_id, event_id) ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX report_outbox_due_idx ON report_outbox(status, next_attempt_elapsed_ms)")
        db.execSQL("CREATE INDEX report_outbox_event_idx ON report_outbox(event_id, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var current = oldVersion
        if (current == 3 && newVersion >= 4) {
            db.execSQL("ALTER TABLE fire_session ADD COLUMN task_id TEXT NOT NULL DEFAULT 'legacy-unbound'")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN source_generation INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN coordinator_generation INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN roi_left REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN roi_top REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN roi_right REAL NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN roi_bottom REAL NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN recovery_proof_version INTEGER")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN recovery_proof_payload TEXT")
            db.execSQL("ALTER TABLE fire_session ADD COLUMN recovery_proof_sha256 TEXT")
            current = 4
        }
        if (current == 4 && newVersion >= 5) {
            // v4 had no independent aircraft identity. Preserve rows without
            // inventing a droneSn from task_id; recovery fails closed on this sentinel.
            db.execSQL("ALTER TABLE fire_session ADD COLUMN drone_sn TEXT NOT NULL DEFAULT 'legacy-unbound'")
            current = 5
        }
        if (current == newVersion) {
            return
        }
        throw IllegalStateException(
            "Unsupported fire store upgrade $oldVersion->$newVersion; production data was not modified",
        )
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Unsupported fire store downgrade $oldVersion->$newVersion; production data was not modified",
        )
    }
}
