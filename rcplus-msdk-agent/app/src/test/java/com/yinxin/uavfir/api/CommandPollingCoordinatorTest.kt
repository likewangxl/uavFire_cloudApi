package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.BoundStreamState
import com.yinxin.uavfir.stream.MockStreamProvider
import com.yinxin.uavfir.stream.StreamProvider
import com.yinxin.uavfir.stream.StreamStartResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPollingCoordinatorTest {
    @Test
    fun pollOnce_startCommandStartsSession_andAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-1",
                    droneSn = "DRONE-001",
                    action = "start",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-1", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertEquals("RUNNING", manager.state.value.name)
    }

    @Test
    fun pollOnce_withoutCommandDoesNotAcknowledge() = runTest {
        val api = RecordingDualStreamApi(nextCommand = null)
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(MockStreamProvider()),
        )

        coordinator.pollOnce("DRONE-001")

        assertNull(api.lastAck)
    }

    @Test
    fun pollOnce_stopCommandStopsSession_andAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-2",
                    droneSn = "DRONE-001",
                    action = "stop",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        manager.start("DRONE-001")
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-2", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertEquals("STOPPED", manager.state.value.name)
    }

    @Test
    fun pollOnce_focusVisibleAcknowledgesApplied() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-3",
                    droneSn = "DRONE-001",
                    action = "focus-visible",
                    status = "pending",
                ),
            ),
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(MockStreamProvider()),
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-3", api.lastAck?.commandId)
        assertEquals("applied", api.lastAck?.status)
        assertNull(api.lastAck?.message)
    }

    @Test
    fun pollOnce_hangingCommandAcknowledgesFailedTimeout() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-timeout",
                    droneSn = "DRONE-001",
                    action = "start",
                    status = "pending",
                ),
            ),
        )
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = DualStreamSessionManager(HangingStreamProvider()),
            commandTimeoutMs = 100,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-timeout", api.lastAck?.commandId)
        assertEquals("failed", api.lastAck?.status)
        assertEquals("command-timeout:start", api.lastAck?.message)
    }

    @Test
    fun pollOnce_commandForDifferentDroneAcknowledgesIgnored() = runTest {
        val api = RecordingDualStreamApi(
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-4",
                    droneSn = "DRONE-OTHER",
                    action = "start",
                    status = "pending",
                ),
            ),
        )
        val manager = DualStreamSessionManager(MockStreamProvider())
        val coordinator = CommandPollingCoordinator(
            client = AgentBackendClient(api),
            sessionManager = manager,
        )

        coordinator.pollOnce("DRONE-001")

        assertEquals("cmd-4", api.lastAck?.commandId)
        assertEquals("ignored", api.lastAck?.status)
        assertTrue(api.lastAck?.message?.contains("DRONE-OTHER") == true)
        assertEquals("INIT", manager.state.value.name)
    }

    private class RecordingDualStreamApi(
        private val nextCommand: AgentApiEnvelope<AgentCommandResponse>?,
    ) : DualStreamApi {
        var lastAck: AgentCommandAckRequest? = null

        override suspend fun heartbeat(
            droneSn: String,
            body: AgentHeartbeatRequest,
        ) = Unit

        override suspend fun status(
            droneSn: String,
            body: AgentStatusRequest,
        ) = Unit

        override suspend fun capability(
            droneSn: String,
            body: CapabilityReportRequest,
        ) = Unit

        override suspend fun pollCommand(droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = nextCommand

        override suspend fun ackCommand(
            droneSn: String,
            body: AgentCommandAckRequest,
        ) {
            lastAck = body
        }
    }

    private class HangingStreamProvider : StreamProvider {
        override suspend fun start(droneSn: String): StreamStartResult {
            awaitCancellation()
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
    }
}
