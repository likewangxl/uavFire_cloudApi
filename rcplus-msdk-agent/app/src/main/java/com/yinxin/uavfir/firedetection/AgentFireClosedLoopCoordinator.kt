package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.DurableWriteResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AgentFireConfirmationEnvelope(
    val sessionId: String,
    val eventId: String,
    val taskId: String,
    val sourceGeneration: Long,
    val confirmation: VisibleConfirmation,
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank() && taskId.isNotBlank())
        require(sourceGeneration > 0)
    }
}

data class CoordinatorSession(
    val sessionId: String,
    val eventId: String,
    val taskId: String,
    val kind: DetectionKind,
    val generation: Long,
    val initialRoi: NormalizedRoi,
    val sourceGeneration: Long = 1,
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank() && taskId.isNotBlank())
        require(generation > 0 && sourceGeneration > 0)
    }
}

data class CoordinatorRecoverySession(
    val session: CoordinatorSession,
    val persistedState: FireSessionState,
    val terminalResultDurable: Boolean,
)

data class CoordinatorWrite(val result: DurableWriteResult, val sequence: Long) {
    val durable: Boolean
        get() = result == DurableWriteResult.Written || result == DurableWriteResult.ExactDuplicate
}

enum class CoordinatorManualHoldReason {
    PAUSE_TIMEOUT,
    PAUSE_FAILED,
    HOVER_TIMEOUT,
    MANUAL_TAKEOVER,
    LOW_BATTERY,
    RETURN_TO_HOME,
    OBSTACLE_AVOIDANCE,
    FLIGHT_ERROR,
    UNKNOWN_MISSION_STATE,
    MISSING_BREAKPOINT,
    BREAKPOINT_MISMATCH,
    ROI_OR_LASER_FAILURE,
    AIRCRAFT_OSD_UNAVAILABLE,
    STORAGE_FAILURE,
    DETECTOR_FAILURE,
    LASER_DISABLE_UNCERTAIN,
    RESUME_FAILURE,
    CANCELLED_AFTER_FLIGHT_SUBMISSION,
    STARTUP_RECOVERY_UNCERTAIN,
    STALE_SESSION_EVIDENCE,
}

enum class MissionWorkflowFailure {
    PAUSE_TIMEOUT,
    PAUSE_FAILED,
    HOVER_TIMEOUT,
    MANUAL_TAKEOVER,
    LOW_BATTERY,
    RETURN_TO_HOME,
    OBSTACLE_AVOIDANCE,
    FLIGHT_ERROR,
    UNKNOWN_MISSION_STATE,
    MISSING_BREAKPOINT,
    BREAKPOINT_MISMATCH,
    RESUME_FAILED,
    RESUME_TIMEOUT,
    SAFETY_EVIDENCE_INVALID,
}

sealed interface MissionPauseOutcome {
    data class Paused(val holdProof: CoordinatorHoldProof) : MissionPauseOutcome
    data class Failed(val reason: MissionWorkflowFailure) : MissionPauseOutcome
}

sealed interface MissionHoverOutcome {
    data class Stable(val hoverProof: CoordinatorHoverProof) : MissionHoverOutcome
    data class Failed(val reason: MissionWorkflowFailure) : MissionHoverOutcome
}

sealed interface MissionResumeOutcome {
    data object Resumed : MissionResumeOutcome
    data class Failed(val reason: MissionWorkflowFailure) : MissionResumeOutcome
}

sealed interface RecoveryMissionOutcome {
    data object Resumed : RecoveryMissionOutcome
    data class ManualHold(val reason: MissionWorkflowFailure) : RecoveryMissionOutcome
}

data class BoundLocalizationResult(
    val sessionId: String,
    val eventId: String,
    val generation: Long,
    val result: FireLocalizationResult,
)

data class CoordinatorHoldProof(
    val sessionId: String,
    val eventId: String,
    val generation: Long,
    val missionToken: MissionHoldToken? = null,
) {
    init { require(sessionId.isNotBlank() && eventId.isNotBlank() && generation > 0) }
}

data class CoordinatorHoverProof(
    val sessionId: String,
    val eventId: String,
    val generation: Long,
    val stableEvidence: StableHoverEvidence? = null,
) {
    init { require(sessionId.isNotBlank() && eventId.isNotBlank() && generation > 0) }
}

