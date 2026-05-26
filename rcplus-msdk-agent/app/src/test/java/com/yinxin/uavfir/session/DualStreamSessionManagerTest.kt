package com.yinxin.uavfir.session

import com.yinxin.uavfir.stream.MockStreamProvider
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualStreamSessionManagerTest {
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
