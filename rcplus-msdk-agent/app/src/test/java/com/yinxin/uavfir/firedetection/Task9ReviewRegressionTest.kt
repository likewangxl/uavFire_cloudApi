package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.FireLocalizationDegradedReason
import com.yinxin.uavfir.firedetection.store.MissionRecoveryProofV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract-level RED tests for the independent Task 9 review. */
class Task9ReviewRegressionTest {
    @Test
    fun `held runtime health is typed and fail closed`() {
        assertTrue(CoordinatorRuntimeHealth(true, true, true).healthy)
        assertFalse(CoordinatorRuntimeHealth(false, true, true).healthy)
        assertEquals(
            CoordinatorManualHoldReason.DETECTOR_FAILURE,
            CoordinatorRuntimeHealth(false, true, true).failureReason,
        )
    }

    @Test
    fun `resume reconciliation distinguishes executing held and unknown`() {
        val outcomes: List<ResumeReconciliationOutcome> = listOf(
            ResumeReconciliationOutcome.Executing,
            ResumeReconciliationOutcome.Held,
            ResumeReconciliationOutcome.Unknown,
        )
        assertEquals(3, outcomes.distinct().size)
        val uncertain: ClosedLoopResult =
            ClosedLoopResult.MissionExecutingDurabilityUncertain("event-1")
        assertEquals(ClosedLoopResult.MissionExecutingDurabilityUncertain("event-1"), uncertain)
    }

    @Test
    fun `recovery proof carries exact versioned mission breakpoint identity`() {
        val proof = MissionRecoveryProofV1(
            missionId = "mission-1",
            missionFileName = "route.kmz",
            missionGeneration = 9,
            waylineId = 1,
            waypointId = 2,
            segmentProgress = 0.4,
            latitude = 34.0,
            longitude = 108.0,
            altitude = 30.0,
            recoverAction = "GoBackToRecordPoint",
            pausedCommandGeneration = 12,
            holdGeneration = 3,
        )
        assertEquals(1, proof.version)
        assertTrue(proof.toMissionHoldToken().breakpoint.isValid)
    }

    @Test
    fun `degraded reasons are closed durable values`() {
        assertEquals(
            FireLocalizationFailure.LASER_SAMPLES_INVALID,
            FireLocalizationDegradedReason.LASER_SAMPLES_INVALID.failure,
        )
    }

    @Test
    fun `busy and disarmed outcomes have a bounded observable sink`() {
        val sink = BoundedCoordinatorOutcomeRecorder(capacity = 2)
        sink.record("event-1", ClosedLoopResult.Busy("owner"))
        sink.record("event-2", ClosedLoopResult.Rejected("bad"))
        sink.record("event-3", ClosedLoopResult.Busy("owner"))
        assertEquals(listOf("event-2", "event-3"), sink.snapshot().map { it.eventId })
    }

    @Test
    fun `startup durability uncertainty cannot authorize new confirmations`() {
        assertTrue(RecoveryResult.Resumed("event-1").safeToAcceptNewConfirmations)
        assertTrue(RecoveryResult.ManualHold("event-1", true).safeToAcceptNewConfirmations)
        assertFalse(RecoveryResult.ManualHold("event-1", false).safeToAcceptNewConfirmations)
        assertFalse(RecoveryResult.DurabilityUncertain("event-1").safeToAcceptNewConfirmations)
    }
}
