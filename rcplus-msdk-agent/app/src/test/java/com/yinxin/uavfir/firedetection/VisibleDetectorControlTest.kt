package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleDetectorControlTest {
    private fun health(featureEnabled: Boolean = true) = CoordinatorArmingHealth(
        featureEnabled = featureEnabled,
        detectorArmRequested = true,
        visibleSourceActive = true,
        sourceGenerationValid = true,
        detectorHealthy = true,
        storeHealthy = true,
        outboxHealthy = true,
        missionAdaptersHealthy = true,
        safetyAdaptersHealthy = true,
        manualHoldActive = false,
        competingOwnerActive = false,
    )

    @Test
    fun `default is disarmed and arm fails closed when a gate is false`() {
        val control = VisibleDetectorControl { health(featureEnabled = false) }
        assertEquals("DISARMED", control.snapshot().state)

        val armed = control.arm()

        assertEquals("ARMED", armed.intent)
        assertEquals("BLOCKED", armed.state)
        assertEquals("UNHEALTHY", armed.health)
        assertEquals("feature-disabled", armed.reason)
        assertFalse(armed.running)
    }

    @Test
    fun `healthy arm reports running and disarm is immediate`() {
        val control = VisibleDetectorControl { health() }
        assertTrue(control.arm().running)
        val stopped = control.disarm()
        assertEquals("DISARMED", stopped.intent)
        assertFalse(stopped.running)
    }
}
