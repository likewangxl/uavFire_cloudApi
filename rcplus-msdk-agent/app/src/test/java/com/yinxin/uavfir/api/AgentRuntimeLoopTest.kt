package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.sdk.DjiDeviceSessionAdapter
import com.yinxin.uavfir.sdk.DjiDeviceState
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
import com.yinxin.uavfir.stream.BoundStreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeLoopTest {
    @Test
    fun tickOnce_reportsHeartbeatStatusCapability_andPollsCommands() = runTest {
        val api = RecordingDualStreamApi()
        val reporter = AgentReporter(AgentBackendClient(api))
        val poller = RecordingCommandPoller()
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(
                    connectionState = AgentConnectionState.CAPABILITY_READY,
                    capability = CameraCapability(
                        visibleSupported = true,
                        thermalSupported = true,
                    ),
                ),
            ),
            reporter = reporter,
            commandPoller = poller,
            sessionManager = FakeCommandExecutor(DualStreamSessionState.RUNNING),
            scope = backgroundScope,
        )

        loop.tickOnce("DRONE-001")

        assertEquals("DRONE-001", api.lastHeartbeatDroneSn)
        assertEquals("RUNNING", api.lastHeartbeatBody?.sessionState)
        assertEquals("DRONE-001", api.lastStatusDroneSn)
        assertTrue(api.lastStatusBody?.message?.contains("CAPABILITY_READY") == true)
        assertEquals("running", api.lastStatusBody?.visibleState)
        assertEquals("degraded", api.lastStatusBody?.thermalState)
        assertEquals("thermal-stream-source-unavailable", api.lastStatusBody?.statusReason)
        assertEquals("DRONE-001", api.lastCapabilityDroneSn)
        assertEquals(1, poller.pollCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start_runsRepeatedTicksUntilStopped() = runTest {
        val api = RecordingDualStreamApi()
        val loop = AgentRuntimeLoop(
            deviceSession = FakeDeviceSession(
                DjiDeviceState(connectionState = AgentConnectionState.SDK_READY),
            ),
            reporter = AgentReporter(AgentBackendClient(api)),
            commandPoller = RecordingCommandPoller(),
            sessionManager = FakeCommandExecutor(DualStreamSessionState.INIT),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            intervalMs = 1_000,
        )

        loop.start("DRONE-LOOP")
        advanceTimeBy(2_500)
        loop.stop()
        val heartbeatCountBefore = api.heartbeatCount
        advanceTimeBy(2_000)

        assertTrue(heartbeatCountBefore >= 3)
        assertEquals(heartbeatCountBefore, api.heartbeatCount)
    }

    private class FakeDeviceSession(
        private val state: DjiDeviceState,
    ) : DjiDeviceSessionAdapter {
        override suspend fun initialize(): DjiDeviceState = state
    }

    private class FakeCommandExecutor(
        override val sessionState: DualStreamSessionState,
    ) : DualStreamCommandExecutor {
        override fun runtimeStatus(): DualStreamCommandExecutor.RuntimeStatus = DualStreamCommandExecutor.RuntimeStatus(
            sessionState = sessionState,
            visibleState = BoundStreamState.BOUND,
            thermalState = BoundStreamState.IDLE,
            failureReason = "thermal-stream-source-unavailable",
        )

        override suspend fun executeCommand(
            droneSn: String,
            action: String,
        ): DualStreamSessionManager.CommandExecutionResult = DualStreamSessionManager.CommandExecutionResult(
            status = "ignored",
        )
    }

    private class RecordingCommandPoller : CommandPoller {
        var pollCount: Int = 0

        override suspend fun pollOnce(droneSn: String) {
            pollCount += 1
        }
    }

    private class RecordingDualStreamApi : DualStreamApi {
        var heartbeatCount: Int = 0
        var lastHeartbeatDroneSn: String? = null
        var lastHeartbeatBody: AgentHeartbeatRequest? = null
        var lastStatusDroneSn: String? = null
        var lastStatusBody: AgentStatusRequest? = null
        var lastCapabilityDroneSn: String? = null
        var lastCapabilityBody: CapabilityReportRequest? = null

        override suspend fun heartbeat(
            droneSn: String,
            body: AgentHeartbeatRequest,
        ) {
            heartbeatCount += 1
            lastHeartbeatDroneSn = droneSn
            lastHeartbeatBody = body
        }

        override suspend fun status(
            droneSn: String,
            body: AgentStatusRequest,
        ) {
            lastStatusDroneSn = droneSn
            lastStatusBody = body
        }

        override suspend fun capability(
            droneSn: String,
            body: CapabilityReportRequest,
        ) {
            lastCapabilityDroneSn = droneSn
            lastCapabilityBody = body
        }

        override suspend fun pollCommand(droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = null

        override suspend fun ackCommand(
            droneSn: String,
            body: AgentCommandAckRequest,
        ) = Unit
    }
}
