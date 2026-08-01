package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.DurableWriteResult
import com.yinxin.uavfir.firedetection.store.FireEvidenceReference
import com.yinxin.uavfir.firedetection.store.MissionRecoveryProofV1
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AgentFireConfirmationEnvelope(
    val sessionId: String,
    val eventId: String,
    val taskId: String,
    val sourceGeneration: Long,
    val confirmation: VisibleConfirmation,
    val evidence: List<FireEvidenceReference> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank() && taskId.isNotBlank())
        require(sourceGeneration > 0)
        require(evidence.size <= MAX_EVIDENCE_REFERENCES)
    }

    private companion object { const val MAX_EVIDENCE_REFERENCES = 2 }
}

data class CoordinatorSession(
    val sessionId: String,
    val eventId: String,
    val taskId: String,
    val kind: DetectionKind,
    val generation: Long,
    val initialRoi: NormalizedRoi,
    val sourceGeneration: Long = 1,
    val evidence: List<FireEvidenceReference> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank() && taskId.isNotBlank())
        require(generation > 0 && sourceGeneration > 0)
        require(evidence.size <= 2)
    }
}

data class CoordinatorRecoverySession(
    val session: CoordinatorSession,
    val persistedState: FireSessionState,
    val terminalResultDurable: Boolean,
    val recoveryProof: MissionRecoveryProofV1? = null,
)

data class CoordinatorRuntimeHealth(
    val detectorHealthy: Boolean,
    val runtimeHealthy: Boolean,
    val storeHealthy: Boolean,
) {
    val healthy: Boolean get() = detectorHealthy && runtimeHealthy && storeHealthy
    val failureReason: CoordinatorManualHoldReason?
        get() = when {
            !detectorHealthy || !runtimeHealthy -> CoordinatorManualHoldReason.DETECTOR_FAILURE
            !storeHealthy -> CoordinatorManualHoldReason.STORAGE_FAILURE
            else -> null
        }
}

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
    CANCELLED_AFTER_DURABLE_HOLD_INTENT,
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

