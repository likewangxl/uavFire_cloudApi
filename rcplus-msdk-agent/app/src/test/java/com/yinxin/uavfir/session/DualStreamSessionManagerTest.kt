package com.yinxin.uavfir.session

import com.yinxin.uavfir.stream.MockStreamProvider
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import com.yinxin.uavfir.firedetection.CoordinatorArmingHealth
import com.yinxin.uavfir.firedetection.VisibleDetectorControl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualStreamSessionManagerTest {
    private fun healthyDetectorGates() = CoordinatorArmingHealth(
        featureEnabled = true,
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
    fun detectorArmAndDisarmCommandsExposeFailClosedLocalState() = runTest {
        val control = VisibleDetectorControl {
            CoordinatorArmingHealth(
                featureEnabled = false,
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
        }
        val manager = DualStreamSessionManager(MockStreamProvider(), visibleDetectorControl = control)

        assertEquals("applied", manager.executeCommand("DRONE-001", "visible-detector-arm").status)
        assertEquals("BLOCKED", manager.detectorStatus().state)
        assertEquals("UNHEALTHY", manager.detectorStatus().health)
        assertEquals("applied", manager.executeCommand("DRONE-001", "visible-detector-disarm").status)
        assertEquals("DISARMED", manager.detectorStatus().state)
    }

    @Test
    fun detectorIntentCommandsApplyBackendVersionAndRejectStaleArm() = runTest {
        val control = VisibleDetectorControl { healthyDetectorGates() }
        val manager = DualStreamSessionManager(MockStreamProvider(), visibleDetectorControl = control)

        assertEquals("applied", manager.executeCommand("DRONE-001", "visible-detector-disarm", mapOf("intentVersion" to 8L)).status)
        assertEquals("ignored", manager.executeCommand("DRONE-001", "visible-detector-arm", mapOf("intentVersion" to 7L)).status)
        assertEquals("DISARMED", manager.detectorStatus().intent)
        assertEquals(8L, manager.detectorStatus().intentVersion)
        assertEquals("applied", manager.executeCommand("DRONE-001", "visible-detector-disarm", mapOf("intentVersion" to 8L)).status)
        assertEquals("failed", manager.executeCommand("DRONE-001", "visible-detector-arm", mapOf("intentVersion" to 8.5)).status)
    }

    @Test
    fun startSession_movesFromInitToRunning() = runTest {
        val manager = DualStreamSessionManager(MockStreamProvider())

        manager.start("DRONE-001")

        assertEquals(DualStreamSessionState.RUNNING, manager.state.value)
    }

    @Test
    fun stopSession_movesToStopped() = runTest {
        val manager = DualStreamSessionManager(MockStreamProvider())

        manager.start("DRONE-001")
        manager.stop()

        assertEquals(DualStreamSessionState.STOPPED, manager.state.value)
    }

    @Test
    fun startSession_movesToFailedWhenProviderThrows() = runTest {
        val manager = DualStreamSessionManager(
            streamProvider = object : StreamProvider {
                override suspend fun start(droneSn: String): StreamStartResult {
                    throw IllegalStateException("thermal unavailable")
                }

                override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                )

                override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.BOUND,
                )

                override suspend fun stop() = Unit
            },
        )

        manager.start("DRONE-001")

        assertEquals(DualStreamSessionState.FAILED, manager.state.value)
    }

    @Test
    fun executeCommand_returnsAppliedWhenVisibleStartsAndThermalDegrades() = runTest {
        val manager = DualStreamSessionManager(
            streamProvider = object : StreamProvider {
                override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                    thermalFailureMessage = "msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding",
                )

                override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                )

                override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.BOUND,
                    playbackStatus = "shared-side-by-side-preview",
                )

                override suspend fun stop() = Unit
            },
        )

        val result = manager.executeCommand("DRONE-001", "start")

        assertEquals("applied", result.status)
        assertEquals(BoundStreamState.BOUND, result.visibleState)
        assertEquals(BoundStreamState.IDLE, result.thermalState)
        assertTrue(
            result.message?.contains(
                "msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding",
            ) == true,
        )
    }

    @Test
    fun runtimeStatus_exposesVisibleThermalAndReasonAfterStart() = runTest {
        val manager = DualStreamSessionManager(
            streamProvider = object : StreamProvider {
                override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                    thermalFailureMessage = "thermal-stream-source-unavailable",
                )

                override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                )

                override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.BOUND,
                    playbackStatus = "shared-side-by-side-preview",
                )

                override suspend fun stop() = Unit
            },
        )

        manager.start("DRONE-001")
        val runtimeStatus = manager.runtimeStatus()

        assertEquals(DualStreamSessionState.RUNNING, runtimeStatus.sessionState)
        assertEquals(BoundStreamState.BOUND, runtimeStatus.visibleState)
        assertEquals(BoundStreamState.IDLE, runtimeStatus.thermalState)
        assertEquals("thermal-stream-source-unavailable", runtimeStatus.failureReason)
    }

    @Test
    fun runtimeStatus_clearsFailureReasonAfterStop() = runTest {
        val manager = DualStreamSessionManager(MockStreamProvider())

        manager.start("DRONE-001")
        manager.stop()
        val runtimeStatus = manager.runtimeStatus()

        assertEquals(DualStreamSessionState.STOPPED, runtimeStatus.sessionState)
        assertNull(runtimeStatus.failureReason)
    }

    @Test
    fun executeCommand_focusThermal_returnsAppliedSharedPreviewState() = runTest {
        val manager = DualStreamSessionManager(
            streamProvider = object : StreamProvider {
                override suspend fun start(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                )

                override suspend fun focusVisible(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.IDLE,
                    playbackStatus = "visible-live-ready",
                )

                override suspend fun focusThermal(droneSn: String): StreamStartResult = StreamStartResult(
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.BOUND,
                    playbackStatus = "shared-side-by-side-preview",
                    thermalFailureMessage = "single-liveview-source-shared-side-by-side-preview",
                    thermalCenterTemperatureC = 88.5,
                )

                override suspend fun stop() = Unit
            },
        )

        manager.start("DRONE-001")
        val result = manager.executeCommand("DRONE-001", "focus-thermal")
        val runtimeStatus = manager.runtimeStatus()

        assertEquals("applied", result.status)
        assertEquals(BoundStreamState.BOUND, result.visibleState)
        assertEquals(BoundStreamState.BOUND, result.thermalState)
        assertEquals("shared-side-by-side-preview", runtimeStatus.playbackStatus)
        assertEquals("single-liveview-source-shared-side-by-side-preview", runtimeStatus.failureReason)
        assertEquals(88.5, runtimeStatus.thermalCenterTemperatureC)
    }
}
