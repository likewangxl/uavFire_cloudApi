package com.yinxin.uavfir.firedetection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class CoordinatorFlightObservation(
    val telemetry: FlightTelemetrySample?,
    val signals: FlightSafetySignals,
)

fun interface CoordinatorFlightObservationSource {
    fun current(): CoordinatorFlightObservation
}

/** Bridges Task 9 to the exact Task 7 hold, hover proof, and safety-claim path. */
class Task7CoordinatorMissionPort internal constructor(
    private val missionControl: AwaitableMissionControl,
    private val safetyGate: FlightSafetyGate,
    private val safetyEvidenceOwner: OwnedResumeSafetyEvidenceProvider,
    private val observationSource: CoordinatorFlightObservationSource,
    private val monotonicNow: () -> Long,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : CoordinatorMissionPort {
    override suspend fun pauseAndAwait(
        session: CoordinatorSession,
        onSubmission: () -> Unit,
    ): MissionPauseOutcome = when (val outcome = missionControl.pause(onSubmission)) {
        is MissionHoldResult.WaylinePaused -> MissionPauseOutcome.Paused(
            CoordinatorHoldProof(
                session.sessionId,
                session.eventId,
                session.generation,
                outcome.token,
            ),
        )
        MissionHoldResult.HoveringNoWayline ->
            MissionPauseOutcome.Failed(MissionWorkflowFailure.UNKNOWN_MISSION_STATE)
        is MissionHoldResult.ManualHold -> MissionPauseOutcome.Failed(outcome.reason.toWorkflowFailure())
    }

    override suspend fun awaitStableHover(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof,
    ): MissionHoverOutcome {
        if (!holdProof.matchesExact(session)) {
            return MissionHoverOutcome.Failed(MissionWorkflowFailure.UNKNOWN_MISSION_STATE)
        }
        val token = holdProof.missionToken
            ?: return MissionHoverOutcome.Failed(MissionWorkflowFailure.MISSING_BREAKPOINT)
        val control = FireControlSessionKey(session.sessionId, session.generation)
        val binding = HoverControlBinding(control, token.mission, token.pausedCommandGeneration)
        val startedAt = monotonicNow()
        while (true) {
            val now = monotonicNow()
            val observation = observationSource.current()
            when (val decision = safetyGate.observeHover(
                binding = binding,
                hoverStartedAtMonotonicMs = startedAt,
                sample = observation.telemetry,
                signals = observation.signals,
                nowMs = now,
            )) {
                HoverSafetyDecision.Waiting -> delayMillis(OBSERVATION_INTERVAL_MS)
                is HoverSafetyDecision.Stable -> return MissionHoverOutcome.Stable(
                    CoordinatorHoverProof(
                        session.sessionId,
                        session.eventId,
                        session.generation,
                        decision.evidence,
                    ),
                )
                is HoverSafetyDecision.ManualHold ->
                    return MissionHoverOutcome.Failed(decision.reason.toWorkflowFailure())
            }
        }
    }

    override suspend fun resumeAfterSafetyReread(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof,
        hoverProof: CoordinatorHoverProof,
        onSubmission: () -> Unit,
    ): MissionResumeOutcome {
        if (!holdProof.matchesExact(session) || !hoverProof.matchesExact(session)) {
            return MissionResumeOutcome.Failed(MissionWorkflowFailure.SAFETY_EVIDENCE_INVALID)
        }
        val token = holdProof.missionToken
            ?: return MissionResumeOutcome.Failed(MissionWorkflowFailure.MISSING_BREAKPOINT)
        val stable = hoverProof.stableEvidence
            ?: return MissionResumeOutcome.Failed(MissionWorkflowFailure.SAFETY_EVIDENCE_INVALID)
        val now = monotonicNow()
        val observation = observationSource.current()
        val control = FireControlSessionKey(session.sessionId, session.generation)
        val evidence = ResumeSafetyEvidence(
            controlSession = control,
            mission = token.mission,
            breakpoint = token.breakpoint,
            pausedCommandGeneration = token.pausedCommandGeneration,
            terminalResultDurable = true,
            laserEnabled = false,
            targetAlignmentClosed = true,
            anotherFireSessionActive = false,
            signals = observation.signals,
            telemetry = observation.telemetry,
            stableHoverEvidence = stable,
            observedAtMonotonicMs = now,
        )
        if (!safetyEvidenceOwner.publish(evidence)) {
            return MissionResumeOutcome.Failed(MissionWorkflowFailure.SAFETY_EVIDENCE_INVALID)
        }
        return try {
            when (val result = missionControl.resume(token, control, onSubmission)) {
                MissionResumeResult.Resumed -> MissionResumeOutcome.Resumed
                is MissionResumeResult.ManualHold ->
                    MissionResumeOutcome.Failed(result.reason.toWorkflowFailure())
            }
        } catch (cancelled: CancellationException) {
            safetyEvidenceOwner.invalidate(control)
            throw cancelled
        } catch (_: Exception) {
            safetyEvidenceOwner.invalidate(control)
            MissionResumeOutcome.Failed(MissionWorkflowFailure.RESUME_FAILED)
        }
    }

    override suspend fun reconcileForRecovery(
        session: CoordinatorRecoverySession,
    ): RecoveryMissionOutcome = RecoveryMissionOutcome.ManualHold(
        // Task 6 does not yet persist Task 7's exact opaque token. Never infer it.
        MissionWorkflowFailure.UNKNOWN_MISSION_STATE,
    )

    private fun CoordinatorHoldProof.matchesExact(session: CoordinatorSession): Boolean =
        sessionId == session.sessionId && eventId == session.eventId && generation == session.generation

    private fun CoordinatorHoverProof.matchesExact(session: CoordinatorSession): Boolean =
        sessionId == session.sessionId && eventId == session.eventId && generation == session.generation

    companion object { const val OBSERVATION_INTERVAL_MS = 100L }
}

private fun FlightSafetyReason.toWorkflowFailure(): MissionWorkflowFailure = when (this) {
    FlightSafetyReason.MANUAL_CONTROL_TAKEOVER -> MissionWorkflowFailure.MANUAL_TAKEOVER
    FlightSafetyReason.LOW_BATTERY -> MissionWorkflowFailure.LOW_BATTERY
    FlightSafetyReason.RETURN_TO_HOME_ACTIVE -> MissionWorkflowFailure.RETURN_TO_HOME
    FlightSafetyReason.OBSTACLE_AVOIDANCE_INTERVENTION -> MissionWorkflowFailure.OBSTACLE_AVOIDANCE
    FlightSafetyReason.FLIGHT_ERROR -> MissionWorkflowFailure.FLIGHT_ERROR
    FlightSafetyReason.MISSING_BREAKPOINT,
    FlightSafetyReason.INVALID_BREAKPOINT,
    -> MissionWorkflowFailure.MISSING_BREAKPOINT
    FlightSafetyReason.BREAKPOINT_MISMATCH -> MissionWorkflowFailure.BREAKPOINT_MISMATCH
    FlightSafetyReason.HOVER_STABILITY_TIMEOUT -> MissionWorkflowFailure.HOVER_TIMEOUT
    FlightSafetyReason.PAUSE_COMMAND_FAILED,
    FlightSafetyReason.HOVER_COMMAND_FAILED,
    -> MissionWorkflowFailure.PAUSE_FAILED
    FlightSafetyReason.RESUME_COMMAND_FAILED,
    FlightSafetyReason.RESUME_OUTCOME_UNKNOWN,
    -> MissionWorkflowFailure.RESUME_FAILED
    else -> MissionWorkflowFailure.SAFETY_EVIDENCE_INVALID
}
