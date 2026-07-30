package com.yinxin.uavfir.firedetection

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

object FlightSafetyPolicy {
    const val MAX_HORIZONTAL_SPEED_MPS = 0.3
    const val MAX_ABSOLUTE_VERTICAL_SPEED_MPS = 0.2
    const val REQUIRED_STABLE_HOVER_MS = 1_000L
    const val HOVER_TIMEOUT_MS = 8_000L
    const val TELEMETRY_STALE_AFTER_MS = 500L
}

enum class FlightSafetyReason {
    MANUAL_CONTROL_TAKEOVER,
    LOW_BATTERY,
    RETURN_TO_HOME_ACTIVE,
    OBSTACLE_AVOIDANCE_INTERVENTION,
    FLIGHT_ERROR,
    UNKNOWN_MISSION_STATE,
    MISSING_BREAKPOINT,
    INVALID_BREAKPOINT,
    MISSION_IDENTITY_MISMATCH,
    BREAKPOINT_MISMATCH,
    LASER_ENABLED,
    TARGET_ALIGNMENT_ACTIVE,
    COMPETING_FIRE_SESSION,
    FIRE_SESSION_IDENTITY_MISMATCH,
    TELEMETRY_UNAVAILABLE,
    TELEMETRY_STALE,
    HOVER_NOT_STABLE,
    HOVER_STABILITY_TIMEOUT,
    TERMINAL_RESULT_NOT_DURABLE,
    HOVER_COMMAND_FAILED,
    PAUSE_COMMAND_FAILED,
    RESUME_COMMAND_FAILED,
    RESUME_OUTCOME_UNKNOWN,
    CONTROL_CLOSED,
}

data class MissionIdentity(val missionId: String, val missionFileName: String) {
    init {
        require(missionId.isNotBlank() && missionFileName.isNotBlank())
    }
}

data class MissionExecutionKey(
    val identity: MissionIdentity,
    val missionGeneration: Long,
) {
    init {
        require(missionGeneration > 0)
    }
}

data class MissionBreakpoint(
    val waylineId: Int,
    val waypointId: Int,
    val segmentProgress: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val recoverAction: String? = null,
) {
    val isValid: Boolean
        get() {
            if (waylineId < 0 || waypointId < 0) return false
            if (!segmentProgress.isFinite() || segmentProgress !in 0.0..1.0) return false
            val coordinates = listOf(latitude, longitude, altitude)
            if (coordinates.any { it != null } && coordinates.any { it == null }) return false
            if (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0)) return false
            if (longitude != null && (!longitude.isFinite() || longitude !in -180.0..180.0)) return false
            if (altitude != null && !altitude.isFinite()) return false
            if (recoverAction != null && recoverAction !in VALID_RECOVER_ACTIONS) return false
            return true
        }

    companion object {
        val VALID_RECOVER_ACTIONS = setOf(
            "GoBackToRecordPoint",
            "GoBackToNextPoint",
            "GoBackToNextNextPoint",
        )
    }
}

data class FireControlSessionKey(val sessionId: String, val generation: Long) {
    init {
        require(sessionId.isNotBlank() && generation > 0)
    }
}

data class HoverControlBinding(
    val controlSession: FireControlSessionKey,
    val mission: MissionExecutionKey,
)

data class FlightSafetySignals(
    val manualTakeover: Boolean = false,
    val lowBattery: Boolean = false,
    val rthActive: Boolean = false,
    val obstacleAvoidanceActive: Boolean = false,
    val flightError: Boolean = false,
)

data class FlightTelemetrySample(
    val capturedAtMonotonicMs: Long,
    val observedAtMonotonicMs: Long,
    val horizontalSpeedMps: Double,
    val verticalSpeedMps: Double,
)

class StableHoverEvidence internal constructor(
    internal val binding: HoverControlBinding,
    internal val issuedAtMonotonicMs: Long,
    internal val nonce: Long,
)

sealed interface HoverSafetyDecision {
    data object Waiting : HoverSafetyDecision
    data class Stable(val evidence: StableHoverEvidence) : HoverSafetyDecision
    data class ManualHold(val reason: FlightSafetyReason) : HoverSafetyDecision
}

