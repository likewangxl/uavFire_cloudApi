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

    @Test
    fun `persisted version survives restart and stale arm cannot overwrite newer disarm`() {
        val store = InMemoryDetectorIntentStore()
        val first = VisibleDetectorControl(store) { health() }
        assertTrue(first.applyIntent("ARMED", 5L).applied)
        assertTrue(first.snapshot().running)

        val restarted = VisibleDetectorControl(store) { health() }
        assertEquals(5L, restarted.snapshot().intentVersion)
        assertEquals("ARMED", restarted.snapshot().intent)
        assertTrue(restarted.applyIntent("DISARMED", 6L).applied)

        val stale = restarted.applyIntent("ARMED", 5L)
        assertFalse(stale.applied)
        assertEquals("stale-intent-version", stale.reason)
        assertEquals("DISARMED", restarted.snapshot().intent)
        assertEquals(6L, restarted.snapshot().intentVersion)
    }

    @Test
    fun `same version and same intent is idempotent but conflict is rejected`() {
        val control = VisibleDetectorControl { health() }
        assertTrue(control.applyIntent("ARMED", 2L).applied)
        assertTrue(control.applyIntent("ARMED", 2L).applied)
        val conflict = control.applyIntent("DISARMED", 2L)
        assertFalse(conflict.applied)
        assertEquals("intent-version-conflict", conflict.reason)
        assertEquals("ARMED", control.snapshot().intent)
    }
}
