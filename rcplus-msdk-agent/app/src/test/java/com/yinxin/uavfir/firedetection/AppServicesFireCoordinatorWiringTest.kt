package com.yinxin.uavfir.firedetection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServicesFireCoordinatorWiringTest {
    private val source = File("src/main/java/com/yinxin/uavfir/AppServices.kt").readText()

    @Test
    fun `production wiring uses Tasks 5 through 8 and no legacy staged success`() {
        listOf(
            "AgentFireConfirmationBridge(",
            "SqliteCoordinatorStoreAdapter(",
            "StoreBackedCoordinatorOutboxPort(",
            "Task7CoordinatorMissionPort(",
            "Task8CoordinatorLocalizationPort(",
            "AgentFireClosedLoopCoordinator(",
            "AgentFireRecoveryCoordinator(",
        ).forEach { assertTrue("missing $it", source.contains(it)) }
        assertTrue(source.contains("AgentFireReportTransport(api)"))
        assertFalse(source.contains("task10-transport-unavailable"))
        assertFalse(source.contains("SendOutcome.Acknowledged"))
        assertFalse(source.contains("holdLocal("))
    }

    @Test
    fun `unfinished backend durability and RC safety evidence fail closed behind default flag`() {
        assertTrue(source.contains("featureEnabled = BuildConfig.VISIBLE_FIRE_DETECTION_ENABLED"))
        assertTrue(source.contains("outboxHealthy = false"))
        assertTrue(source.contains("safetyAdaptersHealthy = false"))
        assertTrue(source.contains("competingOwnerActive = visibleFireLaserLocator.hasActiveOwnership()"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"VISIBLE_FIRE_DETECTION_ENABLED\", \"false\")"))
    }

    @Test
    fun `startup force-safe recovery precedes inference start`() {
        val recovery = source.indexOf("fireRecoveryCoordinator.recover()")
        val inference = source.indexOf("visibleInferenceLoop?.start(appScope)")
        assertTrue(recovery >= 0 && inference > recovery)
    }
}