@ConsistentCopyVisibility
data class ResumeSafetyEvidence internal constructor(
    val controlSession: FireControlSessionKey,
    val mission: MissionExecutionKey,
    val breakpoint: MissionBreakpoint,
    val terminalResultDurable: Boolean,
    val laserEnabled: Boolean,
    val targetAlignmentClosed: Boolean,
    val anotherFireSessionActive: Boolean,
    val signals: FlightSafetySignals,
    val telemetry: FlightTelemetrySample?,
    val stableHoverEvidence: StableHoverEvidence?,
    val observedAtMonotonicMs: Long,
)

fun interface ResumeSafetyEvidenceProvider {
    fun current(controlSession: FireControlSessionKey): ResumeSafetyEvidence?
}

object FailClosedResumeSafetyEvidenceProvider : ResumeSafetyEvidenceProvider {
    override fun current(controlSession: FireControlSessionKey): ResumeSafetyEvidence? = null
}

/**
 * Production owner for coherent resume evidence. Only the local fire
 * coordinator may publish or invalidate snapshots; consumers can only read the
 * exact controlling-session generation.
 */
internal class OwnedResumeSafetyEvidenceProvider : ResumeSafetyEvidenceProvider {
    private val current = AtomicReference<ResumeSafetyEvidence?>(null)

    internal fun publish(evidence: ResumeSafetyEvidence): Boolean {
        while (true) {
            val previous = current.get()
            if (previous != null) {
                val sameControlGeneration = previous.controlSession == evidence.controlSession
                val sameMissionGeneration = previous.mission == evidence.mission
                val timeDidNotRegress =
                    evidence.observedAtMonotonicMs >= previous.observedAtMonotonicMs
                if (!sameControlGeneration || !sameMissionGeneration || !timeDidNotRegress) {
                    if (current.compareAndSet(previous, null)) {
                        return false
                    }
                    continue
                }
            }
            if (current.compareAndSet(previous, evidence)) {
                return true
            }
        }
    }

    internal fun invalidate(controlSession: FireControlSessionKey? = null): Boolean {
        while (true) {
            val previous = current.get() ?: return false
            if (controlSession != null && previous.controlSession != controlSession) {
                return false
            }
            if (current.compareAndSet(previous, null)) {
                return true
            }
        }
    }

    override fun current(controlSession: FireControlSessionKey): ResumeSafetyEvidence? =
        current.get()?.takeIf { it.controlSession == controlSession }
}

sealed interface ResumeSafetyDecision {
    data object Permitted : ResumeSafetyDecision
    data class ManualHold(val reason: FlightSafetyReason) : ResumeSafetyDecision
}

class FlightSafetyGate {
    private var activeBinding: HoverControlBinding? = null
    private var stableSinceMs: Long? = null
    private var lastFreshObservedAtMs: Long? = null
    private var lastObservedAtMs: Long? = null
    private val issuedEvidence = IdentityHashMap<StableHoverEvidence, HoverControlBinding>()
    private val nonce = AtomicLong()

    @Synchronized
    fun reset() {
        invalidate()
        activeBinding = null
        lastObservedAtMs = null
    }

