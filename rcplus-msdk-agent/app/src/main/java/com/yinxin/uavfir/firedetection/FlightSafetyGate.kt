package com.yinxin.uavfir.firedetection

import kotlin.math.abs

object FlightSafetyPolicy {
    const val MAX_HORIZONTAL_SPEED_MPS: Double = 0.3
    const val MAX_ABSOLUTE_VERTICAL_SPEED_MPS: Double = 0.2
    const val REQUIRED_STABLE_HOVER_MS: Long = 1_000
    const val HOVER_TIMEOUT_MS: Long = 8_000
    const val TELEMETRY_STALE_AFTER_MS: Long = 500
}

enum class FlightSafetyReason {
    MANUAL_CONTROL_TAKEOVER,
    LOW_BATTERY,
    RETURN_TO_HOME_ACTIVE,
    OBSTACLE_AVOIDANCE_INTERVENTION,
    FLIGHT_ERROR,
    UNKNOWN_MISSION_STATE,
    MISSING_BREAKPOINT,
    MISSION_IDENTITY_MISMATCH,
    BREAKPOINT_MISMATCH,
    LASER_ENABLED,
    TARGET_ALIGNMENT_ACTIVE,
    COMPETING_FIRE_SESSION,
    TELEMETRY_UNAVAILABLE,
    TELEMETRY_STALE,
    HOVER_STABILITY_TIMEOUT,
    TERMINAL_RESULT_NOT_DURABLE,
    HOVER_COMMAND_FAILED,
    PAUSE_COMMAND_FAILED,
    RESUME_COMMAND_FAILED,
    CONTROL_CLOSED,
}

data class MissionIdentity(
    val missionId: String,
    val missionFileName: String,
)

data class MissionBreakpoint(
    val waylineId: Int,
    val waypointId: Int,
    val segmentProgress: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val recoverAction: String? = null,
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

sealed interface HoverSafetyDecision {
    data object Waiting : HoverSafetyDecision
    data object Stable : HoverSafetyDecision
    data class ManualHold(val reason: FlightSafetyReason) : HoverSafetyDecision
}

data class ResumeSafetyContext(
    val terminalResultDurable: Boolean,
    val expectedMission: MissionIdentity,
    val observedMission: MissionIdentity?,
    val expectedBreakpoint: MissionBreakpoint,
    val observedBreakpoint: MissionBreakpoint?,
    val laserEnabled: Boolean,
    val targetAlignmentClosed: Boolean,
    val anotherFireSessionActive: Boolean,
    val missionStateKnown: Boolean,
    val telemetryFresh: Boolean,
    val signals: FlightSafetySignals = FlightSafetySignals(),
)

sealed interface ResumeSafetyDecision {
    data object Permitted : ResumeSafetyDecision
    data class ManualHold(val reason: FlightSafetyReason) : ResumeSafetyDecision
}

class FlightSafetyGate {
    private var stableSinceMs: Long? = null
    private var lastFreshObservedAtMs: Long? = null

    @Synchronized
    fun reset() {
        stableSinceMs = null
        lastFreshObservedAtMs = null
    }

    @Synchronized
    fun observeHover(
        hoverStartedAtMonotonicMs: Long,
        sample: FlightTelemetrySample?,
        signals: FlightSafetySignals = FlightSafetySignals(),
        nowMs: Long = sample?.observedAtMonotonicMs ?: hoverStartedAtMonotonicMs,
    ): HoverSafetyDecision {
        signalFailure(signals)?.let {
            resetWindow()
            return HoverSafetyDecision.ManualHold(it)
        }
        if (nowMs - hoverStartedAtMonotonicMs >= FlightSafetyPolicy.HOVER_TIMEOUT_MS) {
            resetWindow()
            return HoverSafetyDecision.ManualHold(FlightSafetyReason.HOVER_STABILITY_TIMEOUT)
        }
        if (sample == null) {
            resetWindow()
            return HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_UNAVAILABLE)
        }
        require(sample.observedAtMonotonicMs == nowMs) {
            "nowMs must use the same monotonic time base as the telemetry sample"
        }
        val ageMs = nowMs - sample.capturedAtMonotonicMs
        if (ageMs < 0 || ageMs > FlightSafetyPolicy.TELEMETRY_STALE_AFTER_MS) {
            resetWindow()
            return HoverSafetyDecision.Waiting
        }
        val inBounds =
            sample.horizontalSpeedMps.isFinite() &&
                sample.verticalSpeedMps.isFinite() &&
                sample.horizontalSpeedMps >= 0.0 &&
                sample.horizontalSpeedMps <= FlightSafetyPolicy.MAX_HORIZONTAL_SPEED_MPS &&
                abs(sample.verticalSpeedMps) <= FlightSafetyPolicy.MAX_ABSOLUTE_VERTICAL_SPEED_MPS
        if (!inBounds) {
            resetWindow()
            return HoverSafetyDecision.Waiting
        }
        if (lastFreshObservedAtMs?.let { nowMs - it > FlightSafetyPolicy.TELEMETRY_STALE_AFTER_MS } == true) {
            stableSinceMs = null
        }
        lastFreshObservedAtMs = nowMs
        val startedAt = stableSinceMs ?: nowMs.also { stableSinceMs = it }
        return if (nowMs - startedAt >= FlightSafetyPolicy.REQUIRED_STABLE_HOVER_MS) {
            HoverSafetyDecision.Stable
        } else {
            HoverSafetyDecision.Waiting
        }
    }

    fun evaluateResume(context: ResumeSafetyContext): ResumeSafetyDecision {
        val reason = signalFailure(context.signals)
            ?: FlightSafetyReason.TERMINAL_RESULT_NOT_DURABLE.takeUnless { context.terminalResultDurable }
            ?: FlightSafetyReason.UNKNOWN_MISSION_STATE.takeUnless { context.missionStateKnown }
            ?: FlightSafetyReason.TELEMETRY_STALE.takeUnless { context.telemetryFresh }
            ?: FlightSafetyReason.MISSION_IDENTITY_MISMATCH.takeUnless {
                context.observedMission == context.expectedMission
            }
            ?: FlightSafetyReason.MISSING_BREAKPOINT.takeIf { context.observedBreakpoint == null }
            ?: FlightSafetyReason.BREAKPOINT_MISMATCH.takeUnless {
                context.observedBreakpoint == context.expectedBreakpoint
            }
            ?: FlightSafetyReason.LASER_ENABLED.takeIf { context.laserEnabled }
            ?: FlightSafetyReason.TARGET_ALIGNMENT_ACTIVE.takeUnless { context.targetAlignmentClosed }
            ?: FlightSafetyReason.COMPETING_FIRE_SESSION.takeIf { context.anotherFireSessionActive }
        return reason?.let(ResumeSafetyDecision::ManualHold) ?: ResumeSafetyDecision.Permitted
    }

    private fun signalFailure(signals: FlightSafetySignals): FlightSafetyReason? = when {
        signals.manualTakeover -> FlightSafetyReason.MANUAL_CONTROL_TAKEOVER
        signals.lowBattery -> FlightSafetyReason.LOW_BATTERY
        signals.rthActive -> FlightSafetyReason.RETURN_TO_HOME_ACTIVE
        signals.obstacleAvoidanceActive -> FlightSafetyReason.OBSTACLE_AVOIDANCE_INTERVENTION
        signals.flightError -> FlightSafetyReason.FLIGHT_ERROR
        else -> null
    }

    private fun resetWindow() {
        stableSinceMs = null
        lastFreshObservedAtMs = null
    }
}