sealed interface TerminalAckResult {
    data class Acknowledged(val eventId: String, val sequence: Long) : TerminalAckResult
    data object Offline : TerminalAckResult
    data object TimedOut : TerminalAckResult
    data object Rejected : TerminalAckResult
}

data class CoordinatorArmingHealth(
    val featureEnabled: Boolean,
    val backendMonitoringEnabled: Boolean,
    val visibleSourceActive: Boolean,
    val sourceGenerationValid: Boolean,
    val detectorHealthy: Boolean,
    val storeHealthy: Boolean,
    val outboxHealthy: Boolean,
    val missionAdaptersHealthy: Boolean,
    val safetyAdaptersHealthy: Boolean,
    val manualHoldActive: Boolean,
    val competingOwnerActive: Boolean,
) {
    val armed: Boolean
        get() = featureEnabled && backendMonitoringEnabled && visibleSourceActive &&
            sourceGenerationValid && detectorHealthy && storeHealthy && outboxHealthy &&
            missionAdaptersHealthy && safetyAdaptersHealthy && !manualHoldActive &&
            !competingOwnerActive
}

fun interface CoordinatorRequestIdSource {
    fun next(): String
}

interface CoordinatorStorePort {
    suspend fun persistInitial(
        envelope: AgentFireConfirmationEnvelope,
        request: InitialPersistenceRequest,
    ): CoordinatorWrite

    suspend fun persistStage(session: CoordinatorSession, state: FireSessionState): CoordinatorWrite

    suspend fun persistTerminal(
        session: CoordinatorSession,
        request: TerminalPersistenceRequest,
        result: FireLocalizationResult,
    ): CoordinatorWrite

    suspend fun persistManualHold(
        session: CoordinatorSession,
        reason: CoordinatorManualHoldReason,
    ): CoordinatorWrite

    suspend fun recordTerminalAck(session: CoordinatorSession, sequence: Long): Boolean
    suspend fun loadRecoverySessions(): List<CoordinatorRecoverySession>
}

interface CoordinatorOutboxPort {
    /** Non-blocking wake-up. It must never perform network I/O on the caller. */
    fun trigger(eventId: String, sequence: Long)
    suspend fun awaitTerminalAck(eventId: String, sequence: Long): TerminalAckResult
    fun restartPendingDelivery()
}

interface CoordinatorMissionPort {
    suspend fun pauseAndAwait(
        session: CoordinatorSession,
        onSubmission: () -> Unit,
    ): MissionPauseOutcome

    suspend fun awaitStableHover(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof,
    ): MissionHoverOutcome

    /** The adapter must re-read and atomically claim Task 7 safety evidence at submission. */
    suspend fun resumeAfterSafetyReread(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof,
        hoverProof: CoordinatorHoverProof,
        onSubmission: () -> Unit,
    ): MissionResumeOutcome

    suspend fun reconcileForRecovery(session: CoordinatorRecoverySession): RecoveryMissionOutcome
}

interface CoordinatorLocalizationPort {
    suspend fun localize(
        session: CoordinatorSession,
        request: FireLocalizationRequest,
        holdProof: CoordinatorHoldProof,
        hoverProof: CoordinatorHoverProof,
        onLaserMeasurementBoundary: suspend () -> Boolean,
    ): BoundLocalizationResult

    suspend fun ensureLaserDisabledAndAlignmentClosed(session: CoordinatorSession?)
}

sealed interface ClosedLoopResult {
    data object MissionResumed : ClosedLoopResult
    data class Busy(val activeEventId: String) : ClosedLoopResult
    data class Disarmed(val health: CoordinatorArmingHealth) : ClosedLoopResult
    data class Rejected(val reason: String) : ClosedLoopResult
    data class ManualHold(
        val reason: CoordinatorManualHoldReason,
        val durable: Boolean,
    ) : ClosedLoopResult
}

/**
 * Owns exactly one flight-control session. All mutable ownership is protected by
 * [ownerMutex]; every asynchronous result is checked against the immutable
 * session/event/generation triple before it may advance the reducer.
 */
