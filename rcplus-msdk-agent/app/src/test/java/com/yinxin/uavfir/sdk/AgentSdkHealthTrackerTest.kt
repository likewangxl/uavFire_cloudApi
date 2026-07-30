package com.yinxin.uavfir.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSdkHealthTrackerTest {
    @Test
    fun healthyRequiresRealUxsdkRuntimeMsdkInitializationAndRegistration() {
        val tracker = AgentSdkHealthTracker(
            buildId = "uavfire-agent-0.1.3-4",
            versionName = "0.1.3",
            versionCode = 4,
        )

        tracker.markUxSdkInitialized("a".repeat(64), runtimeClassPresent = true)
        tracker.markMsdkInitialized()
        tracker.markSdkRegistered()

        val snapshot = tracker.snapshot()
        assertTrue(snapshot.uxsdkReal)
        assertTrue(snapshot.msdkReady)
        assertTrue(snapshot.sdkRegistered)
        assertTrue(snapshot.healthy)
    }

    @Test
    fun stubOrMissingUxsdkSourceCannotBecomeHealthy() {
        val tracker = AgentSdkHealthTracker("build", "0.1.3", 4)

        tracker.markUxSdkInitialized("", runtimeClassPresent = true)
        tracker.markMsdkInitialized()
        tracker.markSdkRegistered()

        assertFalse(tracker.snapshot().uxsdkReal)
        assertFalse(tracker.snapshot().healthy)
    }
}
