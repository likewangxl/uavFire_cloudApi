package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightSafetyGateTest {
    private val gate = FlightSafetyGate()

    @Test
    fun hoverRequiresExactOneSecondOfContinuousBoundarySpeedSamples() {
        assertEquals(
            HoverSafetyDecision.Waiting,
            gate.observeHover(0, sample(0, 0.3, -0.2)),
        )
        assertEquals(
            HoverSafetyDecision.Waiting,
            gate.observeHover(0, sample(500, 0.3, 0.2)),
        )
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(999, 0.3, 0.2)))
        assertEquals(
            HoverSafetyDecision.Stable,
            gate.observeHover(0, sample(1_000, 0.3, 0.2)),
        )
    }

    @Test
    fun outOfBoundsAndStaleSamplesResetContinuousWindow() {
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(0, 0.1, 0.0)))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(700, 0.31, 0.0)))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(1_600, 0.1, 0.0)))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(2_100, 0.1, 0.0)))
        assertEquals(HoverSafetyDecision.Stable, gate.observeHover(0, sample(2_600, 0.1, 0.0)))

        gate.reset()
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(0, 0.1, 0.0)))
        assertEquals(
            HoverSafetyDecision.Waiting,
            gate.observeHover(0, sample(800, 0.1, 0.0, capturedAtMs = 200)),
        )
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(1_800, 0.1, 0.0)))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(0, sample(2_300, 0.1, 0.0)))
        assertEquals(HoverSafetyDecision.Stable, gate.observeHover(0, sample(2_800, 0.1, 0.0)))
    }

    @Test
    fun unavailableTelemetryAndEightSecondBoundaryFailClosed() {
        assertEquals(
            HoverSafetyDecision.ManualHold(FlightSafetyReason.TELEMETRY_UNAVAILABLE),
            gate.observeHover(0, null, nowMs = 7_999),
        )
        assertEquals(
            HoverSafetyDecision.ManualHold(FlightSafetyReason.HOVER_STABILITY_TIMEOUT),
            gate.observeHover(0, sample(8_000, 1.0, 1.0)),
        )
    }

    @Test
    fun manualAndAircraftSafetySignalsFailClosed() {
        val cases = listOf(
            FlightSafetySignals(manualTakeover = true) to FlightSafetyReason.MANUAL_CONTROL_TAKEOVER,
            FlightSafetySignals(lowBattery = true) to FlightSafetyReason.LOW_BATTERY,
            FlightSafetySignals(rthActive = true) to FlightSafetyReason.RETURN_TO_HOME_ACTIVE,
            FlightSafetySignals(obstacleAvoidanceActive = true) to FlightSafetyReason.OBSTACLE_AVOIDANCE_INTERVENTION,
            FlightSafetySignals(flightError = true) to FlightSafetyReason.FLIGHT_ERROR,
        )

        cases.forEach { (signals, reason) ->
            gate.reset()
            assertEquals(
                HoverSafetyDecision.ManualHold(reason),
                gate.observeHover(0, sample(0, 0.0, 0.0), signals),
            )
        }
    }

    @Test
    fun resumeRequiresEverySafetyAndDurabilityGate() {
        val identity = MissionIdentity("mission-1", "route.kmz")
        val breakpoint = MissionBreakpoint(1, 4, 0.25)
        val valid = ResumeSafetyContext(
            terminalResultDurable = true,
            expectedMission = identity,
            observedMission = identity,
            expectedBreakpoint = breakpoint,
            observedBreakpoint = breakpoint,
            laserEnabled = false,
            targetAlignmentClosed = true,
            anotherFireSessionActive = false,
            missionStateKnown = true,
            telemetryFresh = true,
        )
        assertEquals(ResumeSafetyDecision.Permitted, gate.evaluateResume(valid))

        val failures = listOf(
            valid.copy(terminalResultDurable = false) to FlightSafetyReason.TERMINAL_RESULT_NOT_DURABLE,
            valid.copy(observedMission = identity.copy(missionId = "other")) to FlightSafetyReason.MISSION_IDENTITY_MISMATCH,
            valid.copy(observedBreakpoint = breakpoint.copy(waypointId = 5)) to FlightSafetyReason.BREAKPOINT_MISMATCH,
            valid.copy(observedBreakpoint = null) to FlightSafetyReason.MISSING_BREAKPOINT,
            valid.copy(laserEnabled = true) to FlightSafetyReason.LASER_ENABLED,
            valid.copy(targetAlignmentClosed = false) to FlightSafetyReason.TARGET_ALIGNMENT_ACTIVE,
            valid.copy(anotherFireSessionActive = true) to FlightSafetyReason.COMPETING_FIRE_SESSION,
            valid.copy(missionStateKnown = false) to FlightSafetyReason.UNKNOWN_MISSION_STATE,
            valid.copy(telemetryFresh = false) to FlightSafetyReason.TELEMETRY_STALE,
            valid.copy(signals = FlightSafetySignals(manualTakeover = true)) to FlightSafetyReason.MANUAL_CONTROL_TAKEOVER,
            valid.copy(signals = FlightSafetySignals(lowBattery = true)) to FlightSafetyReason.LOW_BATTERY,
            valid.copy(signals = FlightSafetySignals(rthActive = true)) to FlightSafetyReason.RETURN_TO_HOME_ACTIVE,
            valid.copy(signals = FlightSafetySignals(obstacleAvoidanceActive = true)) to FlightSafetyReason.OBSTACLE_AVOIDANCE_INTERVENTION,
            valid.copy(signals = FlightSafetySignals(flightError = true)) to FlightSafetyReason.FLIGHT_ERROR,
        )

        failures.forEach { (context, reason) ->
            assertEquals(ResumeSafetyDecision.ManualHold(reason), gate.evaluateResume(context))
        }
        assertTrue(FlightSafetyPolicy.HOVER_TIMEOUT_MS == 8_000L)
    }

    private fun sample(
        nowMs: Long,
        horizontal: Double,
        vertical: Double,
        capturedAtMs: Long = nowMs,
    ) = FlightTelemetrySample(
        capturedAtMonotonicMs = capturedAtMs,
        observedAtMonotonicMs = nowMs,
        horizontalSpeedMps = horizontal,
        verticalSpeedMps = vertical,
    )
}