class AgentFireClosedLoopCoordinator(
    private val store: CoordinatorStorePort,
    private val delivery: CoordinatorOutboxPort,
    private val mission: CoordinatorMissionPort,
    private val localization: CoordinatorLocalizationPort,
    private val armingHealth: () -> CoordinatorArmingHealth,
    private val requestIds: CoordinatorRequestIdSource = CoordinatorRequestIdSource {
        UUID.randomUUID().toString()
    },
    private val reducer: FireSessionReducer = FireSessionReducer(),
    private val terminalAckTimeoutMs: Long = TERMINAL_ACK_TIMEOUT_MS,
) {
    init { require(terminalAckTimeoutMs > 0) }

    private val ownerMutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var active: CoordinatorSession? = null
    @Volatile private var manualHoldLatched = false

    suspend fun process(envelope: AgentFireConfirmationEnvelope): ClosedLoopResult {
        if (!ownerMutex.tryLock()) {
            return ClosedLoopResult.Busy(active?.eventId ?: "owner-acquiring")
        }
        val health = armingHealth()
        if (!health.armed || manualHoldLatched) {
            ownerMutex.unlock()
            return ClosedLoopResult.Disarmed(
                if (manualHoldLatched) health.copy(manualHoldActive = true) else health,
            )
        }
        val session = CoordinatorSession(
            envelope.sessionId,
            envelope.eventId,
            envelope.taskId,
            envelope.confirmation.kind,
            generation.incrementAndGet(),
            envelope.confirmation.roi,
            envelope.sourceGeneration,
        )
        active = session
        var flightSubmitted = false
        var cleanupConfirmed = false
        try {
            val initialReduction = reducer.reduce(
                FireSessionState.VISUAL_CONFIRMING,
                FireSessionEvent.VisualConfirmed(
                    InitialPersistenceRequest(
                        session.sessionId,
                        session.eventId,
                        requestIds.next(),
                        envelope.confirmation,
                    ),
                ),
            ).also { check(it.accepted) }
            var phase: FireSessionPhase = initialReduction.phase
            val initialEffect = initialReduction.effects.single() as FireSessionEffect.PersistInitialAlert
            val initial = safeWrite { store.persistInitial(envelope, initialEffect.request) }
                ?: return ClosedLoopResult.Rejected("initial-store-exception")
            if (!initial.durable) return ClosedLoopResult.Rejected("initial-not-durable")

            runCatching { delivery.trigger(session.eventId, initial.sequence) }
            val initialDurable = reducer.reduce(
                phase,
                FireSessionEvent.InitialAlertDurable(initialEffect.request),
            )
            check(initialDurable.accepted)
            phase = initialDurable.phase
            if (!safeStage(session, FireSessionState.HOLD_REQUESTED).durable) {
                return ClosedLoopResult.Rejected("hold-request-not-durable")
            }

            val paused = withTimeoutOrNull(PAUSE_TIMEOUT_MS) {
                mission.pauseAndAwait(session) { flightSubmitted = true }
            } ?: return manualHold(session, CoordinatorManualHoldReason.PAUSE_TIMEOUT)
            if (paused is MissionPauseOutcome.Failed) {
                return manualHold(session, paused.reason.toManualHold())
            }
            val holdProof = (paused as MissionPauseOutcome.Paused).holdProof
            if (!holdProof.matches(session)) {
                return manualHold(session, CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
            }
            phase = acceptedPhase(phase, FireSessionEvent.MissionPaused)
            if (!safeStage(session, FireSessionState.HOVER_VERIFYING).durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }

            val hover = withTimeoutOrNull(HOVER_TIMEOUT_MS) {
                mission.awaitStableHover(session, holdProof)
            } ?: return manualHold(session, CoordinatorManualHoldReason.HOVER_TIMEOUT)
            if (hover is MissionHoverOutcome.Failed) {
                return manualHold(session, hover.reason.toManualHold())
            }
            val hoverProof = (hover as MissionHoverOutcome.Stable).hoverProof
            if (!hoverProof.matches(session)) {
                return manualHold(session, CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
            }
            phase = acceptedPhase(phase, FireSessionEvent.HoverStable)
            if (!safeStage(session, FireSessionState.TARGET_ALIGNING).durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }

            var laserStageDurable = false
            val bound = try {
                localization.localize(
                    session,
                    FireLocalizationRequest(
                        session.sessionId,
                        session.eventId,
                        session.taskId,
                        session.kind,
                        session.initialRoi,
                    ),
                    holdProof = holdProof,
                    hoverProof = hoverProof,
                    onLaserMeasurementBoundary = {
                        phase = acceptedPhase(phase, FireSessionEvent.TargetAligned)
                        laserStageDurable = safeStage(
                            session,
                            FireSessionState.LASER_MEASURING,
                        ).durable
                        laserStageDurable
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return manualHold(session, CoordinatorManualHoldReason.ROI_OR_LASER_FAILURE)
            }
            if (bound.sessionId != session.sessionId || bound.eventId != session.eventId ||
                bound.generation != session.generation || bound.result.kind != session.kind
            ) {
                return manualHold(session, CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
            }
            val localized = bound.result
            if (localized is FireLocalizationResult.Precise &&
                localized.sourceGeneration != session.sourceGeneration
            ) {
                return manualHold(session, CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
            }
            if (localized is FireLocalizationResult.DegradedOsd &&
                localized.reason == FireLocalizationFailure.SOURCE_GENERATION_CHANGED
            ) {
                return manualHold(session, CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
            }
            if (localized is FireLocalizationResult.ManualHold) {
                return manualHold(session, localized.reason.toManualHold())
            }
            if (!laserStageDurable) {
                phase = acceptedPhase(phase, FireSessionEvent.TargetAligned)
                laserStageDurable = safeStage(session, FireSessionState.LASER_MEASURING).durable
            }
            if (!laserStageDurable) return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            val mapping = when (localized) {
                is FireLocalizationResult.Precise -> LocationStatus.PRECISE to GeoMethod.LASER_RANGEFINDER
                is FireLocalizationResult.DegradedOsd -> LocationStatus.DEGRADED_OSD to GeoMethod.AIRCRAFT_OBSERVATION
                is FireLocalizationResult.ManualHold -> error("handled above")
            }
            val terminalRequest = TerminalPersistenceRequest(
                session.sessionId,
                session.eventId,
                requestIds.next(),
                mapping.first,
                mapping.second,
            )
            phase = acceptedPhase(phase, FireSessionEvent.TerminalResultReady(terminalRequest))
            val terminal = safeWrite { store.persistTerminal(session, terminalRequest, localized) }
            if (terminal == null || !terminal.durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }
            phase = acceptedPhase(phase, FireSessionEvent.TerminalResultDurable(terminalRequest))
            runCatching { delivery.trigger(session.eventId, terminal.sequence) }
            val ack = withTimeoutOrNull(terminalAckTimeoutMs) {
                delivery.awaitTerminalAck(session.eventId, terminal.sequence)
            } ?: TerminalAckResult.TimedOut
            if (ack is TerminalAckResult.Acknowledged &&
                ack.eventId == session.eventId && ack.sequence == terminal.sequence
            ) {
                store.recordTerminalAck(session, terminal.sequence)
            }

            try {
                localization.ensureLaserDisabledAndAlignmentClosed(session)
                cleanupConfirmed = true
            } catch (_: Exception) {
                return manualHold(session, CoordinatorManualHoldReason.LASER_DISABLE_UNCERTAIN)
            }
            phase = acceptedPhase(phase, FireSessionEvent.ResumeRequested)
            if (!safeStage(session, FireSessionState.RESUME_REQUESTED).durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }
            val resumed = mission.resumeAfterSafetyReread(session, holdProof, hoverProof) {
                flightSubmitted = true
            }
            if (resumed is MissionResumeOutcome.Failed) {
                return manualHold(session, CoordinatorManualHoldReason.RESUME_FAILURE)
            }
            phase = acceptedPhase(phase, FireSessionEvent.MissionResumeConfirmed)
            if (!safeStage(session, FireSessionState.MISSION_RESUMED).durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }
            check(phase.state == FireSessionState.MISSION_RESUMED)
            return ClosedLoopResult.MissionResumed
        } catch (cancelled: CancellationException) {
            if (flightSubmitted) {
                withContext(NonCancellable) {
                    manualHold(session, CoordinatorManualHoldReason.CANCELLED_AFTER_FLIGHT_SUBMISSION)
                }
            }
            throw cancelled
        } finally {
            if (!cleanupConfirmed && flightSubmitted) {
                withContext(NonCancellable) {
                    runCatching { localization.ensureLaserDisabledAndAlignmentClosed(session) }
                }
            }
            active = null
            ownerMutex.unlock()
        }
    }

    fun activeSession(): CoordinatorSession? = active

    /** May only be called after explicit Task 7 recovery reconciliation. */
    fun clearManualHoldAfterRecovery(reconciled: Boolean): Boolean {
        if (!reconciled || active != null) return false
        manualHoldLatched = false
        return true
    }

    private fun acceptedPhase(phase: FireSessionPhase, event: FireSessionEvent): FireSessionPhase =
        reducer.reduce(phase, event).also { check(it.accepted) }.phase

    private suspend fun safeStage(session: CoordinatorSession, state: FireSessionState): CoordinatorWrite =
        safeWrite { store.persistStage(session, state) }
            ?: CoordinatorWrite(DurableWriteResult.Rejected("store-exception"), -1)

    private suspend fun safeWrite(block: suspend () -> CoordinatorWrite): CoordinatorWrite? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun manualHold(
        session: CoordinatorSession,
        reason: CoordinatorManualHoldReason,
    ): ClosedLoopResult.ManualHold {
        val closed = runCatching { localization.ensureLaserDisabledAndAlignmentClosed(session) }.isSuccess
        val durable = safeWrite { store.persistManualHold(session, reason) }?.durable == true
        manualHoldLatched = true
        return ClosedLoopResult.ManualHold(
            if (closed) reason else CoordinatorManualHoldReason.LASER_DISABLE_UNCERTAIN,
            durable,
        )
    }

    companion object {
        const val TERMINAL_ACK_TIMEOUT_MS = 1_000L
        const val PAUSE_TIMEOUT_MS = 8_000L
        const val HOVER_TIMEOUT_MS = 8_000L
    }
}

private fun CoordinatorHoldProof.matches(session: CoordinatorSession): Boolean =
    sessionId == session.sessionId && eventId == session.eventId && generation == session.generation

private fun CoordinatorHoverProof.matches(session: CoordinatorSession): Boolean =
    sessionId == session.sessionId && eventId == session.eventId && generation == session.generation

private fun MissionWorkflowFailure.toManualHold(): CoordinatorManualHoldReason = when (this) {
    MissionWorkflowFailure.PAUSE_TIMEOUT -> CoordinatorManualHoldReason.PAUSE_TIMEOUT
    MissionWorkflowFailure.PAUSE_FAILED -> CoordinatorManualHoldReason.PAUSE_FAILED
    MissionWorkflowFailure.HOVER_TIMEOUT -> CoordinatorManualHoldReason.HOVER_TIMEOUT
    MissionWorkflowFailure.MANUAL_TAKEOVER -> CoordinatorManualHoldReason.MANUAL_TAKEOVER
    MissionWorkflowFailure.LOW_BATTERY -> CoordinatorManualHoldReason.LOW_BATTERY
    MissionWorkflowFailure.RETURN_TO_HOME -> CoordinatorManualHoldReason.RETURN_TO_HOME
    MissionWorkflowFailure.OBSTACLE_AVOIDANCE -> CoordinatorManualHoldReason.OBSTACLE_AVOIDANCE
    MissionWorkflowFailure.FLIGHT_ERROR -> CoordinatorManualHoldReason.FLIGHT_ERROR
    MissionWorkflowFailure.UNKNOWN_MISSION_STATE -> CoordinatorManualHoldReason.UNKNOWN_MISSION_STATE
    MissionWorkflowFailure.MISSING_BREAKPOINT -> CoordinatorManualHoldReason.MISSING_BREAKPOINT
    MissionWorkflowFailure.BREAKPOINT_MISMATCH -> CoordinatorManualHoldReason.BREAKPOINT_MISMATCH
    MissionWorkflowFailure.RESUME_FAILED,
    MissionWorkflowFailure.RESUME_TIMEOUT,
    MissionWorkflowFailure.SAFETY_EVIDENCE_INVALID,
    -> CoordinatorManualHoldReason.RESUME_FAILURE
}

private fun FireLocalizationFailure.toManualHold(): CoordinatorManualHoldReason = when (this) {
    FireLocalizationFailure.AIRCRAFT_OSD_UNAVAILABLE -> CoordinatorManualHoldReason.AIRCRAFT_OSD_UNAVAILABLE
    else -> CoordinatorManualHoldReason.ROI_OR_LASER_FAILURE
}
