package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightSafetyGateTest {
    private val mission = MissionExecutionKey(MissionIdentity("m", "m.kmz"), 1)
    private val control = FireControlSessionKey("fire", 1)
    private val binding = HoverControlBinding(control, mission)
    private val gate = FlightSafetyGate()

    @Test
    fun exactBoundariesRequireContinuousFreshSecond() {
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(binding, 0, sample(0, .3, -.2)))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(binding, 0, sample(500, .3, .2)))
        assertTrue(gate.observeHover(binding, 0, sample(1_000, .3, .2)) is HoverSafetyDecision.Stable)
    }

    @Test
    fun staleGapAndOutOfBoundsResetWindow() {
        gate.observeHover(binding, 0, sample(0))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(binding, 0, sample(600)))
        gate.observeHover(binding, 0, sample(1_000))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(binding, 0, sample(1_500, .31)))
        assertEquals(HoverSafetyDecision.Waiting, gate.observeHover(binding, 0, sample(2_000)))
    }

    @Test
    fun timeoutAndSafetySignalsAreTypedManualHold() {
        assertEquals(
            HoverSafetyDecision.ManualHold(FlightSafetyReason.HOVER_STABILITY_TIMEOUT),
            gate.observeHover(binding, 0, sample(8_000, 1.0)),
        )
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
                gate.observeHover(binding, 0, sample(0), signals),
            )
        }
    }

    private fun sample(
        now: Long,
        horizontal: Double = 0.0,
        vertical: Double = 0.0,
    ) = FlightTelemetrySample(now, now, horizontal, vertical)
}
