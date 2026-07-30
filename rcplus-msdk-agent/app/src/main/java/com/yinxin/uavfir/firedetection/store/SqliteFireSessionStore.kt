package com.yinxin.uavfir.firedetection.store

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import com.yinxin.uavfir.firedetection.DetectionKind
import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.GeoMethod
import com.yinxin.uavfir.firedetection.LocationStatus
import java.io.Closeable
import java.util.UUID

class SqliteFireSessionStore(
    private val helper: FireStoreOpenHelper,
    private val clock: StoreClock = AndroidStoreClock(),
) : FireOutboxDispatchStore, Closeable {
    private val db: SQLiteDatabase
        get() = helper.writableDatabase

    fun persistInitialConfirmation(record: InitialConfirmationRecord): DurableWriteResult =
        durableTransaction { database ->
            val canonical = CanonicalFireReport.initial(record)
            val existing = outboxByKey(database, record.request.eventId, 1)
            if (existing != null) {
                return@durableTransaction initialDuplicateResult(database, record, canonical, existing)
            }
            if (sessionById(database, record.request.sessionId) != null ||
                sessionByEvent(database, record.request.eventId) != null
            ) {
                return@durableTransaction DurableWriteResult.Conflict("Session/event identity is already allocated")
            }

            val confirmation = record.request.confirmation
            val session = ContentValues().apply {
                put("session_id", record.request.sessionId)
                put("event_id", record.request.eventId)
                put("state", FireSessionState.VISUAL_CONFIRMED.name)
                put("detection_kind", confirmation.kind.name)
                put("confidence", confirmation.confidence.toDouble())
                put("policy_version", confirmation.policyVersion)
                put("model_version", record.modelVersion)
                put("model_hash", record.modelHash.lowercase())
                put("input_size", record.inputSize)
                put("runtime", record.runtime)
                put("initial_request_id", record.request.requestId)
                putNull("terminal_request_id")
                put("location_status", LocationStatus.LASER_LOCATING.name)
                putNull("geo_method")
                put("created_at_wall_ms", record.eventTimestampWallMillis)
                put("updated_at_wall_ms", record.eventTimestampWallMillis)
            }
            check(database.insertOrThrow(FireStoreContract.Session.TABLE, null, session) != -1L)
            insertEvidence(database, record.request.sessionId, record.request.eventId, 1, record.evidence)
            insertOutbox(
                database = database,
                sessionId = record.request.sessionId,
                eventId = record.request.eventId,
                sequence = 1,
                eventTimestampWallMillis = record.eventTimestampWallMillis,
                state = FireSessionState.VISUAL_CONFIRMED,
                report = canonical,
            )
            DurableWriteResult.Written
        }

    fun persistTerminalResult(record: TerminalResultRecord): DurableWriteResult =
        durableTransaction { database ->
            val canonical = CanonicalFireReport.terminal(record)
            val session = sessionById(database, record.request.sessionId)
                ?: return@durableTransaction DurableWriteResult.Rejected("Initial session is not durable")
            if (session.eventId != record.request.eventId) {
                return@durableTransaction DurableWriteResult.Conflict("Session/event identity mismatch")
            }
            val existing = outboxByKey(database, record.request.eventId, record.sequence)
            if (session.terminalRequestId != null || existing != null) {
                return@durableTransaction terminalDuplicateResult(
                    database,
                    session,
                    existing,
                    record,
                    canonical,
                )
            }
            if (session.state != FireSessionState.LASER_MEASURING) {
                return@durableTransaction DurableWriteResult.Rejected(
                    "Terminal result requires LASER_MEASURING",
                )
            }
            if (!hasOutboxSequence(database, record.request.eventId, 1)) {
                return@durableTransaction DurableWriteResult.Rejected("Initial report is not durable")
            }
            if (record.sequence != nextSequence(database, record.request.eventId)) {
                return@durableTransaction DurableWriteResult.Rejected("Terminal sequence must be contiguous")
            }

            val values = ContentValues().apply {
                put("state", FireSessionState.RESULT_DURABLE.name)
                put("terminal_request_id", record.request.requestId)
                put("location_status", record.request.locationStatus.name)
                put("geo_method", record.request.geoMethod.name)
                put("updated_at_wall_ms", record.eventTimestampWallMillis)
            }
            check(
                database.update(
                    FireStoreContract.Session.TABLE,
                    values,
                    "session_id=? AND event_id=? AND state=? AND terminal_request_id IS NULL",
                    arrayOf(
                        record.request.sessionId,
                        record.request.eventId,
                        FireSessionState.LASER_MEASURING.name,
                    ),
                ) == 1,
            )
            insertEvidence(
                database,
                record.request.sessionId,
                record.request.eventId,
                record.sequence,
                record.evidence,
            )
            insertOutbox(
                database,
                record.request.sessionId,
                record.request.eventId,
                record.sequence,
                record.eventTimestampWallMillis,
                FireSessionState.RESULT_DURABLE,
                canonical,
            )
            DurableWriteResult.Written
        }

    fun persistStage(record: StagePersistenceRecord): DurableWriteResult =
        durableTransaction { database ->
            val canonical = CanonicalFireReport.stage(record)
            val session = sessionById(database, record.sessionId)
                ?: return@durableTransaction DurableWriteResult.Rejected("Session is not durable")
            if (session.eventId != record.eventId) {
                return@durableTransaction DurableWriteResult.Conflict("Session/event identity mismatch")
            }
            val existing = outboxByKey(database, record.eventId, record.sequence)
            if (existing != null) {
                return@durableTransaction if (
                    existing.eventId == record.eventId &&
                    existing.sessionId == record.sessionId &&
                    existing.sequence == record.sequence &&
                    existing.eventTimestampWallMillis == record.eventTimestampWallMillis &&
                    existing.state == record.state &&
                    existing.payload == canonical.payload &&
                    existing.payloadSha256 == canonical.sha256
                ) {
                    DurableWriteResult.ExactDuplicate
                } else {
                    DurableWriteResult.Conflict("Sequence already has different state or payload")
                }
            }
            if (record.sequence != nextSequence(database, record.eventId)) {
                return@durableTransaction DurableWriteResult.Rejected("Outbox sequence must be contiguous")
            }
            if (!isAllowedStoredTransition(session.state, record.state)) {
                return@durableTransaction DurableWriteResult.Rejected(
                    "Illegal durable state transition ${session.state}->${record.state}",
                )
            }
            val values = ContentValues().apply {
                put("state", record.state.name)
                put("updated_at_wall_ms", record.eventTimestampWallMillis)
            }
            check(
                database.update(
                    FireStoreContract.Session.TABLE,
                    values,
                    "session_id=? AND event_id=?",
                    arrayOf(record.sessionId, record.eventId),
                ) == 1,
            )
            insertOutbox(
                database,
                record.sessionId,
                record.eventId,
                record.sequence,
                record.eventTimestampWallMillis,
                record.state,
                canonical,
            )
            DurableWriteResult.Written
        }

    fun loadActiveSessions(): List<DurableFireSession> =
        db.query(
            FireStoreContract.Session.TABLE,
            SESSION_COLUMNS,
            "state NOT IN (?,?)",
            arrayOf(FireSessionState.DISARMED.name, FireSessionState.MISSION_RESUMED.name),
            null,
            null,
            "created_at_wall_ms ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toSession())
            }
        }

    fun loadEvidence(eventId: String): List<FireEvidenceReference> =
        db.query(
            FireStoreContract.Evidence.TABLE,
            arrayOf(
                "path",
                "sha256",
                "media_type",
                "captured_at_wall_ms",
                "byte_size",
                "width_pixels",
                "height_pixels",
                "rotation_degrees",
            ),
            "event_id=?",
            arrayOf(eventId),
            null,
            null,
            "report_sequence ASC,id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FireEvidenceReference(
                            path = cursor.getString(0),
                            sha256 = cursor.getString(1),
                            mediaType = cursor.getString(2),
                            capturedAtWallMillis = cursor.getLong(3),
                            byteSize = cursor.getLong(4),
                            widthPixels = cursor.getInt(5),
                            heightPixels = cursor.getInt(6),
                            rotationDegrees = cursor.getInt(7),
                        ),
                    )
                }
            }
        }

    fun loadPendingOutbox(): List<OutboxRow> = queryOutbox(
        selection = "status IN (?,?)",
        args = arrayOf(OutboxStatus.PENDING.name, OutboxStatus.IN_FLIGHT.name),
    )

    fun loadOutbox(eventId: String): List<OutboxRow> =
        queryOutbox(selection = "event_id=?", args = arrayOf(eventId))

    fun loadForStartup(): FireStoreStartup {
        recoverMonotonicEpoch()
        recoverUnsafeFlightSessions()
        return FireStoreStartup(loadActiveSessions(), loadPendingOutbox())
    }

    override fun leaseNext(leaseMillis: Long): OutboxLease? {
        require(leaseMillis > 0)
        return transaction { database ->
            val now = clock.elapsedRealtimeMillis()
            val row = database.rawQuery(
                """
                SELECT ${OUTBOX_COLUMNS.joinToString(",") { "o.$it" }}
                FROM report_outbox o
                WHERE (
                    (o.status=? AND o.next_attempt_elapsed_ms<=? AND o.monotonic_epoch=?)
                    OR
                    (o.status=? AND o.lease_until_elapsed_ms<=? AND o.monotonic_epoch=?)
                )
                AND NOT EXISTS (
                    SELECT 1 FROM report_outbox p
                    WHERE p.event_id=o.event_id AND p.sequence<o.sequence AND p.status!=?
                )
                ORDER BY o.created_at_wall_ms ASC,o.event_id ASC,o.sequence ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    OutboxStatus.PENDING.name,
                    now.toString(),
                    clock.monotonicEpochId(),
                    OutboxStatus.IN_FLIGHT.name,
                    now.toString(),
                    clock.monotonicEpochId(),
                    OutboxStatus.ACKED.name,
                ),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toOutbox() else null }
                ?: return@transaction null
            val token = UUID.randomUUID().toString()
            val values = ContentValues().apply {
                put("status", OutboxStatus.IN_FLIGHT.name)
                put("lease_token", token)
                put("lease_until_elapsed_ms", checkedAdd(now, leaseMillis))
                put("updated_at_wall_ms", clock.wallTimeMillis())
            }
            val updated = database.update(
                FireStoreContract.Outbox.TABLE,
                values,
                "event_id=? AND sequence=? AND ((status=? AND next_attempt_elapsed_ms<=?) OR (status=? AND lease_until_elapsed_ms<=?))",
                arrayOf(
                    row.eventId,
                    row.sequence.toString(),
                    OutboxStatus.PENDING.name,
                    now.toString(),
                    OutboxStatus.IN_FLIGHT.name,
                    now.toString(),
                ),
            )
            if (updated != 1) null else OutboxLease(
                row = row.copy(
                    status = OutboxStatus.IN_FLIGHT,
                    leaseUntilElapsedMillis = checkedAdd(now, leaseMillis),
                ),
                token = token,
            )
        }
    }

    override fun markAcknowledged(lease: OutboxLease): Boolean =
        finishLease(lease, OutboxStatus.ACKED, null, retry = false)

    override fun quarantine(lease: OutboxLease, reason: String): Boolean =
        finishLease(lease, OutboxStatus.QUARANTINED, reason, retry = false)

    override fun scheduleRetry(lease: OutboxLease, reason: String): Boolean =
        finishLease(lease, OutboxStatus.PENDING, reason, retry = true)

    override fun close() {
        helper.close()
    }

    private fun finishLease(
        lease: OutboxLease,
        status: OutboxStatus,
        reason: String?,
        retry: Boolean,
    ): Boolean = transaction { database ->
        val nowElapsed = clock.elapsedRealtimeMillis()
        val values = ContentValues().apply {
            put("status", status.name)
            putNull("lease_token")
            putNull("lease_until_elapsed_ms")
            put("last_error", reason?.let(::sanitizePersistedError))
            put("updated_at_wall_ms", clock.wallTimeMillis())
            if (retry) {
                val attempts = lease.row.attemptCount + 1
                put("attempt_count", attempts)
                put(
                    "next_attempt_elapsed_ms",
                    checkedAdd(nowElapsed, retryDelayMillis(attempts)),
                )
                put("monotonic_epoch", clock.monotonicEpochId())
            }
        }
        database.update(
            FireStoreContract.Outbox.TABLE,
            values,
            """
            event_id=? AND session_id=? AND sequence=? AND status=? AND lease_token=?
            AND monotonic_epoch=? AND lease_until_elapsed_ms>?
            """.trimIndent(),
            arrayOf(
                lease.row.eventId,
                lease.row.sessionId,
                lease.row.sequence.toString(),
                OutboxStatus.IN_FLIGHT.name,
                lease.token,
                clock.monotonicEpochId(),
                nowElapsed.toString(),
            ),
        ) == 1
    }

    private fun recoverMonotonicEpoch() {
        transaction<Unit> { database ->
            val values = ContentValues().apply {
                put("status", OutboxStatus.PENDING.name)
                put("next_attempt_elapsed_ms", clock.elapsedRealtimeMillis())
                put("monotonic_epoch", clock.monotonicEpochId())
                putNull("lease_token")
                putNull("lease_until_elapsed_ms")
                put("updated_at_wall_ms", clock.wallTimeMillis())
            }
            database.update(
                FireStoreContract.Outbox.TABLE,
                values,
                "status IN (?,?) AND monotonic_epoch!=?",
                arrayOf(
                    OutboxStatus.PENDING.name,
                    OutboxStatus.IN_FLIGHT.name,
                    clock.monotonicEpochId(),
                ),
            )
        }
    }

    private fun recoverUnsafeFlightSessions() {
        transaction<Unit> { database ->
            val unsafe = database.query(
                FireStoreContract.Session.TABLE,
                arrayOf("session_id", "event_id"),
                "state IN (${UNSAFE_STARTUP_STATES.joinToString(",") { "?" }})",
                UNSAFE_STARTUP_STATES.map { it.name }.toTypedArray(),
                null,
                null,
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
            unsafe.forEach { (sessionId, eventId) ->
                val sequence = nextSequence(database, eventId)
                val wall = clock.wallTimeMillis()
                val report = CanonicalFireReport.manualHold(eventId, sessionId, sequence, wall)
                val values = ContentValues().apply {
                    put("state", FireSessionState.MANUAL_HOLD.name)
                    put("updated_at_wall_ms", wall)
                }
                check(
                    database.update(
                        FireStoreContract.Session.TABLE,
                        values,
                        "session_id=? AND event_id=?",
                        arrayOf(sessionId, eventId),
                    ) == 1,
                )
                insertOutbox(
                    database,
                    sessionId,
                    eventId,
                    sequence,
                    wall,
                    FireSessionState.MANUAL_HOLD,
                    report,
                )
            }
        }
    }

    private fun insertEvidence(
        database: SQLiteDatabase,
        sessionId: String,
        eventId: String,
        sequence: Long,
        evidence: List<FireEvidenceReference>,
    ) {
        evidence.forEach { item ->
            val values = ContentValues().apply {
                put("session_id", sessionId)
                put("event_id", eventId)
                put("report_sequence", sequence)
                put("path", item.path)
                put("sha256", item.sha256.lowercase())
                put("media_type", item.mediaType)
                put("captured_at_wall_ms", item.capturedAtWallMillis)
                put("byte_size", item.byteSize)
                put("width_pixels", item.widthPixels)
                put("height_pixels", item.heightPixels)
                put("rotation_degrees", item.rotationDegrees)
            }
            check(database.insertOrThrow(FireStoreContract.Evidence.TABLE, null, values) != -1L)
        }
    }

    private fun insertOutbox(
        database: SQLiteDatabase,
        sessionId: String,
        eventId: String,
        sequence: Long,
        eventTimestampWallMillis: Long,
        state: FireSessionState,
        report: CanonicalReport,
    ) {
        val nowElapsed = clock.elapsedRealtimeMillis()
        val nowWall = clock.wallTimeMillis()
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("event_id", eventId)
            put("sequence", sequence)
            put("event_timestamp_wall_ms", eventTimestampWallMillis)
            put("state", state.name)
            put("payload", report.payload)
            put("payload_sha256", report.sha256)
            put("status", OutboxStatus.PENDING.name)
            put("attempt_count", 0)
            put("next_attempt_elapsed_ms", nowElapsed)
            put("monotonic_epoch", clock.monotonicEpochId())
            putNull("lease_token")
            putNull("lease_until_elapsed_ms")
            putNull("last_error")
            put("created_at_wall_ms", nowWall)
            put("updated_at_wall_ms", nowWall)
        }
        check(database.insertOrThrow(FireStoreContract.Outbox.TABLE, null, values) != -1L)
    }

    private fun initialDuplicateResult(
        database: SQLiteDatabase,
        record: InitialConfirmationRecord,
        canonical: CanonicalReport,
        existing: OutboxRow,
    ): DurableWriteResult {
        val session = sessionById(database, record.request.sessionId)
            ?: return DurableWriteResult.Conflict("Outbox exists without matching session")
        val confirmation = record.request.confirmation
        val same = session.eventId == record.request.eventId &&
            session.initialRequestId == record.request.requestId &&
            session.detectionKind == confirmation.kind &&
            session.confidence.toFloat() == confirmation.confidence &&
            session.policyVersion == confirmation.policyVersion &&
            session.modelVersion == record.modelVersion &&
            session.modelHash == record.modelHash.lowercase() &&
            session.inputSize == record.inputSize &&
            session.runtime == record.runtime &&
            existing.eventId == record.request.eventId &&
            existing.sessionId == record.request.sessionId &&
            existing.sequence == 1L &&
            existing.state == FireSessionState.VISUAL_CONFIRMED &&
            existing.payload == canonical.payload &&
            existing.payloadSha256 == canonical.sha256 &&
            existing.eventTimestampWallMillis == record.eventTimestampWallMillis &&
            evidenceMatches(database, record.request.eventId, 1, record.evidence)
        return if (same) DurableWriteResult.ExactDuplicate
        else DurableWriteResult.Conflict("Initial request or sequence has different durable content")
    }

    private fun terminalDuplicateResult(
        database: SQLiteDatabase,
        session: SessionRow,
        existing: OutboxRow?,
        record: TerminalResultRecord,
        canonical: CanonicalReport,
    ): DurableWriteResult {
        val same = existing != null &&
            session.terminalRequestId == record.request.requestId &&
            session.locationStatus == record.request.locationStatus &&
            session.geoMethod == record.request.geoMethod &&
            existing.eventId == record.request.eventId &&
            existing.sessionId == record.request.sessionId &&
            existing.sequence == record.sequence &&
            existing.state == FireSessionState.RESULT_DURABLE &&
            existing.payload == canonical.payload &&
            existing.payloadSha256 == canonical.sha256 &&
            existing.eventTimestampWallMillis == record.eventTimestampWallMillis &&
            evidenceMatches(database, record.request.eventId, record.sequence, record.evidence)
        return if (same) DurableWriteResult.ExactDuplicate
        else DurableWriteResult.Conflict("Terminal result already differs or is incomplete")
    }

    private fun evidenceMatches(
        database: SQLiteDatabase,
        eventId: String,
        sequence: Long,
        expected: List<FireEvidenceReference>,
    ): Boolean {
        val actual = database.query(
            FireStoreContract.Evidence.TABLE,
            arrayOf(
                "path",
                "sha256",
                "media_type",
                "captured_at_wall_ms",
                "byte_size",
                "width_pixels",
                "height_pixels",
                "rotation_degrees",
            ),
            "event_id=? AND report_sequence=?",
            arrayOf(eventId, sequence.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FireEvidenceReference(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getLong(3),
                            cursor.getLong(4),
                            cursor.getInt(5),
                            cursor.getInt(6),
                            cursor.getInt(7),
                        ),
                    )
                }
            }
        }
        return actual == expected
    }

    private fun queryOutbox(selection: String, args: Array<String>): List<OutboxRow> =
        db.query(
            FireStoreContract.Outbox.TABLE,
            OUTBOX_COLUMNS,
            selection,
            args,
            null,
            null,
            "created_at_wall_ms ASC,event_id ASC,sequence ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toOutbox())
            }
        }

    private fun sessionById(database: SQLiteDatabase, sessionId: String): SessionRow? =
        sessionQuery(database, "session_id=?", arrayOf(sessionId))

    private fun sessionByEvent(database: SQLiteDatabase, eventId: String): SessionRow? =
        sessionQuery(database, "event_id=?", arrayOf(eventId))

    private fun sessionQuery(
        database: SQLiteDatabase,
        selection: String,
        args: Array<String>,
    ): SessionRow? = database.query(
        FireStoreContract.Session.TABLE,
        INTERNAL_SESSION_COLUMNS,
        selection,
        args,
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toSessionRow() else null }

    private fun outboxByKey(database: SQLiteDatabase, eventId: String, sequence: Long): OutboxRow? =
        database.query(
            FireStoreContract.Outbox.TABLE,
            OUTBOX_COLUMNS,
            "event_id=? AND sequence=?",
            arrayOf(eventId, sequence.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toOutbox() else null }

    private fun hasOutboxSequence(database: SQLiteDatabase, eventId: String, sequence: Long): Boolean =
        outboxByKey(database, eventId, sequence) != null

    private fun nextSequence(database: SQLiteDatabase, eventId: String): Long =
        database.rawQuery(
            "SELECT COALESCE(MAX(sequence),0)+1 FROM report_outbox WHERE event_id=?",
            arrayOf(eventId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private inline fun durableTransaction(
        block: (SQLiteDatabase) -> DurableWriteResult,
    ): DurableWriteResult = try {
        transaction(block)
    } catch (error: IllegalArgumentException) {
        DurableWriteResult.Rejected(error.message ?: "Invalid durable record")
    } catch (error: IllegalStateException) {
        DurableWriteResult.Rejected(error.message ?: "Durable transaction failed")
    } catch (error: SQLException) {
        DurableWriteResult.Rejected(error.message ?: "SQLite transaction failed")
    }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val database = db
        database.beginTransactionNonExclusive()
        return try {
            val value = block(database)
            database.setTransactionSuccessful()
            value
        } finally {
            database.endTransaction()
        }
    }

    private fun Cursor.toSession(): DurableFireSession = DurableFireSession(
        sessionId = getString(0),
        eventId = getString(1),
        state = FireSessionState.valueOf(getString(2)),
        detectionKind = DetectionKind.valueOf(getString(3)),
        locationStatus = LocationStatus.valueOf(getString(4)),
        geoMethod = getStringOrNull(5)?.let(GeoMethod::valueOf),
        createdAtWallMillis = getLong(6),
        updatedAtWallMillis = getLong(7),
    )

    private fun Cursor.toSessionRow(): SessionRow = SessionRow(
        sessionId = getString(0),
        eventId = getString(1),
        state = FireSessionState.valueOf(getString(2)),
        detectionKind = DetectionKind.valueOf(getString(3)),
        initialRequestId = getString(4),
        terminalRequestId = getStringOrNull(5),
        locationStatus = LocationStatus.valueOf(getString(6)),
        geoMethod = getStringOrNull(7)?.let(GeoMethod::valueOf),
        confidence = getDouble(8),
        policyVersion = getString(9),
        modelVersion = getString(10),
        modelHash = getString(11),
        inputSize = getInt(12),
        runtime = getString(13),
    )

    private fun Cursor.toOutbox(): OutboxRow = OutboxRow(
        eventId = getString(0),
        sessionId = getString(1),
        sequence = getLong(2),
        eventTimestampWallMillis = getLong(3),
        state = FireSessionState.valueOf(getString(4)),
        payload = getString(5),
        payloadSha256 = getString(6),
        status = OutboxStatus.valueOf(getString(7)),
        attemptCount = getInt(8),
        nextAttemptElapsedMillis = getLong(9),
        leaseUntilElapsedMillis = getLongOrNull(10),
        lastError = getStringOrNull(11),
    )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private data class SessionRow(
        val sessionId: String,
        val eventId: String,
        val state: FireSessionState,
        val detectionKind: DetectionKind,
        val initialRequestId: String,
        val terminalRequestId: String?,
        val locationStatus: LocationStatus,
        val geoMethod: GeoMethod?,
        val confidence: Double,
        val policyVersion: String,
        val modelVersion: String,
        val modelHash: String,
        val inputSize: Int,
        val runtime: String,
    )

    companion object {
        private const val MAX_ERROR_LENGTH = 1_000
        private val RETRY_DELAYS = longArrayOf(250, 500, 1_000, 2_000, 5_000)
        private val UNSAFE_STARTUP_STATES = listOf(
            FireSessionState.HOLD_REQUESTED,
            FireSessionState.HOVER_VERIFYING,
            FireSessionState.TARGET_ALIGNING,
            FireSessionState.LASER_MEASURING,
            FireSessionState.RESULT_DURABLE,
            FireSessionState.RESUME_REQUESTED,
        )
        private val SESSION_COLUMNS = arrayOf(
            "session_id",
            "event_id",
            "state",
            "detection_kind",
            "location_status",
            "geo_method",
            "created_at_wall_ms",
            "updated_at_wall_ms",
        )
        private val INTERNAL_SESSION_COLUMNS = arrayOf(
            "session_id",
            "event_id",
            "state",
            "detection_kind",
            "initial_request_id",
            "terminal_request_id",
            "location_status",
            "geo_method",
            "confidence",
            "policy_version",
            "model_version",
            "model_hash",
            "input_size",
            "runtime",
        )
        private val OUTBOX_COLUMNS = arrayOf(
            "event_id",
            "session_id",
            "sequence",
            "event_timestamp_wall_ms",
            "state",
            "payload",
            "payload_sha256",
            "status",
            "attempt_count",
            "next_attempt_elapsed_ms",
            "lease_until_elapsed_ms",
            "last_error",
        )

        internal fun retryDelayMillis(attemptCount: Int): Long {
            require(attemptCount > 0)
            return RETRY_DELAYS[(attemptCount - 1).coerceAtMost(RETRY_DELAYS.lastIndex)]
        }

        private fun isAllowedStoredTransition(
            from: FireSessionState,
            to: FireSessionState,
        ): Boolean = to == FireSessionState.MANUAL_HOLD || when (from) {
            FireSessionState.VISUAL_CONFIRMED -> to == FireSessionState.HOLD_REQUESTED
            FireSessionState.HOLD_REQUESTED -> to == FireSessionState.HOVER_VERIFYING
            FireSessionState.HOVER_VERIFYING -> to == FireSessionState.TARGET_ALIGNING
            FireSessionState.TARGET_ALIGNING -> to == FireSessionState.LASER_MEASURING
            FireSessionState.RESULT_DURABLE -> to == FireSessionState.RESUME_REQUESTED
            FireSessionState.RESUME_REQUESTED -> to == FireSessionState.MISSION_RESUMED
            FireSessionState.MISSION_RESUMED -> to == FireSessionState.SCANNING
            FireSessionState.MANUAL_HOLD -> to == FireSessionState.SCANNING
            else -> false
        }

        private fun checkedAdd(left: Long, right: Long): Long {
            require(left >= 0 && right >= 0)
            return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
        }

        private fun sanitizePersistedError(reason: String): String =
            reason.take(MAX_ERROR_LENGTH).takeIf(SAFE_ERROR_CODE::matches) ?: "REDACTED_ERROR"

        private val SAFE_ERROR_CODE = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    }
}
