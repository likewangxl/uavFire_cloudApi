package com.yinxin.uavfir.firedetection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yinxin.uavfir.api.FlightControlActionClient
import com.yinxin.uavfir.api.MissionHoldControl
import com.yinxin.uavfir.api.VisibleFireLaserLocator
import com.yinxin.uavfir.api.VisibleFireTime
import com.yinxin.uavfir.firedetection.store.CanonicalCoordinatorStoreRecordFactory
import com.yinxin.uavfir.firedetection.store.CoordinatorModelIdentity
import com.yinxin.uavfir.firedetection.store.FireEvidenceReference
import com.yinxin.uavfir.firedetection.store.FireOutboxDispatcher
import com.yinxin.uavfir.firedetection.store.FireReportTransport
import com.yinxin.uavfir.firedetection.store.FireStoreOpenHelper
import com.yinxin.uavfir.firedetection.store.MutableStoreClock
import com.yinxin.uavfir.firedetection.store.SendOutcome
import com.yinxin.uavfir.firedetection.store.SqliteCoordinatorStoreAdapter
import com.yinxin.uavfir.firedetection.store.SqliteFireSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class AgentFireProductionAdaptersIntegrationTest {
    @Test
    fun `real store outbox task7 and task8 adapters preserve identity and degraded audit`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "task9-integration-${System.nanoTime()}.db"
        val storeClock = MutableStoreClock(2_000, 100_000, "boot-integration")
        val store = SqliteFireSessionStore(FireStoreOpenHelper(context, database), storeClock)
        try {
            val storeAdapter = SqliteCoordinatorStoreAdapter(
                store,
                CanonicalCoordinatorStoreRecordFactory(
                    storeClock,
                    CoordinatorModelIdentity("visible-v1", "a".repeat(64), 960, "ncnn"),
                ),
            )
            val outbox = StoreBackedCoordinatorOutboxPort(
                store,
                FireOutboxDispatcher(store, FireReportTransport { SendOutcome.Acknowledged }),
                this,
                pollMillis = 10,
            )
            val missionPort = IntegrationMissionPort()
            val safetyOwner = OwnedResumeSafetyEvidenceProvider()
            val safetyGate = FlightSafetyGate()
            val mission = Task7CoordinatorMissionPort(
                AwaitableMissionControl(
                    missionPort,
                    hover = {},
                    scope = this,
                    safetyGate = safetyGate,
                    safetyProvider = safetyOwner,
                    monotonicNow = { testScheduler.currentTime },
                ),
                safetyGate,
                safetyOwner,
                observationSource = CoordinatorFlightObservationSource {
                    val now = testScheduler.currentTime
                    CoordinatorFlightObservation(
                        FlightTelemetrySample(now, now, 0.0, 0.0),
                        FlightSafetySignals(),
                    )
                },
                monotonicNow = { testScheduler.currentTime },
                delayMillis = { delay(it) },
            )
            val locator = VisibleFireLaserLocator(
                NoopMissionHold,
                NoopFlightControl,
                time = object : VisibleFireTime {
                    override fun nowMs() = testScheduler.currentTime
                    override suspend fun delayMs(durationMs: Long) = delay(durationMs)
                },
                localTargetAimer = LocalVisibleTargetAimerPort {
                    LocalTargetAimResult.Failed(LocalTargetAimFailure.TARGET_NOT_REACQUIRED)
                },
                aircraftOsdProvider = AircraftOsdSnapshotProvider {
                    AircraftOsdSnapshot(34.0, 108.0, 50.0, 1, testScheduler.currentTime)
                },
                sourceGenerationGuard = VisibleSourceGenerationGuard { 7 },
            )
            val evidence = FireEvidenceReference(
                "/tmp/task9-evidence.ppm", "b".repeat(64), "image/x-portable-pixmap",
                99_900, 128, 4, 4,
            )
            val coordinator = AgentFireClosedLoopCoordinator(
                storeAdapter,
                outbox,
                mission,
                Task8CoordinatorLocalizationPort(locator),
                armingHealth = { healthy() },
                requestIds = object : CoordinatorRequestIdSource {
                    var next = 1
                    override fun next() = "00000000-0000-4000-8000-${next++.toString().padStart(12, '0')}"
                },
            )
            val result = coordinator.process(
                AgentFireConfirmationEnvelope(
                    "session-integration",
                    "event-integration",
                    "task-integration",
                    7,
                    VisibleConfirmation(
                        DetectionKind.SMOKE,
                        .91f,
                        NormalizedRoi(.3f, .3f, .6f, .6f),
                        1_800,
                        1_900,
                        "policy-v1",
                    ),
                    listOf(evidence),
                ),
            )

            assertEquals(ClosedLoopResult.MissionResumed, result)
            val payloads = store.loadOutbox("event-integration")
            assertTrue(payloads.all { it.payload.contains("\"taskId\":\"task-integration\"") })
            assertTrue(payloads.all { it.payload.contains("\"sourceGeneration\":7") })
            assertTrue(payloads.last { it.state == FireSessionState.RESULT_DURABLE }.payload
                .contains("\"degradedReason\":\"TARGET_NOT_ALIGNED\""))
            assertFalse(payloads.any { it.state == FireSessionState.LASER_MEASURING })
            val durable = checkNotNull(store.loadDurableSession("event-integration"))
            assertEquals("task-integration", durable.taskId)
            assertEquals(7, durable.sourceGeneration)
            assertTrue(durable.recoveryProof != null)
            assertTrue(store.loadEvidence("event-integration").size >= 2)
        } finally {
            store.close()
            context.deleteDatabase(database)
        }
    }

    private class IntegrationMissionPort : MissionControlPort {
        private val mission = MissionExecutionKey(MissionIdentity("mission-1", "route.kmz"), 1)
        private var snapshot = MissionSnapshot(mission, ObservedMissionState.EXECUTING, 1)
        private val listeners = mutableListOf<(MissionSnapshot) -> Unit>()
        override fun snapshot() = snapshot
        override fun queryBreakpoint(mission: MissionExecutionKey, callback: (MissionBreakpoint?, MissionCommandError?) -> Unit): MissionCancellation {
            callback(MissionBreakpoint(0, 2, .4), null)
            return MissionCancellation {}
        }
        override fun pause(mission: MissionExecutionKey, onSubmissionBoundary: (MissionCommandSubmission) -> Unit, callback: (MissionCommandCallback) -> Unit): MissionCommandSubmission =
            submit(mission, ObservedMissionState.INTERRUPTED, onSubmissionBoundary, callback)
        override fun resume(mission: MissionExecutionKey, breakpoint: MissionBreakpoint, onSubmissionBoundary: (MissionCommandSubmission) -> Unit, callback: (MissionCommandCallback) -> Unit): MissionCommandSubmission =
            submit(mission, ObservedMissionState.EXECUTING, onSubmissionBoundary, callback)
        private fun submit(mission: MissionExecutionKey, state: ObservedMissionState, boundary: (MissionCommandSubmission) -> Unit, callback: (MissionCommandCallback) -> Unit): MissionCommandSubmission {
            val submitted = MissionCommandSubmission(mission, snapshot.commandGeneration + 1, MissionCancellation {})
            boundary(submitted)
            snapshot = MissionSnapshot(mission, state, submitted.commandGeneration)
            listeners.toList().forEach { it(snapshot) }
            callback(MissionCommandCallback(mission, submitted.commandGeneration, null))
            return submitted
        }
        override fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation {
            listeners += listener
            listener(snapshot)
            return MissionCancellation { listeners -= listener }
        }
    }

    private object NoopMissionHold : MissionHoldControl {
        override suspend fun holdForConfirmation() = true
        override suspend fun resumeAfterConfirmation() = Unit
    }
    private object NoopFlightControl : FlightControlActionClient {
        override suspend fun startTakeoff() = Unit
        override suspend fun startGoHome() = Unit
        override suspend fun stopGoHome() = Unit
        override suspend fun startAutoLanding() = Unit
        override suspend fun stopAutoLanding() = Unit
        override suspend fun emergencyStop() = Unit
        override suspend fun hover() = Unit
        override suspend fun stopFlyToPoint() = Unit
        override suspend fun sendVirtualStick(key: String, durationMs: Long) = Unit
        override suspend fun flyToPoint(latitude: Double, longitude: Double, height: Double, speed: Double) = Unit
        override suspend fun setNavigationLight(enabled: Boolean) = Unit
    }

    private fun healthy() = CoordinatorArmingHealth(
        true, true, true, true, true, true, true, true, true, false, false,
    )
}
