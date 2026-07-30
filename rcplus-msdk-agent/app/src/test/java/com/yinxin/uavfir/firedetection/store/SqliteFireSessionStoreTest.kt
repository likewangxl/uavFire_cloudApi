package com.yinxin.uavfir.firedetection.store

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.yinxin.uavfir.firedetection.DetectionKind
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
                    captured_at_wall_ms,byte_size,metadata_json
                ) VALUES('missing','missing',1,'/tmp/x.jpg','${"a".repeat(64)}','image/jpeg',1,1,'{}')
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
                    session_id,event_id,sequence,event_timestamp_wall_ms,payload,payload_sha256,
                    status,attempt_count,next_attempt_elapsed_ms,monotonic_epoch,lease_token,
                    lease_until_elapsed_ms,last_error,created_at_wall_ms,updated_at_wall_ms
                )
                SELECT session_id,event_id,sequence,event_timestamp_wall_ms,payload,payload_sha256,
                    status,attempt_count,next_attempt_elapsed_ms,monotonic_epoch,lease_token,
                    lease_until_elapsed_ms,last_error,created_at_wall_ms,updated_at_wall_ms
                FROM report_outbox WHERE event_id='$EVENT_ID' AND sequence=1
                """.trimIndent(),
            )
        }

        val conflict = record.copy(
            payload = """{"state":"VISUAL_CONFIRMED","confidence":0.1}""",
            payloadSha256 = sha256("""{"state":"VISUAL_CONFIRMED","confidence":0.1}"""),
        )
        assertTrue(store.persistInitialConfirmation(conflict) is DurableWriteResult.Conflict)
        assertEquals(record.payload, store.loadPendingOutbox().single().payload)
    }

    @Test
    fun typedRecordsRejectImageBlobsAndCredentialOrContactFields() {
        listOf(
            """{"image":"data:image/jpeg;base64,AAAA"}""",
            """{"authorization":"redacted-value"}""",
            """{"phone":"not-a-real-contact"}""",
        ).forEach { forbidden ->
            assertThrows(IllegalArgumentException::class.java) {
                initialRecord().copy(payload = forbidden, payloadSha256 = sha256(forbidden))
            }
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
        val terminal = terminalRecord()
        assertEquals(DurableWriteResult.Written, store.persistTerminalResult(terminal))
        assertEquals(DurableWriteResult.ExactDuplicate, store.persistTerminalResult(terminal))
        val session = store.loadActiveSessions().single()
        assertEquals(FireSessionState.RESULT_DURABLE, session.state)
        assertEquals(LocationStatus.PRECISE, session.locationStatus)
        assertEquals(GeoMethod.LASER_RANGEFINDER, session.geoMethod)
        assertEquals(listOf(1L, 2L), store.loadPendingOutbox().map { it.sequence })

        val conflictingTerminal = terminal.copy(
            request = terminal.request.copy(
                requestId = "33333333-3333-4333-8333-333333333333",
                locationStatus = LocationStatus.DEGRADED_OSD,
                geoMethod = GeoMethod.AIRCRAFT_OBSERVATION,
            ),
            payload = """{"locationStatus":"DEGRADED_OSD"}""",
            payloadSha256 = sha256("""{"locationStatus":"DEGRADED_OSD"}"""),
        )
        assertTrue(store.persistTerminalResult(conflictingTerminal) is DurableWriteResult.Conflict)
        assertEquals(LocationStatus.PRECISE, store.loadActiveSessions().single().locationStatus)
    }

    @Test
    fun failedTerminalTransactionRollsBackSessionEvidenceAndOutboxTogether() {
        store.persistInitialConfirmation(initialRecord())
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_terminal_outbox BEFORE INSERT ON report_outbox BEGIN SELECT RAISE(ABORT,'simulated full disk'); END",
        )
        val terminal = terminalRecord().copy(evidence = listOf(evidence().copy(path = "/tmp/terminal.jpg")))
        assertTrue(store.persistTerminalResult(terminal) is DurableWriteResult.Rejected)

        val session = store.loadActiveSessions().single()
        assertEquals(FireSessionState.VISUAL_CONFIRMED, session.state)
        assertEquals(LocationStatus.LASER_LOCATING, session.locationStatus)
        assertEquals(listOf(1L), store.loadOutbox(EVENT_ID).map { it.sequence })
        assertEquals(1, store.loadEvidence(EVENT_ID).size)
    }

    @Test
    fun laterSequenceBecomesEligibleOnlyAfterEarlierAck() {
        store.persistInitialConfirmation(initialRecord())
        store.persistTerminalResult(terminalRecord())
        val first = store.leaseNext(1_000)!!
        assertEquals(1L, first.row.sequence)
        assertNull(store.leaseNext(1_000))
        assertTrue(store.markAcknowledged(first))
        assertEquals(2L, store.leaseNext(1_000)!!.row.sequence)
    }

    @Test
    fun strictOrderingLeaseExpiryRetriesAndQuarantineAreCrashSafe() {
        store.persistInitialConfirmation(initialRecord())
        store.persistTerminalResult(terminalRecord())

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
            store.persistStage(
                StagePersistenceRecord(
                    sessionId = SESSION_ID,
                    eventId = EVENT_ID,
                    sequence = 2,
                    eventTimestampWallMillis = 101_000,
                    state = FireSessionState.HOLD_REQUESTED,
                    payload = """{"state":"HOLD_REQUESTED"}""",
                    payloadSha256 = sha256("""{"state":"HOLD_REQUESTED"}"""),
                ),
            ),
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
        payload = """{"state":"VISUAL_CONFIRMED","confidence":0.94}""",
        payloadSha256 = sha256("""{"state":"VISUAL_CONFIRMED","confidence":0.94}"""),
        evidence = listOf(evidence()),
    )

    private fun terminalRecord() = TerminalResultRecord(
        request = TerminalPersistenceRequest(
            sessionId = SESSION_ID,
            eventId = EVENT_ID,
            requestId = "22222222-2222-4222-8222-222222222222",
            locationStatus = LocationStatus.PRECISE,
            geoMethod = GeoMethod.LASER_RANGEFINDER,
        ),
        sequence = 2,
        eventTimestampWallMillis = 102_000,
        payload = """{"locationStatus":"PRECISE"}""",
        payloadSha256 = sha256("""{"locationStatus":"PRECISE"}"""),
        evidence = emptyList(),
    )

    private fun evidence() = FireEvidenceReference(
        path = "/data/user/0/com.yinxin.uavfir/files/fire/event-1.jpg",
        sha256 = "b".repeat(64),
        mediaType = "image/jpeg",
        capturedAtWallMillis = 99_100,
        byteSize = 1234,
        metadataJson = """{"width":1920,"height":1080}""",
    )

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
