package com.yinxin.uavfir.firedetection

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

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
        assertEquals("BLOCKED", restarted.snapshot().state)
        assertEquals("authority-reconciliation-required", restarted.snapshot().reason)
        assertFalse(restarted.snapshot().running)
        assertFalse(restarted.isArmRequested())
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

    @Test
    fun `store load exception fails closed until authoritative command arrives`() {
        val store = object : DetectorIntentStore {
            override fun load(): PersistedDetectorIntent? = error("disk-read-failed")
            override fun save(intent: PersistedDetectorIntent): Boolean = true
        }
        val control = VisibleDetectorControl(store) { health() }

        assertEquals("BLOCKED", control.snapshot().state)
        assertEquals("UNHEALTHY", control.snapshot().health)
        assertTrue(control.snapshot().reason!!.startsWith("intent-store-load-failed"))
        assertTrue(control.applyIntent("DISARMED", 2L).applied)
        assertEquals("DISARMED", control.snapshot().state)
    }

    @Test
    fun `wrong shared preferences type is diagnosed without startup crash`() {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "contains" -> true
                "getString" -> "ARMED"
                "getLong" -> throw ClassCastException("stored value is Int")
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences

        val control = VisibleDetectorControl(SharedPreferencesDetectorIntentStore(preferences)) { health() }

        assertEquals("BLOCKED", control.snapshot().state)
        assertEquals("UNHEALTHY", control.snapshot().health)
        assertTrue(control.snapshot().reason!!.startsWith("intent-store-load-failed"))
    }

    @Test
    fun `failed persistence never changes detector intent`() {
        val store = object : DetectorIntentStore {
            override fun load(): PersistedDetectorIntent? = null
            override fun save(intent: PersistedDetectorIntent): Boolean = false
        }
        val control = VisibleDetectorControl(store) { health() }

        val result = control.applyIntent("ARMED", 1L)

        assertFalse(result.applied)
        assertEquals("intent-persist-failed", result.reason)
        assertEquals("DISARMED", control.snapshot().intent)
        assertFalse(control.snapshot().running)
    }

    @Test
    fun `invalid persisted intent is blocked until reconciliation`() {
        val store = object : DetectorIntentStore {
            override fun load() = PersistedDetectorIntent("CORRUPT", 9L)
            override fun save(intent: PersistedDetectorIntent) = true
        }

        val control = VisibleDetectorControl(store) { health() }

        assertEquals("BLOCKED", control.snapshot().state)
        assertEquals("intent-store-load-invalid", control.snapshot().reason)
        assertFalse(control.isArmRequested())
    }
}
