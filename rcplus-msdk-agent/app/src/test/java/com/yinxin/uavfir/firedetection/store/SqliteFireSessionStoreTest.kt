package com.yinxin.uavfir.firedetection.store

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.yinxin.uavfir.firedetection.DetectionKind
import com.yinxin.uavfir.firedetection.FireSessionEffect
import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.GeoMethod
import com.yinxin.uavfir.firedetection.InitialPersistenceRequest
import com.yinxin.uavfir.firedetection.LocationStatus
import com.yinxin.uavfir.firedetection.NormalizedRoi
import com.yinxin.uavfir.firedetection.TerminalPersistenceRequest
import com.yinxin.uavfir.firedetection.VisibleConfirmation
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteFireSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "fire-store-${UUID.randomUUID()}.db"
    private val clock = MutableStoreClock(elapsedMillis = 10_000, wallMillis = 100_000)
    private val helper = FireStoreOpenHelper(context, dbName)
    private val store = SqliteFireSessionStore(helper, clock)

    @After
    fun close() {
        store.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun schemaUsesWalForeignKeysAndRequiredTablesWithoutBlobColumns() {
        val db = helper.writableDatabase
        assertTrue(db.isWriteAheadLoggingEnabled)
        assertEquals(1L, db.longPragma("foreign_keys"))
        assertEquals(
            setOf("fire_session", "fire_evidence", "report_outbox"),
            db.userTables(),
        )
        val evidenceTypes = db.columnTypes("fire_evidence")
        assertFalse(evidenceTypes.values.any { it.contains("BLOB", ignoreCase = true) })
        assertEquals(FireStoreContract.SCHEMA_VERSION, db.version)
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                """
                INSERT INTO fire_evidence(
                    session_id,event_id,report_sequence,path,sha256,media_type,
                    captured_at_wall_ms,byte_size,width_pixels,height_pixels,rotation_degrees
                ) VALUES('missing','missing',1,'/tmp/x.jpg','${"a".repeat(64)}','image/jpeg',1,1,1,1,0)
                """.trimIndent(),
            )
        }
    }

    @Test
    fun initialWriteIsAtomicIdempotentAndRejectsSameSequencePayloadConflict() {
        val record = initialRecord()
        assertEquals(DurableWriteResult.Written, store.persistInitialConfirmation(record))
        assertEquals(DurableWriteResult.ExactDuplicate, store.persistInitialConfirmation(record))
        assertEquals(1, store.loadActiveSessions().size)
        assertEquals(listOf(1L), store.loadPendingOutbox().map { it.sequence })
        assertEquals(1, store.loadEvidence(EVENT_ID).size)
        assertThrows(SQLiteConstraintException::class.java) {
            helper.writableDatabase.execSQL(
                """
                INSERT INTO report_outbox(
                    session_id,event_id,sequence,event_timestamp_wall_ms,state,payload,payload_sha256,
                    status,attempt_count,next_attempt_elapsed_ms,monotonic_epoch,lease_token,
                    lease_until_elapsed_ms,last_error,created_at_wall_ms,updated_at_wall_ms
                )
                SELECT session_id,event_id,sequence,event_timestamp_wall_ms,state,payload,payload_sha256,
                    status,attempt_count,next_attempt_elapsed_ms,monotonic_epoch,lease_token,
                    lease_until_elapsed_ms,last_error,created_at_wall_ms,updated_at_wall_ms
                FROM report_outbox WHERE event_id='$EVENT_ID' AND sequence=1
                """.trimIndent(),
            )
        }

        val conflict = record.copy(
            request = record.request.copy(
                requestId = "99999999-9999-4999-8999-999999999999",
            ),
        )
        assertTrue(store.persistInitialConfirmation(conflict) is DurableWriteResult.Conflict)
        assertEquals(CanonicalFireReport.initial(record).payload, store.loadPendingOutbox().single().payload)
    }

    @Test
    fun typedRecordsRejectImageBlobsAndCredentialOrContactFields() {
        listOf(
            initialPayload(extra = ""","reason":"data:image/jpeg;base64,AAAA""""),
            initialPayload(extra = ""","auth\u006frization":"escaped-sensitive-key""""),
            initialPayload(extra = ""","reason":"person@example.com""""),
            initialPayload(extra = ""","nested":{"imageBytes":"${"A".repeat(128)}"}"""),
        ).forEach { forbidden ->
            assertTrue(store.persistInitialConfirmation(initialRecord().copy(payload = forbidden)) is DurableWriteResult.Rejected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence().copy(rotationDegrees = 45)
        }
    }

    @Test
    fun failedInitialTransactionLeavesNoSessionEvidenceOrOutbox() {
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_initial_outbox BEFORE INSERT ON report_outbox BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END",
        )
        val result = store.persistInitialConfirmation(initialRecord())
        assertTrue(result is DurableWriteResult.Rejected)
        assertTrue(store.loadActiveSessions().isEmpty())
        assertTrue(store.loadPendingOutbox().isEmpty())
        assertTrue(store.loadEvidence(EVENT_ID).isEmpty())
    }

    @Test
    fun terminalWriteIsAtomicExactlyOnceAndKeepsCoordinatesOutOfStoreContract() {
        assertEquals(DurableWriteResult.Written, store.persistInitialConfirmation(initialRecord()))
        advanceToLaser()
        val terminal = terminalRecord()
        assertEquals(DurableWriteResult.Written, store.persistTerminalResult(terminal))
        assertEquals(DurableWriteResult.ExactDuplicate, store.persistTerminalResult(terminal))
        val session = store.loadActiveSessions().single()
        assertEquals(FireSessionState.RESULT_DURABLE, session.state)
        assertEquals(LocationStatus.PRECISE, session.locationStatus)
        assertEquals(GeoMethod.LASER_RANGEFINDER, session.geoMethod)
        assertEquals((1L..6L).toList(), store.loadPendingOutbox().map { it.sequence })

        val conflictingTerminal = terminal.copy(
            effect = FireSessionEffect.PersistTerminalResult(
                terminal.request.copy(
                    requestId = "33333333-3333-4333-8333-333333333333",
                    locationStatus = LocationStatus.DEGRADED_OSD,
                    geoMethod = GeoMethod.AIRCRAFT_OBSERVATION,
                ),
            ),
            payload = terminalPayload(
                locationStatus = LocationStatus.DEGRADED_OSD,
                geoMethod = GeoMethod.AIRCRAFT_OBSERVATION,
            ),
        )
        assertTrue(store.persistTerminalResult(conflictingTerminal) is DurableWriteResult.Conflict)
        assertEquals(LocationStatus.PRECISE, store.loadActiveSessions().single().locationStatus)
    }

    @Test
    fun failedTerminalTransactionRollsBackSessionEvidenceAndOutboxTogether() {
        store.persistInitialConfirmation(initialRecord())
        advanceToLaser()
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_terminal_outbox BEFORE INSERT ON report_outbox BEGIN SELECT RAISE(ABORT,'simulated full disk'); END",
        )
        val terminal = terminalRecord().copy(evidence = listOf(evidence().copy(path = "/tmp/terminal.jpg")))
        assertTrue(store.persistTerminalResult(terminal) is DurableWriteResult.Rejected)

        val session = store.loadActiveSessions().single()
        assertEquals(FireSessionState.LASER_MEASURING, session.state)
        assertEquals(LocationStatus.LASER_LOCATING, session.locationStatus)
        assertEquals((1L..5L).toList(), store.loadOutbox(EVENT_ID).map { it.sequence })
        assertEquals(1, store.loadEvidence(EVENT_ID).size)
    }

    @Test
    fun laterSequenceBecomesEligibleOnlyAfterEarlierAck() {
        store.persistInitialConfirmation(initialRecord())
        store.persistStage(stageRecord(2, FireSessionState.HOLD_REQUESTED))
        val first = store.leaseNext(1_000)!!
        assertEquals(1L, first.row.sequence)
        assertNull(store.leaseNext(1_000))
        assertTrue(store.markAcknowledged(first))
        assertEquals(2L, store.leaseNext(1_000)!!.row.sequence)
    }

    @Test
    fun expiredLeaseCannotAckRetryOrQuarantineEvenBeforeReLease() {
        store.persistInitialConfirmation(initialRecord())
        listOf<(OutboxLease) -> Boolean>(
            store::markAcknowledged,
            { store.scheduleRetry(it, "NETWORK") },
            { store.quarantine(it, "HTTP_409") },
        ).forEach { finish ->
            val lease = store.leaseNext(100)!!
            clock.elapsedMillis = lease.row.leaseUntilElapsedMillis!!
            assertFalse("lease ownership ends at its exact boundary", finish(lease))
            clock.elapsedMillis += 1
            val replacement = store.leaseNext(100)!!
            assertFalse("old token stays invalid after re-lease", finish(lease))
            assertTrue(store.scheduleRetry(replacement, "RESET"))
            clock.elapsedMillis = store.loadPendingOutbox().single().nextAttemptElapsedMillis
        }
    }

    @Test
    fun leaseCompletionRequiresCurrentEpochAndTimeStrictlyBeforeExpiry() {
        store.persistInitialConfirmation(initialRecord())
        val lease = store.leaseNext(100)!!
        clock.elapsedMillis = lease.row.leaseUntilElapsedMillis!! - 1
        assertTrue(store.markAcknowledged(lease))

        val secondDb = "fire-store-epoch-${UUID.randomUUID()}.db"
        val secondHelper = FireStoreOpenHelper(context, secondDb)
        val secondClock = MutableStoreClock(10_000, 100_000)
        val secondStore = SqliteFireSessionStore(secondHelper, secondClock)
        try {
            secondStore.persistInitialConfirmation(initialRecord())
            val secondLease = secondStore.leaseNext(100)!!
            secondClock.epochId = "different-boot"
            assertFalse(secondStore.markAcknowledged(secondLease))
        } finally {
            secondStore.close()
            context.deleteDatabase(secondDb)
        }
    }

    @Test
    fun strictOrderingLeaseExpiryRetriesAndQuarantineAreCrashSafe() {
        store.persistInitialConfirmation(initialRecord())
        store.persistStage(stageRecord(2, FireSessionState.HOLD_REQUESTED))

        val first = store.leaseNext(leaseMillis = 1_000)!!
        assertEquals(1L, first.row.sequence)
        assertNull(store.leaseNext(leaseMillis = 1_000))

        clock.elapsedMillis += 999
        assertNull(store.leaseNext(leaseMillis = 1_000))
        clock.elapsedMillis += 1
        val recovered = store.leaseNext(leaseMillis = 1_000)!!
        assertEquals(1L, recovered.row.sequence)
        assertTrue(recovered.token != first.token)

        assertFalse(store.markAcknowledged(first))
        assertTrue(store.scheduleRetry(recovered, "network"))
        assertEquals(10_000L + 1_000L + 250L, store.loadPendingOutbox().first().nextAttemptElapsedMillis)
        assertNull(store.leaseNext(leaseMillis = 1_000))
        clock.elapsedMillis += 250
        val retry = store.leaseNext(leaseMillis = 1_000)!!
        assertTrue(store.quarantine(retry, "HTTP_409_PAYLOAD_CONFLICT"))
        assertNull(store.leaseNext(leaseMillis = 1_000))
        assertEquals(OutboxStatus.QUARANTINED, store.loadOutbox(EVENT_ID).first().status)
        assertEquals(OutboxStatus.PENDING, store.loadOutbox(EVENT_ID)[1].status)
    }

    @Test
    fun retryScheduleIsCanonicalAndCapsAtFiveSeconds() {
        store.persistInitialConfirmation(initialRecord())
        val expected = listOf(250L, 500L, 1_000L, 2_000L, 5_000L, 5_000L)
        expected.forEach { delay ->
            val lease = store.leaseNext(leaseMillis = 100)!!
            val before = clock.elapsedMillis
            assertTrue(store.scheduleRetry(lease, "HTTP_503"))
            assertEquals(before + delay, store.loadPendingOutbox().single().nextAttemptElapsedMillis)
            clock.elapsedMillis += delay
        }
    }

    @Test
    fun startupRecoveryLoadsPendingAndConvertsUnsafeFlightWindowToManualHold() {
        store.persistInitialConfirmation(initialRecord())
        assertEquals(
            DurableWriteResult.Written,
            store.persistStage(stageRecord(2, FireSessionState.HOLD_REQUESTED)),
        )
        val recovery = store.loadForStartup()
        assertEquals(FireSessionState.MANUAL_HOLD, recovery.activeSessions.single().state)
        assertEquals(listOf(1L, 2L, 3L), recovery.pendingOutbox.map { it.sequence })
        assertEquals(FireSessionState.MANUAL_HOLD, store.loadActiveSessions().single().state)
    }

    @Test
    fun startupResetsOnlyRetryTimingFromAnEarlierMonotonicEpoch() {
        store.persistInitialConfirmation(initialRecord())
        val lease = store.leaseNext(1_000)!!
        assertTrue(store.scheduleRetry(lease, "offline"))
        val oldDue = store.loadPendingOutbox().single().nextAttemptElapsedMillis

        clock.epochId = "next-boot"
        clock.elapsedMillis = 5
        store.loadForStartup()
        val recovered = store.loadPendingOutbox().single()
        assertEquals(5L, recovered.nextAttemptElapsedMillis)
        assertTrue(oldDue > recovered.nextAttemptElapsedMillis)
        assertEquals(100_000L, recovered.eventTimestampWallMillis)
    }

    @Test
    fun reportIdentityMismatchAndMalformedJsonAreRejectedBeforeCommit() {
        listOf(
            initialPayload(eventId = "other-event"),
            initialPayload(sessionId = "other-session"),
            initialPayload(sequence = 2),
            initialPayload(eventTimestamp = 100_001),
            initialPayload(state = FireSessionState.HOLD_REQUESTED),
        ).forEach { payload ->
            assertTrue(
                store.persistInitialConfirmation(initialRecord().copy(payload = payload)) is
                    DurableWriteResult.Rejected,
            )
        }
        assertTrue(store.loadActiveSessions().isEmpty())

        assertTrue(
            store.persistInitialConfirmation(initialRecord().copy(payload = "{broken")) is
                DurableWriteResult.Rejected,
        )
        assertTrue(
            store.persistInitialConfirmation(
                initialRecord().copy(
                    payload = initialPayload().replaceFirst(
                        """"state":"VISUAL_CONFIRMED"""",
                        """"state":"VISUAL_CONFIRMED","state":"VISUAL_CONFIRMED"""",
                    ),
                ),
            ) is DurableWriteResult.Rejected,
        )
    }

    @Test
    fun semanticallyIdenticalJsonIsCanonicalizedAndHashIsDerivedInternally() {
        val first = initialRecord()
        assertEquals(DurableWriteResult.Written, store.persistInitialConfirmation(first))
        val reordered = first.copy(
            payload = """{"visibleRoi":{"height":0.20,"width":2e-1,"y":0.200,"x":0.2},"state":"VISUAL_CONFIRMED","sessionId":"$SESSION_ID","sequence":1,"runtime":"NCNN","policyVersion":"agent-visible-v1","modelVersion":"visible-v1","modelHash":"${"a".repeat(64)}","locationStatus":"LASER_LOCATING","inputSize":960,"eventTimestamp":100000,"eventId":"$EVENT_ID","detectionKind":"FIRE","confidence":0.9400}""",
        )
        assertEquals(DurableWriteResult.ExactDuplicate, store.persistInitialConfirmation(reordered))
        val row = store.loadPendingOutbox().single()
        assertEquals(CanonicalFireReport.initial(first).payload, row.payload)
        assertEquals(sha256(row.payload), row.payloadSha256)
    }

    @Test
    fun terminalRequiresLaserMeasuringAndStageDuplicateIncludesState() {
        store.persistInitialConfirmation(initialRecord())
        assertTrue(store.persistTerminalResult(terminalRecord()) is DurableWriteResult.Rejected)

        val hold = stageRecord(2, FireSessionState.HOLD_REQUESTED)
        assertEquals(DurableWriteResult.Written, store.persistStage(hold))
        assertTrue(store.persistTerminalResult(terminalRecord()) is DurableWriteResult.Rejected)
        assertTrue(
            store.persistStage(
                hold.copy(
                    state = FireSessionState.HOVER_VERIFYING,
                    payload = stagePayload(2, FireSessionState.HOVER_VERIFYING),
                ),
            ) is
                DurableWriteResult.Conflict,
        )
        assertEquals(DurableWriteResult.Written, store.persistStage(stageRecord(3, FireSessionState.HOVER_VERIFYING)))
        assertTrue(store.persistTerminalResult(terminalRecord()) is DurableWriteResult.Rejected)
        assertEquals(DurableWriteResult.Written, store.persistStage(stageRecord(4, FireSessionState.TARGET_ALIGNING)))
        assertTrue(store.persistTerminalResult(terminalRecord()) is DurableWriteResult.Rejected)
        assertEquals(DurableWriteResult.Written, store.persistStage(stageRecord(5, FireSessionState.LASER_MEASURING)))
        assertEquals(DurableWriteResult.Written, store.persistTerminalResult(terminalRecord()))
        assertEquals(DurableWriteResult.ExactDuplicate, store.persistTerminalResult(terminalRecord()))
    }

    @Test
    fun schemaRejectsCrossedSessionEventPairs() {
        store.persistInitialConfirmation(initialRecord())
        store.persistInitialConfirmation(
            initialRecord().copy(
                request = initialRecord().request.copy(
                    sessionId = "session-2",
                    eventId = "event-2",
                    requestId = "44444444-4444-4444-8444-444444444444",
                ),
                payload = initialPayload(eventId = "event-2", sessionId = "session-2"),
            ),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            helper.writableDatabase.execSQL(
                """
                INSERT INTO fire_evidence(
                    session_id,event_id,report_sequence,path,sha256,media_type,
                    captured_at_wall_ms,byte_size,width_pixels,height_pixels,rotation_degrees
                ) VALUES('$SESSION_ID','event-2',9,'/tmp/crossed.jpg','${"c".repeat(64)}',
                    'image/jpeg',1,1,1,1,0)
                """.trimIndent(),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            helper.writableDatabase.execSQL(
                """
                INSERT INTO report_outbox(
                    session_id,event_id,sequence,event_timestamp_wall_ms,state,payload,payload_sha256,
                    status,attempt_count,next_attempt_elapsed_ms,monotonic_epoch,created_at_wall_ms,updated_at_wall_ms
                ) VALUES('$SESSION_ID','event-2',9,1,'HOLD_REQUESTED','{}','${"d".repeat(64)}',
                    'PENDING',0,1,'test-boot',1,1)
                """.trimIndent(),
            )
        }
    }

    @Test
    fun bootEpochFallbackNeverClaimsContinuityAcrossProcesses() {
        val stableA = AndroidStoreClock(BootIdentitySource { "boot-1" }) { "ignored-a" }
        val stableB = AndroidStoreClock(BootIdentitySource { "boot-1" }) { "ignored-b" }
        assertEquals(stableA.monotonicEpochId(), stableB.monotonicEpochId())

        val fallbackA = AndroidStoreClock(BootIdentitySource { null }) { "process-a" }
        val fallbackB = AndroidStoreClock(BootIdentitySource { null }) { "process-b" }
        assertTrue(fallbackA.monotonicEpochId().startsWith("untrusted-process-"))
        assertTrue(fallbackA.monotonicEpochId() != fallbackB.monotonicEpochId())
    }

    private fun initialRecord() = InitialConfirmationRecord(
        request = InitialPersistenceRequest(
            sessionId = SESSION_ID,
            eventId = EVENT_ID,
            requestId = "11111111-1111-4111-8111-111111111111",
            confirmation = VisibleConfirmation(
                kind = DetectionKind.FIRE,
                confidence = 0.94f,
                roi = NormalizedRoi(0.2f, 0.2f, 0.4f, 0.4f),
                firstFrameTimestampMillis = 99_000,
                secondFrameTimestampMillis = 99_100,
                policyVersion = "agent-visible-v1",
            ),
        ),
        eventTimestampWallMillis = 100_000,
        modelVersion = "visible-v1",
        modelHash = "a".repeat(64),
        inputSize = 960,
        runtime = "NCNN",
        payload = initialPayload(),
        evidence = listOf(evidence()),
    )

    private fun terminalRecord() = TerminalResultRecord(
        effect = FireSessionEffect.PersistTerminalResult(
            TerminalPersistenceRequest(
                sessionId = SESSION_ID,
                eventId = EVENT_ID,
                requestId = "22222222-2222-4222-8222-222222222222",
                locationStatus = LocationStatus.PRECISE,
                geoMethod = GeoMethod.LASER_RANGEFINDER,
            ),
        ),
        sequence = 6,
        eventTimestampWallMillis = 102_000,
        payload = terminalPayload(),
        evidence = emptyList(),
    )

    private fun evidence() = FireEvidenceReference(
        path = "/data/user/0/com.yinxin.uavfir/files/fire/event-1.jpg",
        sha256 = "b".repeat(64),
        mediaType = "image/jpeg",
        capturedAtWallMillis = 99_100,
        byteSize = 1234,
        widthPixels = 1920,
        heightPixels = 1080,
        rotationDegrees = 0,
    )

    private fun advanceToLaser() {
        listOf(
            FireSessionState.HOLD_REQUESTED,
            FireSessionState.HOVER_VERIFYING,
            FireSessionState.TARGET_ALIGNING,
            FireSessionState.LASER_MEASURING,
        ).forEachIndexed { index, state ->
            assertEquals(DurableWriteResult.Written, store.persistStage(stageRecord(index + 2L, state)))
        }
    }

    private fun stageRecord(
        sequence: Long,
        state: FireSessionState,
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
    ) = StagePersistenceRecord(
        sessionId = sessionId,
        eventId = eventId,
        sequence = sequence,
        eventTimestampWallMillis = 100_000 + sequence * 1_000,
        state = state,
        payload = stagePayload(sequence, state, eventId, sessionId),
    )

    private fun stagePayload(
        sequence: Long,
        state: FireSessionState,
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
    ): String = """
        {
          "eventTimestamp":${100_000 + sequence * 1_000},
          "state":"${state.name}",
          "sequence":$sequence,
          "sessionId":"$sessionId",
          "eventId":"$eventId"
        }
    """.trimIndent()

    private fun initialPayload(
        eventId: String = EVENT_ID,
        sessionId: String = SESSION_ID,
        sequence: Long = 1,
        eventTimestamp: Long = 100_000,
        state: FireSessionState = FireSessionState.VISUAL_CONFIRMED,
        confidence: String = "0.94",
        extra: String = "",
    ): String = """
        {
          "runtime":"NCNN",
          "eventId":"$eventId",
          "sessionId":"$sessionId",
          "sequence":$sequence,
          "eventTimestamp":$eventTimestamp,
          "state":"${state.name}",
          "detectionKind":"FIRE",
          "confidence":$confidence,
          "visibleRoi":{"x":0.2,"y":0.2,"width":0.2,"height":0.2},
          "locationStatus":"LASER_LOCATING",
          "modelVersion":"visible-v1",
          "modelHash":"${"a".repeat(64)}",
          "policyVersion":"agent-visible-v1",
          "inputSize":960
          $extra
        }
    """.trimIndent()

    private fun terminalPayload(
        locationStatus: LocationStatus = LocationStatus.PRECISE,
        geoMethod: GeoMethod = GeoMethod.LASER_RANGEFINDER,
    ): String = """
        {
          "eventId":"$EVENT_ID",
          "sessionId":"$SESSION_ID",
          "sequence":6,
          "eventTimestamp":102000,
          "state":"RESULT_DURABLE",
          "locationStatus":"${locationStatus.name}",
          "geoMethod":"${geoMethod.name}"
        }
    """.trimIndent()

    private fun android.database.sqlite.SQLiteDatabase.longPragma(name: String): Long =
        rawQuery("PRAGMA $name", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun android.database.sqlite.SQLiteDatabase.userTables(): Set<String> =
        rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name != 'sqlite_sequence'",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun android.database.sqlite.SQLiteDatabase.columnTypes(table: String): Map<String, String> =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2))
            }
        }

    private companion object {
        const val SESSION_ID = "session-1"
        const val EVENT_ID = "event-1"
    }
}
