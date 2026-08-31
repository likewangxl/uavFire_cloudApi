package com.yinxin.uavfir.ui

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.sdk.DjiDeviceState
import com.yinxin.uavfir.sdk.DjiFlightLimit
import com.yinxin.uavfir.sdk.DjiHomeStatus
import com.yinxin.uavfir.sdk.DjiStorageStatus
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ValidationConsoleControllerTest {
    @Test
    fun buildHomeStatus_usesConnectedAircraftLimitsAndStorage() {
        val homeStatus = ValidationConsoleController.buildHomeStatus(
            deviceState = DjiDeviceState(
                connectionState = AgentConnectionState.CAPABILITY_READY,
                aircraftName = "森林灭火 M300-01",
                aircraftModel = "M300_RTK",
                flightLimit = DjiFlightLimit(
                    heightLimitMeters = 120,
                    distanceLimitEnabled = true,
                    distanceLimitMeters = 500,
                ),
            ),
            storageStatus = DjiStorageStatus(
                freeBytes = 23_600_000_000L,
                totalBytes = 64_000_000_000L,
            ),
        )

        assertEquals(
            DjiHomeStatus(
                flightLimitText = "飞行限制\n限高120m / 限距500m",
                taskSpaceText = "任务空间\n可用 23.6GB / 总计 64.0GB",
                aircraftStatusText = "当前飞行器\n森林灭火 M300-01    已连接",
            ),
            homeStatus,
        )
    }

    @Test
    fun buildHomeStatus_reportsDisconnectedFallbacks() {
        val homeStatus = ValidationConsoleController.buildHomeStatus(
            deviceState = DjiDeviceState(connectionState = AgentConnectionState.SDK_READY),
            storageStatus = DjiStorageStatus(freeBytes = null, totalBytes = null),
        )

        assertEquals(
            DjiHomeStatus(
                flightLimitText = "飞行限制\n未连接",
                taskSpaceText = "任务空间\n读取中",
                aircraftStatusText = "当前飞行器\n未获取设备名称    未连接",
            ),
            homeStatus,
        )
    }

    @Test
    fun buildHomeStatus_usesRealProductTypeWhenAircraftNameIsUnavailable() {
        val homeStatus = ValidationConsoleController.buildHomeStatus(
            deviceState = DjiDeviceState(
                connectionState = AgentConnectionState.AIRCRAFT_CONNECTED,
                aircraftName = "UNKNOWN",
                aircraftModel = "M300_RTK",
            ),
            storageStatus = DjiStorageStatus(freeBytes = 1L, totalBytes = 2L),
        )

        assertEquals("当前飞行器\nDJI M300 RTK    已连接", homeStatus.aircraftStatusText)
    }

    @Test
    fun refreshDeviceStatus_reportsConnectedCapabilityState() = runTest {
        val controller = ValidationConsoleController(
            deviceSession = FakeDjiDeviceSession(
                DjiDeviceState(
                    connectionState = AgentConnectionState.CAPABILITY_READY,
                    capability = CameraCapability(
                        visibleSupported = true,
                        thermalSupported = true,
                    ),
                ),
            ),
            commandExecutor = FakeCommandExecutor(
                DualStreamSessionManager.CommandExecutionResult(status = "applied"),
            ),
        )

        val result = controller.refreshDeviceStatus("DRONE-001")

        assertEquals(
            "连接状态: CAPABILITY_READY\n可见光: true\n红外: true",
            result.statusText,
        )
    }

    @Test
    fun startDualStream_reportsSharedPreviewAsRunningForBothChannels() = runTest {
        val controller = ValidationConsoleController(
            deviceSession = FakeDjiDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.SDK_READY),
            ),
            commandExecutor = FakeCommandExecutor(
                DualStreamSessionManager.CommandExecutionResult(
                    status = "applied",
                    message = "single-liveview-source-shared-side-by-side-preview",
                    visibleState = BoundStreamState.BOUND,
                    thermalState = BoundStreamState.BOUND,
                ),
            ),
        )

        val result = controller.startDualStream("DRONE-001")

        assertEquals(
            "双流启动结果: applied\n可见光: running\n红外: running\n原因: single-liveview-source-shared-side-by-side-preview",
            result.statusText,
        )
    }

    private class FakeDjiDeviceSession(
        private val state: DjiDeviceState,
    ) : DjiDeviceSessionAdapter {
        override suspend fun initialize(): DjiDeviceState = state
    }

    private class FakeCommandExecutor(
        private val result: DualStreamSessionManager.CommandExecutionResult,
    ) : DualStreamCommandExecutor {
        override val sessionState: DualStreamSessionState = DualStreamSessionState.INIT

        override suspend fun executeCommand(
            droneSn: String,
            action: String,
        ): DualStreamSessionManager.CommandExecutionResult = result
    }
}