sealed interface ResumeReconciliationOutcome {
    data object Executing : ResumeReconciliationOutcome
    data object Held : ResumeReconciliationOutcome
    data object Unknown : ResumeReconciliationOutcome
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
        session: CoordinatorSession,
        envelope: AgentFireConfirmationEnvelope,
        request: InitialPersistenceRequest,
    ): CoordinatorWrite

    suspend fun persistStage(
        session: CoordinatorSession,
        state: FireSessionState,
        recoveryProof: MissionRecoveryProofV1? = null,
    ): CoordinatorWrite

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

    /** Completes only when the exact held mission becomes unsafe. */
    suspend fun awaitHeldInvalidation(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof,
    ): MissionWorkflowFailure = awaitCancellation()

    suspend fun reconcileAfterResumeSubmission(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof,
    ): ResumeReconciliationOutcome = ResumeReconciliationOutcome.Unknown

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
    data class MissionExecutingDurabilityUncertain(val eventId: String) : ClosedLoopResult
    data class ResumeOutcomeUnknown(val eventId: String) : ClosedLoopResult
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
    private val runtimeHealth: () -> CoordinatorRuntimeHealth = {
        CoordinatorRuntimeHealth(true, true, true)
    },
    private val requestIds: CoordinatorRequestIdSource = CoordinatorRequestIdSource {
        UUID.randomUUID().toString()
    },
    private val reducer: FireSessionReducer = FireSessionReducer(),
    private val terminalAckTimeoutMs: Long = TERMINAL_ACK_TIMEOUT_MS,
    private val runtimeHealthPollMs: Long = RUNTIME_HEALTH_POLL_MS,
) {
    init { require(terminalAckTimeoutMs > 0 && runtimeHealthPollMs > 0) }

    private val ownerMutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var active: CoordinatorSession? = null
    @Volatile private var unresolvedResumeOwner: UnresolvedResumeOwner? = null
    @Volatile private var manualHoldLatched = false

    suspend fun process(envelope: AgentFireConfirmationEnvelope): ClosedLoopResult {
        if (!ownerMutex.tryLock()) {
            return ClosedLoopResult.Busy(active?.eventId ?: "owner-acquiring")
        }
        unresolvedResumeOwner?.let { unresolved ->
            try {
                reconcileSubmittedResume(
                    unresolved.session,
                    unresolved.holdProof,
                    CoordinatorManualHoldReason.RESUME_FAILURE,
                )
            } finally {
                ownerMutex.unlock()
            }
            return ClosedLoopResult.Busy(unresolved.session.eventId)
        }
        val health = try {
            armingHealth()
        } catch (_: Exception) {
            ownerMutex.unlock()
            return ClosedLoopResult.Rejected("arming-health-unavailable")
        }
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
            envelope.evidence,
        )
        active = session
        var commandStage = CommandStage.NONE
        var durableHoldIntent = false
        var cleanupConfirmed = false
        var verifiedHoldProof: CoordinatorHoldProof? = null
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
            val initial = safeWrite { store.persistInitial(session, envelope, initialEffect.request) }
                ?: return ClosedLoopResult.Rejected("initial-store-exception")
            if (!initial.durable) return ClosedLoopResult.Rejected("initial-not-durable")

            val initialDurable = reducer.reduce(
                phase,
                FireSessionEvent.InitialAlertDurable(initialEffect.request),
            )
            check(initialDurable.accepted)
            phase = initialDurable.phase
            requireEffect(initialDurable, FireSessionEffect.PauseMission)
            runCatching { delivery.trigger(session.eventId, initial.sequence) }
            val (holdWrite, paused) = coroutineScope {
                val pause = async {
                    withTimeoutOrNull(PAUSE_TIMEOUT_MS) {
                        mission.pauseAndAwait(session) { commandStage = CommandStage.PAUSE_SUBMITTED }
                    }
                }
                val stage = async { safeStage(session, FireSessionState.HOLD_REQUESTED) }
                val durable = stage.await()
                if (!durable.durable) {
                    pause.cancel()
                    durable to null
                } else {
                    durableHoldIntent = true
                    durable to pause.await()
                }
            }
            if (!holdWrite.durable) {
                return if (commandStage >= CommandStage.PAUSE_SUBMITTED) {
                    manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
                } else {
                    ClosedLoopResult.Rejected("hold-request-not-durable")
                }
            }
            if (paused == null) return manualHold(session, CoordinatorManualHoldReason.PAUSE_TIMEOUT)
            if (paused is MissionPauseOutcome.Failed) {
                return manualHold(session, paused.reason.toManualHold())
            }
            val holdProof = (paused as MissionPauseOutcome.Paused).holdProof
            if (!holdProof.matches(session)) {
                return manualHold(session, CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
            }
            verifiedHoldProof = holdProof
            commandStage = CommandStage.HOLD_CONFIRMED
            phase = acceptedPhase(phase, FireSessionEvent.MissionPaused)
            val recoveryProof = holdProof.missionToken?.let(MissionRecoveryProofV1::from)
            if (!safeStage(session, FireSessionState.HOVER_VERIFYING, recoveryProof).durable) {
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
            val hoverReduction = reducer.reduce(phase, FireSessionEvent.HoverStable)
            check(hoverReduction.accepted)
            requireEffect(hoverReduction, FireSessionEffect.AlignTarget)
            phase = hoverReduction.phase
            if (!safeStage(session, FireSessionState.TARGET_ALIGNING).durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }
            val held = runHeldWorkflow(session, phase, holdProof, hoverProof)
            when (held) {
                is HeldWorkflowResult.Invalidated -> return manualHold(session, held.reason)
                is HeldWorkflowResult.Completed -> {
                    phase = held.phase
                    cleanupConfirmed = true
                }
            }
            val resumeReduction = reducer.reduce(phase, FireSessionEvent.ResumeRequested)
            check(resumeReduction.accepted)
            requireEffect(resumeReduction, FireSessionEffect.ResumeMission)
            phase = resumeReduction.phase
            if (!safeStage(session, FireSessionState.RESUME_REQUESTED).durable) {
                return manualHold(session, CoordinatorManualHoldReason.STORAGE_FAILURE)
            }
            val finalHealthFailure = try {
                runtimeHealth().failureReason
            } catch (_: Exception) {
                CoordinatorManualHoldReason.DETECTOR_FAILURE
            }
            if (finalHealthFailure != null) {
                return manualHold(session, finalHealthFailure)
            }
            val resumed = mission.resumeAfterSafetyReread(session, holdProof, hoverProof) {
                commandStage = CommandStage.RESUME_SUBMITTED
            }
            if (resumed is MissionResumeOutcome.Failed) {
                if (commandStage == CommandStage.RESUME_SUBMITTED) {
                    return reconcileSubmittedResume(
                        session,
                        holdProof,
                        CoordinatorManualHoldReason.RESUME_FAILURE,
                    )
                }
                return manualHold(session, CoordinatorManualHoldReason.RESUME_FAILURE)
            }
            commandStage = CommandStage.RESUME_CONFIRMED
            phase = acceptedPhase(phase, FireSessionEvent.MissionResumeConfirmed)
            if (!safeStage(session, FireSessionState.MISSION_RESUMED).durable) {
                retainUnresolvedResumeOwner(session, holdProof)
                return ClosedLoopResult.MissionExecutingDurabilityUncertain(session.eventId)
            }
            check(phase.state == FireSessionState.MISSION_RESUMED)
            return ClosedLoopResult.MissionResumed
        } catch (cancelled: CancellationException) {
            if (commandStage >= CommandStage.RESUME_SUBMITTED) {
                withContext(NonCancellable) {
                    reconcileSubmittedResume(
                        session,
                        verifiedHoldProof,
                        CoordinatorManualHoldReason.CANCELLED_AFTER_FLIGHT_SUBMISSION,
                    )
                }
            } else if (commandStage >= CommandStage.PAUSE_SUBMITTED || durableHoldIntent) {
                withContext(NonCancellable) {
                    manualHold(
                        session,
                        if (commandStage >= CommandStage.PAUSE_SUBMITTED) {
                            CoordinatorManualHoldReason.CANCELLED_AFTER_FLIGHT_SUBMISSION
                        } else CoordinatorManualHoldReason.CANCELLED_AFTER_DURABLE_HOLD_INTENT,
                    )
                }
            }
            throw cancelled
        } finally {
            if (!cleanupConfirmed && (commandStage >= CommandStage.PAUSE_SUBMITTED || durableHoldIntent)) {
                withContext(NonCancellable) {
                    runCatching { localization.ensureLaserDisabledAndAlignmentClosed(session) }
                }
            }
            active = null
            ownerMutex.unlock()
        }
    }

    private suspend fun runHeldWorkflow(
        session: CoordinatorSession,
        initialPhase: FireSessionPhase,
        holdProof: CoordinatorHoldProof,
        hoverProof: CoordinatorHoverProof,
    ): HeldWorkflowResult = coroutineScope {
        val work = async { performLocalizationAndReporting(session, initialPhase, holdProof, hoverProof) }
        val safety = async {
            try {
                mission.awaitHeldInvalidation(session, holdProof).toManualHold()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CoordinatorManualHoldReason.FLIGHT_ERROR
            }
        }
        val health = async {
            while (true) {
                val snapshot = try {
                    runtimeHealth()
                } catch (_: Exception) {
                    return@async CoordinatorManualHoldReason.DETECTOR_FAILURE
                }
                snapshot.failureReason?.let { return@async it }
                delay(runtimeHealthPollMs)
            }
            @Suppress("UNREACHABLE_CODE") CoordinatorManualHoldReason.DETECTOR_FAILURE
        }
        try {
            select {
                work.onAwait { it }
                safety.onAwait { HeldWorkflowResult.Invalidated(it) }
                health.onAwait { HeldWorkflowResult.Invalidated(it) }
            }
        } finally {
            work.cancel()
            safety.cancel()
            health.cancel()
        }
    }

    private suspend fun performLocalizationAndReporting(
        session: CoordinatorSession,
        initialPhase: FireSessionPhase,
        holdProof: CoordinatorHoldProof,
        hoverProof: CoordinatorHoverProof,
    ): HeldWorkflowResult {
        var phase = initialPhase
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
                holdProof,
                hoverProof,
                onLaserMeasurementBoundary = {
                    val aligned = reducer.reduce(phase, FireSessionEvent.TargetAligned)
                    check(aligned.accepted)
                    requireEffect(aligned, FireSessionEffect.MeasureLaser)
                    phase = aligned.phase
                    laserStageDurable = safeStage(session, FireSessionState.LASER_MEASURING).durable
                    laserStageDurable
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.ROI_OR_LASER_FAILURE)
        }
        if (bound.sessionId != session.sessionId || bound.eventId != session.eventId ||
            bound.generation != session.generation || bound.result.kind != session.kind
        ) return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
        val localized = bound.result
        if (localized is FireLocalizationResult.Precise &&
            localized.sourceGeneration != session.sourceGeneration
        ) return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
        if (localized is FireLocalizationResult.DegradedOsd &&
            localized.reason == FireLocalizationFailure.SOURCE_GENERATION_CHANGED
        ) return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
        if (localized is FireLocalizationResult.ManualHold) {
            return HeldWorkflowResult.Invalidated(localized.reason.toManualHold())
        }
        if (localized is FireLocalizationResult.Precise && !laserStageDurable) {
            return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE)
        }
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
        val pending = reducer.reduce(phase, FireSessionEvent.TerminalResultReady(terminalRequest))
        check(pending.accepted)
        requireEffect(pending, FireSessionEffect.PersistTerminalResult(terminalRequest))
        phase = pending.phase
        val terminal = safeWrite { store.persistTerminal(session, terminalRequest, localized) }
        if (terminal == null || !terminal.durable) {
            return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.STORAGE_FAILURE)
        }
        phase = acceptedPhase(phase, FireSessionEvent.TerminalResultDurable(terminalRequest))
        runCatching { delivery.trigger(session.eventId, terminal.sequence) }
        val ack = withTimeoutOrNull(terminalAckTimeoutMs) {
            delivery.awaitTerminalAck(session.eventId, terminal.sequence)
        } ?: TerminalAckResult.TimedOut
        if (ack is TerminalAckResult.Acknowledged &&
            ack.eventId == session.eventId && ack.sequence == terminal.sequence
        ) store.recordTerminalAck(session, terminal.sequence)
        try {
            localization.ensureLaserDisabledAndAlignmentClosed(session)
        } catch (_: Exception) {
            return HeldWorkflowResult.Invalidated(CoordinatorManualHoldReason.LASER_DISABLE_UNCERTAIN)
        }
        return HeldWorkflowResult.Completed(phase)
    }

    private suspend fun reconcileSubmittedResume(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof?,
        heldReason: CoordinatorManualHoldReason,
    ): ClosedLoopResult = when (val reconciliation = try {
        holdProof?.let { mission.reconcileAfterResumeSubmission(session, it) }
            ?: ResumeReconciliationOutcome.Unknown
    } catch (_: Exception) {
        ResumeReconciliationOutcome.Unknown
    }) {
        ResumeReconciliationOutcome.Executing -> {
            val write = safeStage(session, FireSessionState.MISSION_RESUMED)
            if (write.durable) {
                clearUnresolvedResumeOwner(session)
                ClosedLoopResult.MissionResumed
            } else {
                retainUnresolvedResumeOwner(session, holdProof)
                ClosedLoopResult.MissionExecutingDurabilityUncertain(session.eventId)
            }
        }
        ResumeReconciliationOutcome.Held -> {
            val result = manualHold(session, heldReason)
            if (result.durable) clearUnresolvedResumeOwner(session)
            else retainUnresolvedResumeOwner(session, holdProof)
            result
        }
        ResumeReconciliationOutcome.Unknown -> {
            retainUnresolvedResumeOwner(session, holdProof)
            ClosedLoopResult.ResumeOutcomeUnknown(session.eventId)
        }
    }

    fun activeSession(): CoordinatorSession? = active ?: unresolvedResumeOwner?.session

    /** May only be called after explicit Task 7 recovery reconciliation. */
    fun clearManualHoldAfterRecovery(reconciled: Boolean): Boolean {
        if (!reconciled || active != null || unresolvedResumeOwner != null) return false
        manualHoldLatched = false
        return true
    }

    private fun retainUnresolvedResumeOwner(
        session: CoordinatorSession,
        holdProof: CoordinatorHoldProof?,
    ) {
        unresolvedResumeOwner = UnresolvedResumeOwner(session, holdProof)
    }

    private fun clearUnresolvedResumeOwner(session: CoordinatorSession) {
        if (unresolvedResumeOwner?.session == session) unresolvedResumeOwner = null
    }

    private fun acceptedPhase(phase: FireSessionPhase, event: FireSessionEvent): FireSessionPhase =
        reducer.reduce(phase, event).also { check(it.accepted) }.phase

    private suspend fun safeStage(
        session: CoordinatorSession,
        state: FireSessionState,
        recoveryProof: MissionRecoveryProofV1? = null,
    ): CoordinatorWrite =
        safeWrite { store.persistStage(session, state, recoveryProof) }
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
        val effectiveReason = if (closed) reason else CoordinatorManualHoldReason.LASER_DISABLE_UNCERTAIN
        val durable = safeWrite { store.persistManualHold(session, effectiveReason) }?.durable == true
        manualHoldLatched = true
        return ClosedLoopResult.ManualHold(
            effectiveReason,
            durable,
        )
    }

    companion object {
        const val TERMINAL_ACK_TIMEOUT_MS = 1_000L
        const val PAUSE_TIMEOUT_MS = 8_000L
        const val HOVER_TIMEOUT_MS = 8_000L
        const val RUNTIME_HEALTH_POLL_MS = 100L
    }
}

private enum class CommandStage {
    NONE,
    PAUSE_SUBMITTED,
    HOLD_CONFIRMED,
    RESUME_SUBMITTED,
    RESUME_CONFIRMED,
}

private data class UnresolvedResumeOwner(
    val session: CoordinatorSession,
    val holdProof: CoordinatorHoldProof?,
)

private sealed interface HeldWorkflowResult {
    data class Completed(val phase: FireSessionPhase) : HeldWorkflowResult
    data class Invalidated(val reason: CoordinatorManualHoldReason) : HeldWorkflowResult
}

private fun requireEffect(reduction: FireSessionReduction, expected: FireSessionEffect) {
    check(reduction.effects.size == 1 && reduction.effects.single() == expected) {
        "Reducer did not authorize effect $expected from ${reduction.state}"
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
