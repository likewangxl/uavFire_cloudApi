package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.DurableWriteResult
import com.yinxin.uavfir.firedetection.store.MissionRecoveryProofV1
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFireRecoveryCoordinatorTest {
    @Test
    fun `startup closes laser first restarts outbox and never guesses resume`() = runTest {
        val trace = mutableListOf<String>()
        val store = RecoveryStore(trace, listOf(recovery(FireSessionState.LASER_MEASURING, false)))
        val coordinator = AgentFireRecoveryCoordinator(
            store, RecoveryDelivery(trace), RecoveryLocalization(trace), RecoveryMission(trace, RecoveryMissionOutcome.ManualHold(MissionWorkflowFailure.UNKNOWN_MISSION_STATE)),
        )
        val results = coordinator.recover()
        assertEquals(listOf("close", "outbox", "manual"), trace)
        assertTrue(results.single() is RecoveryResult.ManualHold)
    }

    @Test
    fun `durable terminal with exact reconciled mission gets one explicit resume`() = runTest {
        val trace = mutableListOf<String>()
        val store = RecoveryStore(trace, listOf(recovery(FireSessionState.RESULT_DURABLE, true)))
        val coordinator = AgentFireRecoveryCoordinator(
            store, RecoveryDelivery(trace), RecoveryLocalization(trace), RecoveryMission(trace, RecoveryMissionOutcome.Resumed),
        )
        assertEquals(listOf(RecoveryResult.Resumed("event-r")), coordinator.recover())
        assertEquals(1, trace.count { it == "reconcile" })
        assertTrue(trace.contains("stage-MISSION_RESUMED"))
    }

    @Test
    fun `executing recovery with failed durable completion remains uncertain`() = runTest {
        val trace = mutableListOf<String>()
        val store = RecoveryStore(
            trace,
            listOf(recovery(FireSessionState.RESUME_REQUESTED, true)),
            stageResult = DurableWriteResult.Rejected("disk"),
        )
        val coordinator = AgentFireRecoveryCoordinator(
            store,
            RecoveryDelivery(trace),
            RecoveryLocalization(trace),
            RecoveryMission(trace, RecoveryMissionOutcome.Resumed),
        )

        assertEquals(
            listOf(RecoveryResult.DurabilityUncertain("event-r")),
            coordinator.recover(),
        )
        assertEquals(1, trace.count { it == "reconcile" })
    }

    @Test
    fun `executing recovery cannot skip an undurable resume requested audit`() = runTest {
        val trace = mutableListOf<String>()
        val store = RecoveryStore(
            trace,
            listOf(recovery(FireSessionState.RESULT_DURABLE, true)),
            stageResult = DurableWriteResult.Rejected("disk"),
        )
        val coordinator = AgentFireRecoveryCoordinator(
            store,
            RecoveryDelivery(trace),
            RecoveryLocalization(trace),
            RecoveryMission(trace, RecoveryMissionOutcome.Resumed),
        )

        assertEquals(
            listOf(RecoveryResult.DurabilityUncertain("event-r")),
            coordinator.recover(),
        )
        assertTrue(trace.contains("stage-RESUME_REQUESTED"))
        assertTrue(!trace.contains("stage-MISSION_RESUMED"))
    }

    @Test
    fun `resume-stage restart failure does not retry loop`() = runTest {
        val trace = mutableListOf<String>()
        val store = RecoveryStore(trace, listOf(recovery(FireSessionState.RESUME_REQUESTED, true)))
        val coordinator = AgentFireRecoveryCoordinator(
            store, RecoveryDelivery(trace), RecoveryLocalization(trace), RecoveryMission(trace, RecoveryMissionOutcome.ManualHold(MissionWorkflowFailure.RESUME_TIMEOUT)),
        )
        assertTrue(coordinator.recover().single() is RecoveryResult.ManualHold)
        assertEquals(1, trace.count { it == "reconcile" })
    }

    @Test
    fun `every flight-owning restart stage closes hardware and becomes durable manual hold without proof`() = runTest {
        listOf(
            FireSessionState.HOLD_REQUESTED,
            FireSessionState.HOVER_VERIFYING,
            FireSessionState.TARGET_ALIGNING,
            FireSessionState.LASER_MEASURING,
            FireSessionState.RESULT_DURABLE,
            FireSessionState.RESUME_REQUESTED,
        ).forEach { state ->
            val trace = mutableListOf<String>()
            val store = RecoveryStore(trace, listOf(recovery(state, terminal = false)))
            val coordinator = AgentFireRecoveryCoordinator(
                store,
                RecoveryDelivery(trace),
                RecoveryLocalization(trace),
                RecoveryMission(trace, RecoveryMissionOutcome.ManualHold(MissionWorkflowFailure.UNKNOWN_MISSION_STATE)),
            )
            assertTrue("$state", coordinator.recover().single() is RecoveryResult.ManualHold)
            assertEquals("$state", listOf("close", "outbox", "manual"), trace)
        }
    }

    private class RecoveryStore(
        private val trace: MutableList<String>,
        private val sessions: List<CoordinatorRecoverySession>,
        private val stageResult: DurableWriteResult = DurableWriteResult.Written,
    ) : CoordinatorStorePort {
        override suspend fun persistInitial(session: CoordinatorSession, envelope: AgentFireConfirmationEnvelope, request: InitialPersistenceRequest) = error("unused")
        override suspend fun persistStage(session: CoordinatorSession, state: FireSessionState, recoveryProof: MissionRecoveryProofV1?): CoordinatorWrite {
            trace += "stage-$state"
            return CoordinatorWrite(stageResult, 10)
        }
        override suspend fun persistTerminal(session: CoordinatorSession, request: TerminalPersistenceRequest, result: FireLocalizationResult) = error("unused")
        override suspend fun persistManualHold(session: CoordinatorSession, reason: CoordinatorManualHoldReason): CoordinatorWrite { trace += "manual"; return CoordinatorWrite(DurableWriteResult.Written, 9) }
        override suspend fun recordTerminalAck(session: CoordinatorSession, sequence: Long) = false
        override suspend fun loadRecoverySessions() = sessions
    }
    private class RecoveryDelivery(private val trace: MutableList<String>) : CoordinatorOutboxPort {
        override fun trigger(eventId: String, sequence: Long) = Unit
        override suspend fun awaitTerminalAck(eventId: String, sequence: Long) = TerminalAckResult.Offline
        override fun restartPendingDelivery() { trace += "outbox" }
    }
    private class RecoveryLocalization(private val trace: MutableList<String>) : CoordinatorLocalizationPort {
        override suspend fun localize(session: CoordinatorSession, request: FireLocalizationRequest, holdProof: CoordinatorHoldProof, hoverProof: CoordinatorHoverProof, onLaserMeasurementBoundary: suspend () -> Boolean) = error("unused")
        override suspend fun ensureLaserDisabledAndAlignmentClosed(session: CoordinatorSession?) { trace += "close" }
    }
    private class RecoveryMission(private val trace: MutableList<String>, private val outcome: RecoveryMissionOutcome) : CoordinatorMissionPort {
        override suspend fun pauseAndAwait(session: CoordinatorSession, onSubmission: () -> Unit) = error("unused")
        override suspend fun awaitStableHover(session: CoordinatorSession, holdProof: CoordinatorHoldProof) = error("unused")
        override suspend fun resumeAfterSafetyReread(session: CoordinatorSession, holdProof: CoordinatorHoldProof, hoverProof: CoordinatorHoverProof, onSubmission: () -> Unit) = error("unused")
        override suspend fun reconcileForRecovery(session: CoordinatorRecoverySession): RecoveryMissionOutcome { trace += "reconcile"; return outcome }
    }

    companion object {
        private fun recovery(state: FireSessionState, terminal: Boolean) = CoordinatorRecoverySession(
            CoordinatorSession("session-r", "event-r", "task-r", DetectionKind.FIRE, 1, NormalizedRoi(.1f, .1f, .2f, .2f)), state, terminal,
        )
    }
}