    @Synchronized
    fun observeHover(
        binding: HoverControlBinding,
        hoverStartedAtMonotonicMs: Long,
        sample: FlightTelemetrySample?,
        signals: FlightSafetySignals = FlightSafetySignals(),
        nowMs: Long = sample?.observedAtMonotonicMs ?: hoverStartedAtMonotonicMs,
    ): HoverSafetyDecision {
        if (activeBinding != binding) {
            invalidate()
            activeBinding = binding
            lastObservedAtMs = null
        }
        signalFailure(signals)?.let {
            invalidate()
            return HoverSafetyDecision.ManualHold(it)
        }
        if (nowMs < hoverStartedAtMonotonicMs ||
            lastObservedAtMs?.let { nowMs < it } == true ||
            sample?.observedAtMonotonicMs != null && sample.observedAtMonotonicMs != nowMs
        ) {
            invalidate()
            return HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_STALE)
        }
        lastObservedAtMs = nowMs
        if (nowMs - hoverStartedAtMonotonicMs >= FlightSafetyPolicy.HOVER_TIMEOUT_MS) {
            invalidate()
            return HoverSafetyDecision.ManualHold(FlightSafetyReason.HOVER_STABILITY_TIMEOUT)
        }
        if (sample == null) {
            invalidate()
            return HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_UNAVAILABLE)
        }
        if (!isFreshAndStable(sample, nowMs)) {
            invalidate()
            return HoverSafetyDecision.Waiting
        }
        if (lastFreshObservedAtMs?.let { nowMs - it > FlightSafetyPolicy.TELEMETRY_STALE_AFTER_MS } == true) {
            stableSinceMs = null
            issuedEvidence.clear()
        }
        lastFreshObservedAtMs = nowMs
        val startedAt = stableSinceMs ?: nowMs.also { stableSinceMs = it }
        if (nowMs - startedAt < FlightSafetyPolicy.REQUIRED_STABLE_HOVER_MS) {
            return HoverSafetyDecision.Waiting
        }
        val evidence = StableHoverEvidence(binding, nowMs, nonce.incrementAndGet())
        issuedEvidence[evidence] = binding
        return HoverSafetyDecision.Stable(evidence)
    }

    @Synchronized
    fun evaluateResume(
        controlSession: FireControlSessionKey,
        mission: MissionExecutionKey,
        breakpoint: MissionBreakpoint,
        evidence: ResumeSafetyEvidence?,
        nowMs: Long,
    ): ResumeSafetyDecision {
        val reason = when {
            evidence == null -> FlightSafetyReason.TELEMETRY_UNAVAILABLE
            evidence.controlSession != controlSession -> FlightSafetyReason.FIRE_SESSION_IDENTITY_MISMATCH
            evidence.mission != mission -> FlightSafetyReason.MISSION_IDENTITY_MISMATCH
            evidence.breakpoint != breakpoint -> FlightSafetyReason.BREAKPOINT_MISMATCH
            !evidence.terminalResultDurable -> FlightSafetyReason.TERMINAL_RESULT_NOT_DURABLE
            signalFailure(evidence.signals) != null -> signalFailure(evidence.signals)
            evidence.laserEnabled -> FlightSafetyReason.LASER_ENABLED
            !evidence.targetAlignmentClosed -> FlightSafetyReason.TARGET_ALIGNMENT_ACTIVE
            evidence.anotherFireSessionActive -> FlightSafetyReason.COMPETING_FIRE_SESSION
            nowMs < evidence.observedAtMonotonicMs -> FlightSafetyReason.TELEMETRY_STALE
            evidence.telemetry == null -> FlightSafetyReason.TELEMETRY_UNAVAILABLE
            evidence.telemetry.observedAtMonotonicMs != evidence.observedAtMonotonicMs ->
                FlightSafetyReason.TELEMETRY_STALE
            !isFreshAndStable(evidence.telemetry, nowMs) -> FlightSafetyReason.TELEMETRY_STALE
            evidence.stableHoverEvidence == null -> FlightSafetyReason.HOVER_NOT_STABLE
            issuedEvidence[evidence.stableHoverEvidence] != HoverControlBinding(controlSession, mission) ->
                FlightSafetyReason.HOVER_NOT_STABLE
            else -> null
        }
        if (reason != null) invalidate()
        return reason?.let(ResumeSafetyDecision::ManualHold) ?: ResumeSafetyDecision.Permitted
    }

    private fun isFreshAndStable(sample: FlightTelemetrySample, nowMs: Long): Boolean {
        val age = nowMs - sample.capturedAtMonotonicMs
        return sample.observedAtMonotonicMs <= nowMs &&
            age in 0..FlightSafetyPolicy.TELEMETRY_STALE_AFTER_MS &&
            sample.horizontalSpeedMps.isFinite() &&
            sample.verticalSpeedMps.isFinite() &&
            sample.horizontalSpeedMps in 0.0..FlightSafetyPolicy.MAX_HORIZONTAL_SPEED_MPS &&
            abs(sample.verticalSpeedMps) <= FlightSafetyPolicy.MAX_ABSOLUTE_VERTICAL_SPEED_MPS
    }

    private fun signalFailure(signals: FlightSafetySignals): FlightSafetyReason? = when {
        signals.manualTakeover -> FlightSafetyReason.MANUAL_CONTROL_TAKEOVER
        signals.lowBattery -> FlightSafetyReason.LOW_BATTERY
        signals.rthActive -> FlightSafetyReason.RETURN_TO_HOME_ACTIVE
        signals.obstacleAvoidanceActive -> FlightSafetyReason.OBSTACLE_AVOIDANCE_INTERVENTION
        signals.flightError -> FlightSafetyReason.FLIGHT_ERROR
        else -> null
    }

    private fun invalidate() {
        stableSinceMs = null
        lastFreshObservedAtMs = null
        issuedEvidence.clear()
    }
}
