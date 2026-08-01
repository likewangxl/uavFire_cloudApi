package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.DurableWriteResult
import com.yinxin.uavfir.firedetection.store.MissionRecoveryProofV1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentFireClosedLoopCoordinatorTest {
    @Test
    fun `fire precise success preserves ordering and resumes`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        val result = fixture.coordinator.process(fixture.envelope)

        assertEquals(ClosedLoopResult.MissionResumed, result)
        assertEquals(
            listOf("initial", "kick-1", "pause", "hover", "align-stage", "localize", "terminal", "ack", "close", "safety", "resume"),
            fixture.trace,
        )
        assertEquals(LocationStatus.PRECISE, fixture.store.terminal?.locationStatus)
        assertEquals(1, fixture.delivery.kicks.count { it.second == 1L })
    }

    @Test
    fun `smoke uses the identical precise workflow and retains kind`() = runTest {
        val fixture = fixture(DetectionKind.SMOKE, precise(DetectionKind.SMOKE))
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
        assertEquals(DetectionKind.SMOKE, fixture.store.initial!!.confirmation.kind)
        assertEquals(DetectionKind.SMOKE, fixture.localization.lastRequest!!.kind)
    }

    @Test
    fun `laser failure with fresh OSD is a degraded durable terminal then resumes`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, degraded())
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
        assertEquals(LocationStatus.DEGRADED_OSD, fixture.store.terminal!!.locationStatus)
        assertEquals(GeoMethod.AIRCRAFT_OBSERVATION, fixture.store.terminal!!.geoMethod)
    }

    @Test
    fun `invalid OSD manual-hold result never resumes`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, FireLocalizationResult.ManualHold(DetectionKind.FIRE, FireLocalizationFailure.AIRCRAFT_OSD_UNAVAILABLE))
        assertTrue(fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.ManualHold)
        assertEquals(0, fixture.mission.resumeCalls)
        assertEquals(CoordinatorManualHoldReason.AIRCRAFT_OSD_UNAVAILABLE, fixture.store.manualHold)
    }

    @Test
    fun `first alert and pause start independently after initial commit`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.delivery.blockInitial = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        assertTrue(fixture.delivery.kicks.contains(fixture.envelope.eventId to 1L))
        assertEquals(1, fixture.mission.pauseCalls)
        assertFalse(pending.isCompleted)
        fixture.delivery.blockInitial!!.complete(Unit)
        runCurrent()
        assertEquals(ClosedLoopResult.MissionResumed, pending.await())
    }

    @Test
    fun `terminal ACK over one second cannot hold the aircraft`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.delivery.ack = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ClosedLoopResult.MissionResumed, pending.await())
        assertEquals(1, fixture.mission.resumeCalls)
    }

    @Test
    fun `exact terminal ACK within one second is recorded for the same sequence`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.delivery.autoAcknowledge = true
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
        assertTrue(fixture.store.ackRecorded)
    }

    @Test
    fun `ROI aim and laser failures with valid OSD all degrade and resume`() = runTest {
        listOf(
            FireLocalizationFailure.TARGET_NOT_ALIGNED,
            FireLocalizationFailure.TARGET_DETECTION_TIMEOUT,
            FireLocalizationFailure.LASER_ENABLE_FAILED,
            FireLocalizationFailure.LASER_SAMPLES_UNAVAILABLE,
            FireLocalizationFailure.LASER_SAMPLES_INVALID,
        ).forEach { reason ->
            val result = FireLocalizationResult.DegradedOsd(
                DetectionKind.FIRE,
                reason,
                AircraftOsdSnapshot(34.0, 108.0, 10.0, 1, 100),
            )
            val fixture = fixture(DetectionKind.FIRE, result)
            assertEquals("$reason", ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
            assertEquals(LocationStatus.DEGRADED_OSD, fixture.store.terminal!!.locationStatus)
        }
    }

    @Test
    fun `backend offline cannot hold the aircraft after local terminal durability`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.delivery.ackResult = TerminalAckResult.Offline
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
    }

    @Test
    fun `storage failure before pause performs no flight action`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.store.initialResult = DurableWriteResult.Rejected("disk")
        assertTrue(fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.Rejected)
        assertEquals(0, fixture.mission.pauseCalls)
        assertTrue(fixture.delivery.kicks.isEmpty())
    }

    @Test
    fun `terminal persistence failure while held enters durable manual hold`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.store.terminalResult = DurableWriteResult.Rejected("disk")
        assertTrue(fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.ManualHold)
        assertEquals(CoordinatorManualHoldReason.STORAGE_FAILURE, fixture.store.manualHold)
        assertEquals(0, fixture.mission.resumeCalls)
    }

    @Test
    fun `pause hover safety and resume failures enter manual hold without retry`() = runTest {
        listOf(
            MissionWorkflowFailure.PAUSE_TIMEOUT,
            MissionWorkflowFailure.HOVER_TIMEOUT,
            MissionWorkflowFailure.MANUAL_TAKEOVER,
            MissionWorkflowFailure.LOW_BATTERY,
            MissionWorkflowFailure.RETURN_TO_HOME,
            MissionWorkflowFailure.OBSTACLE_AVOIDANCE,
            MissionWorkflowFailure.FLIGHT_ERROR,
            MissionWorkflowFailure.UNKNOWN_MISSION_STATE,
            MissionWorkflowFailure.MISSING_BREAKPOINT,
        ).forEach { failure ->
            val fixture = fixture(DetectionKind.FIRE, precise())
            fixture.mission.pauseFailure = failure
            assertTrue("$failure", fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.ManualHold)
            assertEquals(0, fixture.mission.resumeCalls)
        }
        val resume = fixture(DetectionKind.FIRE, precise())
        resume.mission.resumeFailure = MissionWorkflowFailure.RESUME_TIMEOUT
        assertTrue(resume.coordinator.process(resume.envelope) is ClosedLoopResult.ResumeOutcomeUnknown)
        assertEquals(1, resume.mission.resumeCalls)
    }

    @Test
    fun `manual takeover during localization cancels work and enters manual hold`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.localization.block = CompletableDeferred()
        fixture.mission.invalidation = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        fixture.mission.invalidation!!.complete(MissionWorkflowFailure.MANUAL_TAKEOVER)
        runCurrent()
        assertTrue(pending.await() is ClosedLoopResult.ManualHold)
        assertEquals(CoordinatorManualHoldReason.MANUAL_TAKEOVER, fixture.store.manualHold)
        assertEquals(0, fixture.mission.resumeCalls)
    }

    @Test
    fun `detector failure during ACK cancels wait and holds`() = runTest {
        var health = CoordinatorRuntimeHealth(true, true, true)
        val fixture = fixture(DetectionKind.FIRE, precise(), runtimeHealth = { health })
        fixture.delivery.ack = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        health = CoordinatorRuntimeHealth(false, true, true)
        advanceTimeBy(101)
        runCurrent()
        assertTrue(pending.await() is ClosedLoopResult.ManualHold)
        assertEquals(CoordinatorManualHoldReason.DETECTOR_FAILURE, fixture.store.manualHold)
        assertEquals(0, fixture.mission.resumeCalls)
    }

    @Test
    fun `runtime health probe exception during held work fails closed`() = runTest {
        var healthy = true
        val fixture = fixture(
            DetectionKind.FIRE,
            precise(),
            runtimeHealth = {
                if (healthy) CoordinatorRuntimeHealth(true, true, true) else error("probe")
            },
        )
        fixture.delivery.ack = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        healthy = false
        advanceTimeBy(101)
        runCurrent()

        assertTrue(pending.await() is ClosedLoopResult.ManualHold)
        assertEquals(CoordinatorManualHoldReason.DETECTOR_FAILURE, fixture.store.manualHold)
    }

    @Test
    fun `pre-laser degradation never fabricates laser measuring`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, degraded(FireLocalizationFailure.TARGET_NOT_ALIGNED))
        fixture.localization.invokeLaserBoundary = false
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
        assertFalse(fixture.store.stages.contains(FireSessionState.LASER_MEASURING))
    }

    @Test
    fun `cleanup failure persists final laser uncertain reason`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, FireLocalizationResult.ManualHold(DetectionKind.FIRE, FireLocalizationFailure.AIRCRAFT_OSD_UNAVAILABLE))
        fixture.localization.cleanupFails = true
        val result = fixture.coordinator.process(fixture.envelope) as ClosedLoopResult.ManualHold
        assertEquals(CoordinatorManualHoldReason.LASER_DISABLE_UNCERTAIN, result.reason)
        assertEquals(result.reason, fixture.store.manualHold)
    }

    @Test
    fun `resume submitted then executing reconciliation never writes manual hold`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.mission.resumeFailure = MissionWorkflowFailure.RESUME_TIMEOUT
        fixture.mission.reconciliation = ResumeReconciliationOutcome.Executing
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
        assertEquals(null, fixture.store.manualHold)
    }

    @Test
    fun `confirmed executing with final persistence failure is durability uncertain not manual hold`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.store.stageFailures += FireSessionState.MISSION_RESUMED
        assertTrue(
            fixture.coordinator.process(fixture.envelope) is
                ClosedLoopResult.MissionExecutingDurabilityUncertain,
        )
        assertEquals(null, fixture.store.manualHold)
    }

    @Test
    fun `every pre-resume progress write failure fails closed`() = runTest {
        listOf(
            FireSessionState.HOLD_REQUESTED,
            FireSessionState.HOVER_VERIFYING,
            FireSessionState.TARGET_ALIGNING,
            FireSessionState.LASER_MEASURING,
            FireSessionState.RESUME_REQUESTED,
        ).forEach { state ->
            val fixture = fixture(DetectionKind.FIRE, precise())
            fixture.store.stageFailures += state
            val result = fixture.coordinator.process(fixture.envelope)
            if (state == FireSessionState.HOLD_REQUESTED && fixture.mission.pauseSubmissions == 0) {
                assertTrue("$state", result is ClosedLoopResult.Rejected)
            } else {
                assertTrue("$state", result is ClosedLoopResult.ManualHold)
            }
            assertEquals(0, fixture.mission.resumeCalls.takeIf { state != FireSessionState.RESUME_REQUESTED } ?: 0)
        }
    }

    @Test
    fun `pause starts while hold progress transaction is blocked after initial durability`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.store.holdStageBlock = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        assertEquals(1, fixture.mission.pauseCalls)
        assertTrue(fixture.delivery.kicks.contains(fixture.envelope.eventId to 1L))
        fixture.store.holdStageBlock!!.complete(Unit)
        assertEquals(ClosedLoopResult.MissionResumed, pending.await())
    }

    @Test
    fun `failed hold persistence cancels a pause that has not submitted`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.store.stageFailures += FireSessionState.HOLD_REQUESTED
        fixture.mission.beforePauseSubmission = CompletableDeferred()

        assertTrue(fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.Rejected)
        assertEquals(0, fixture.mission.pauseSubmissions)
        assertEquals(null, fixture.store.manualHold)
    }

    @Test
    fun `cancellation after durable hold intent before pause submission is durably closed`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.mission.beforePauseSubmission = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertEquals(CoordinatorManualHoldReason.CANCELLED_AFTER_DURABLE_HOLD_INTENT, fixture.store.manualHold)
        assertEquals(0, fixture.mission.pauseSubmissions)
    }

    @Test
    fun `second confirmation is typed busy while first session owns flight`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.mission.pauseBlock = CompletableDeferred()
        val first = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        val second = fixture.coordinator.process(fixture.envelope.copy(eventId = "event-2", sessionId = "session-2"))
        assertTrue(second is ClosedLoopResult.Busy)
        fixture.mission.pauseBlock!!.complete(Unit)
        assertEquals(ClosedLoopResult.MissionResumed, first.await())
    }

    @Test
    fun `stale cross-session localization and ACK are rejected`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.localization.overrideIdentity = "other-session" to "other-event"
        assertTrue(fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.ManualHold)
        assertEquals(0, fixture.mission.resumeCalls)

        val ack = fixture(DetectionKind.FIRE, precise())
        ack.delivery.ackResult = TerminalAckResult.Acknowledged("other-event", 99)
        assertEquals(ClosedLoopResult.MissionResumed, ack.coordinator.process(ack.envelope))
        assertFalse(ack.store.ackRecorded)
    }

    @Test
    fun `stale source generation localization enters manual hold`() = runTest {
        val precise = fixture(DetectionKind.FIRE, precise().copy(sourceGeneration = 8))
        assertTrue(precise.coordinator.process(precise.envelope) is ClosedLoopResult.ManualHold)
        assertEquals(CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE, precise.store.manualHold)
        assertEquals(0, precise.mission.resumeCalls)

        val degraded = fixture(
            DetectionKind.FIRE,
            FireLocalizationResult.DegradedOsd(
                DetectionKind.FIRE,
                FireLocalizationFailure.SOURCE_GENERATION_CHANGED,
                AircraftOsdSnapshot(34.0, 108.0, 10.0, 1, 100),
            ),
        )
        assertTrue(degraded.coordinator.process(degraded.envelope) is ClosedLoopResult.ManualHold)
        assertEquals(CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE, degraded.store.manualHold)
        assertEquals(0, degraded.mission.resumeCalls)
    }

    @Test
    fun `default off and every arming gate fail closed`() = runTest {
        CoordinatorArmingHealth::class.java.declaredFields
        val base = healthy()
        val unhealthy = listOf(
            base.copy(featureEnabled = false),
            base.copy(backendMonitoringEnabled = false),
            base.copy(visibleSourceActive = false),
            base.copy(sourceGenerationValid = false),
            base.copy(detectorHealthy = false),
            base.copy(storeHealthy = false),
            base.copy(outboxHealthy = false),
            base.copy(missionAdaptersHealthy = false),
            base.copy(safetyAdaptersHealthy = false),
            base.copy(manualHoldActive = true),
            base.copy(competingOwnerActive = true),
        )
        unhealthy.forEach { health ->
            val fixture = fixture(DetectionKind.FIRE, precise(), health)
            assertTrue(fixture.coordinator.process(fixture.envelope) is ClosedLoopResult.Disarmed)
            assertEquals(0, fixture.store.initialCalls)
        }
    }

    @Test
    fun `arming probe exception releases ownership for the next request`() = runTest {
        var probeFails = true
        val fixture = fixture(
            DetectionKind.FIRE,
            precise(),
            armingHealth = {
                if (probeFails) error("probe") else healthy()
            },
        )
        assertEquals(
            ClosedLoopResult.Rejected("arming-health-unavailable"),
            fixture.coordinator.process(fixture.envelope),
        )
        probeFails = false
        assertEquals(ClosedLoopResult.MissionResumed, fixture.coordinator.process(fixture.envelope))
    }

    @Test
    fun `cancellation after pause submission durably enters manual hold`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.mission.pauseBlock = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertEquals(CoordinatorManualHoldReason.CANCELLED_AFTER_FLIGHT_SUBMISSION, fixture.store.manualHold)
    }

    @Test
    fun `cancellation before durable initial write abandons without flight action or manual hold`() = runTest {
        val fixture = fixture(DetectionKind.FIRE, precise())
        fixture.store.initialBlock = CompletableDeferred()
        val pending = async { fixture.coordinator.process(fixture.envelope) }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(0, fixture.mission.pauseCalls)
        assertEquals(null, fixture.store.manualHold)
    }

    private fun fixture(
        kind: DetectionKind,
        localizationResult: FireLocalizationResult,
        health: CoordinatorArmingHealth = healthy(),
        runtimeHealth: () -> CoordinatorRuntimeHealth = { CoordinatorRuntimeHealth(true, true, true) },
        armingHealth: (() -> CoordinatorArmingHealth)? = null,
    ): Fixture {
        val trace = mutableListOf<String>()
        val store = FakeStore(trace)
        val delivery = FakeDelivery(trace)
        val mission = FakeMission(trace)
        val localization = FakeLocalization(trace, localizationResult)
        val coordinator = AgentFireClosedLoopCoordinator(
            store = store,
            delivery = delivery,
            mission = mission,
            localization = localization,
            armingHealth = armingHealth ?: { health },
            runtimeHealth = runtimeHealth,
            requestIds = object : CoordinatorRequestIdSource {
                private var next = 1
                override fun next(): String = "00000000-0000-4000-8000-${next++.toString().padStart(12, '0')}"
            },
            terminalAckTimeoutMs = 1_000,
        )
        return Fixture(coordinator, envelope(kind), store, delivery, mission, localization, trace)
    }

    private data class Fixture(
        val coordinator: AgentFireClosedLoopCoordinator,
        val envelope: AgentFireConfirmationEnvelope,
        val store: FakeStore,
        val delivery: FakeDelivery,
        val mission: FakeMission,
        val localization: FakeLocalization,
        val trace: MutableList<String>,
    )

    private class FakeStore(private val trace: MutableList<String>) : CoordinatorStorePort {
        var initialResult: DurableWriteResult = DurableWriteResult.Written
        var terminalResult: DurableWriteResult = DurableWriteResult.Written
        var initialCalls = 0
        var initial: InitialPersistenceRequest? = null
        var terminal: TerminalPersistenceRequest? = null
        var manualHold: CoordinatorManualHoldReason? = null
        var ackRecorded = false
        val stages = mutableListOf<FireSessionState>()
        val stageFailures = mutableSetOf<FireSessionState>()
        var holdStageBlock: CompletableDeferred<Unit>? = null
        var initialBlock: CompletableDeferred<Unit>? = null
        private var nextSequence = 2L
        override suspend fun persistInitial(session: CoordinatorSession, envelope: AgentFireConfirmationEnvelope, request: InitialPersistenceRequest): CoordinatorWrite {
            initialBlock?.await()
            trace += "initial"; initialCalls++; initial = request
            return CoordinatorWrite(initialResult, 1)
        }
        override suspend fun persistStage(session: CoordinatorSession, state: FireSessionState, recoveryProof: MissionRecoveryProofV1?): CoordinatorWrite {
            stages += state
            if (state == FireSessionState.HOLD_REQUESTED) holdStageBlock?.await()
            if (state == FireSessionState.TARGET_ALIGNING) trace += "align-stage"
            return CoordinatorWrite(
                if (state in stageFailures) DurableWriteResult.Rejected("stage-failure")
                else DurableWriteResult.Written,
                nextSequence++,
            )
        }
        override suspend fun persistTerminal(session: CoordinatorSession, request: TerminalPersistenceRequest, result: FireLocalizationResult): CoordinatorWrite {
            trace += "terminal"; terminal = request
            return CoordinatorWrite(terminalResult, nextSequence++)
        }
        override suspend fun persistManualHold(session: CoordinatorSession, reason: CoordinatorManualHoldReason): CoordinatorWrite {
            manualHold = reason
            return CoordinatorWrite(DurableWriteResult.Written, nextSequence++)
        }
        override suspend fun recordTerminalAck(session: CoordinatorSession, sequence: Long): Boolean { ackRecorded = true; return true }
        override suspend fun loadRecoverySessions(): List<CoordinatorRecoverySession> = emptyList()
    }

    private class FakeDelivery(private val trace: MutableList<String>) : CoordinatorOutboxPort {
        val kicks = mutableListOf<Pair<String, Long>>()
        var blockInitial: CompletableDeferred<Unit>? = null
        var ack: CompletableDeferred<TerminalAckResult>? = null
        var ackResult: TerminalAckResult = TerminalAckResult.Offline
        var autoAcknowledge = false
        override fun trigger(eventId: String, sequence: Long) {
            kicks += eventId to sequence
            if (sequence == 1L) trace += "kick-1"
        }
        override suspend fun awaitTerminalAck(eventId: String, sequence: Long): TerminalAckResult {
            blockInitial?.await()
            trace += "ack"
            return ack?.await() ?: if (autoAcknowledge) {
                TerminalAckResult.Acknowledged(eventId, sequence)
            } else ackResult
        }
        override fun restartPendingDelivery() = Unit
    }

    private class FakeMission(private val trace: MutableList<String>) : CoordinatorMissionPort {
        var pauseCalls = 0
        var pauseSubmissions = 0
        var resumeCalls = 0
        var pauseFailure: MissionWorkflowFailure? = null
        var resumeFailure: MissionWorkflowFailure? = null
        var pauseBlock: CompletableDeferred<Unit>? = null
        var beforePauseSubmission: CompletableDeferred<Unit>? = null
        var invalidation: CompletableDeferred<MissionWorkflowFailure>? = null
        var reconciliation: ResumeReconciliationOutcome = ResumeReconciliationOutcome.Unknown
        override suspend fun pauseAndAwait(session: CoordinatorSession, onSubmission: () -> Unit): MissionPauseOutcome {
            trace += "pause"; pauseCalls++; beforePauseSubmission?.await(); onSubmission(); pauseSubmissions++; pauseBlock?.await()
            return pauseFailure?.let(MissionPauseOutcome::Failed) ?: MissionPauseOutcome.Paused(
                CoordinatorHoldProof(session.sessionId, session.eventId, session.generation),
            )
        }
        override suspend fun awaitStableHover(session: CoordinatorSession, holdProof: CoordinatorHoldProof): MissionHoverOutcome {
            trace += "hover"
            return MissionHoverOutcome.Stable(
                CoordinatorHoverProof(session.sessionId, session.eventId, session.generation),
            )
        }
        override suspend fun resumeAfterSafetyReread(session: CoordinatorSession, holdProof: CoordinatorHoldProof, hoverProof: CoordinatorHoverProof, onSubmission: () -> Unit): MissionResumeOutcome {
            trace += "safety"; onSubmission(); trace += "resume"; resumeCalls++
            return resumeFailure?.let(MissionResumeOutcome::Failed) ?: MissionResumeOutcome.Resumed
        }
        override suspend fun reconcileForRecovery(session: CoordinatorRecoverySession): RecoveryMissionOutcome = RecoveryMissionOutcome.ManualHold(MissionWorkflowFailure.UNKNOWN_MISSION_STATE)
        override suspend fun awaitHeldInvalidation(session: CoordinatorSession, holdProof: CoordinatorHoldProof): MissionWorkflowFailure =
            invalidation?.await() ?: awaitCancellation()
        override suspend fun reconcileAfterResumeSubmission(session: CoordinatorSession, holdProof: CoordinatorHoldProof) = reconciliation
    }

    private class FakeLocalization(
        private val trace: MutableList<String>,
        private val result: FireLocalizationResult,
    ) : CoordinatorLocalizationPort {
        var lastRequest: FireLocalizationRequest? = null
        var overrideIdentity: Pair<String, String>? = null
        var block: CompletableDeferred<Unit>? = null
        var invokeLaserBoundary = true
        var cleanupFails = false
        override suspend fun localize(session: CoordinatorSession, request: FireLocalizationRequest, holdProof: CoordinatorHoldProof, hoverProof: CoordinatorHoverProof, onLaserMeasurementBoundary: suspend () -> Boolean): BoundLocalizationResult {
            lastRequest = request
            if (invokeLaserBoundary) onLaserMeasurementBoundary()
            trace += "localize"
            block?.await()
            val identity = overrideIdentity
            return BoundLocalizationResult(identity?.first ?: session.sessionId, identity?.second ?: session.eventId, session.generation, result)
        }
        override suspend fun ensureLaserDisabledAndAlignmentClosed(session: CoordinatorSession?) {
            trace += "close"
            if (cleanupFails) error("cleanup")
        }
    }

    companion object {
        private fun healthy() = CoordinatorArmingHealth(true, true, true, true, true, true, true, true, true, false, false)
        private fun envelope(kind: DetectionKind) = AgentFireConfirmationEnvelope(
            sessionId = "session-1", eventId = "event-1", taskId = "task-1", sourceGeneration = 7,
            confirmation = VisibleConfirmation(kind, .9f, NormalizedRoi(.4f, .4f, .6f, .6f), 100, 200, "agent-visible-v1"),
        )
        private fun precise(kind: DetectionKind = DetectionKind.FIRE) = FireLocalizationResult.Precise(
            kind, 34.0, 108.0, 10.0, 100.0, 5.0, emptyList(), NormalizedRoi(.4f, .4f, .6f, .6f), 7,
        )
        private fun degraded(reason: FireLocalizationFailure = FireLocalizationFailure.LASER_SAMPLES_INVALID) = FireLocalizationResult.DegradedOsd(
            DetectionKind.FIRE, reason,
            AircraftOsdSnapshot(34.0, 108.0, 10.0, 1, 100),
        )
    }
}
