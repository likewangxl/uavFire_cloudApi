package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionState
import com.yinxin.uavfir.stream.BoundStreamState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBackendClientTest {
    @Test
    fun heartbeatPayload_containsDroneSnAndSessionState() {
        val payload = AgentHeartbeatRequest(
            droneSn = "DRONE-001",
            connectionState = AgentConnectionState.STREAMING.name,
            sessionState = DualStreamSessionState.RUNNING.name,
        )

        assertEquals("DRONE-001", payload.droneSn)
        assertEquals("STREAMING", payload.connectionState)
        assertEquals("RUNNING", payload.sessionState)
    }

    @Test
    fun buildHeartbeatRequest_usesEnumNamesForStates() {
        val client = AgentBackendClient(api = RecordingDualStreamApi())

        val payload = client.buildHeartbeatRequest(
            droneSn = "DRONE-002",
            connectionState = AgentConnectionState.CAPABILITY_READY,
            sessionState = DualStreamSessionState.STARTING,
        )

        assertEquals("DRONE-002", payload.droneSn)
        assertEquals("CAPABILITY_READY", payload.connectionState)
        assertEquals("STARTING", payload.sessionState)
    }

    @Test
    fun buildStatusRequest_preservesMessageBoundary() {
        val client = AgentBackendClient(api = RecordingDualStreamApi())

        val payload = client.buildStatusRequest(
            droneSn = "DRONE-003",
            connectionState = AgentConnectionState.DEGRADED,
            message = "thermal stream reconnecting",
        )

        assertEquals("DRONE-003", payload.droneSn)
        assertEquals("DEGRADED", payload.connectionState)
        assertEquals("thermal stream reconnecting", payload.message)
        assertEquals("awaiting-media-url", payload.playbackStatus)
        assertEquals(null, payload.visiblePlayUrl)
        assertEquals(null, payload.thermalPlayUrl)
    }

    @Test
    fun buildStatusRequest_usesRuntimePlaybackStatusWhenThermalPreviewSharesVisibleFeed() {
        val client = AgentBackendClient(api = RecordingDualStreamApi())

        val payload = client.buildStatusRequest(
            droneSn = "DRONE-THERMAL",
            connectionState = AgentConnectionState.CAPABILITY_READY,
            message = "shared-preview",
            runtimeStatus = DualStreamCommandExecutor.RuntimeStatus(
                sessionState = DualStreamSessionState.RUNNING,
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                failureReason = "single-liveview-source-shared-side-by-side-preview",
                playbackStatus = "shared-side-by-side-preview",
            ),
        )

        assertEquals("shared-side-by-side-preview", payload.playbackStatus)
        assertEquals("running", payload.visibleState)
        assertEquals("running", payload.thermalState)
    }

    @Test
    fun buildCapabilityReportRequest_mapsVisibleAndThermalFlags() {
        val client = AgentBackendClient(api = RecordingDualStreamApi())

        val payload = client.buildCapabilityReportRequest(
            droneSn = "DRONE-004",
            capability = CameraCapability(
                visibleSupported = true,
                thermalSupported = false,
            ),
        )

        assertEquals("DRONE-004", payload.droneSn)
        assertTrue(payload.visibleSupported)
        assertFalse(payload.thermalSupported)
    }

    @Test
    fun sendHeartbeat_postsPayloadToApi() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.sendHeartbeat(
            droneSn = "DRONE-005",
            connectionState = AgentConnectionState.STREAMING,
            sessionState = DualStreamSessionState.RUNNING,
        )

        assertEquals("DRONE-005", api.lastHeartbeatDroneSn)
        assertEquals("RUNNING", api.lastHeartbeatBody?.sessionState)
    }

    @Test
    fun sendStatus_postsPayloadToApi() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.sendStatus(
            droneSn = "DRONE-006",
            connectionState = AgentConnectionState.DEGRADED,
            message = "visible stream reconnecting",
        )

        assertEquals("DRONE-006", api.lastStatusDroneSn)
        assertEquals("visible stream reconnecting", api.lastStatusBody?.message)
    }

    @Test
    fun sendCapability_postsPayloadToApi() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.sendCapability(
            droneSn = "DRONE-007",
            capability = CameraCapability(
                visibleSupported = true,
                thermalSupported = true,
            ),
        )

        assertEquals("DRONE-007", api.lastCapabilityDroneSn)
        assertTrue(api.lastCapabilityBody?.visibleSupported == true)
        assertTrue(api.lastCapabilityBody?.thermalSupported == true)
    }

    @Test
    fun pollCommand_returnsCommandFromApi() = runTest {
        val api = RecordingDualStreamApi().apply {
            nextCommand = AgentApiEnvelope(
                data = AgentCommandResponse(
                    commandId = "cmd-1",
                    droneSn = "DRONE-008",
                    action = "start",
                    status = "pending",
                ),
            )
        }
        val client = AgentBackendClient(api = api)

        val command = client.pollCommand("DRONE-008")

        assertEquals("cmd-1", command?.commandId)
        assertEquals("start", command?.action)
    }

    @Test
    fun ackCommand_postsAckPayloadToApi() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.ackCommand(
            droneSn = "DRONE-009",
            commandId = "cmd-2",
            status = "applied",
            message = "session running",
        )

        assertEquals("DRONE-009", api.lastAckDroneSn)
        assertEquals("cmd-2", api.lastAckBody?.commandId)
        assertEquals("applied", api.lastAckBody?.status)
        assertEquals("session running", api.lastAckBody?.message)
    }

    private class RecordingDualStreamApi : DualStreamApi {
        var lastHeartbeatDroneSn: String? = null
        var lastHeartbeatBody: AgentHeartbeatRequest? = null
        var lastStatusDroneSn: String? = null
        var lastStatusBody: AgentStatusRequest? = null
        var lastCapabilityDroneSn: String? = null
        var lastCapabilityBody: CapabilityReportRequest? = null
        var nextCommand: AgentApiEnvelope<AgentCommandResponse>? = null
        var lastAckDroneSn: String? = null
        var lastAckBody: AgentCommandAckRequest? = null

        override suspend fun heartbeat(
            droneSn: String,
            body: AgentHeartbeatRequest,
        ) {
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

        override suspend fun pollCommand(droneSn: String): AgentApiEnvelope<AgentCommandResponse>? = nextCommand

        override suspend fun ackCommand(
            droneSn: String,
            body: AgentCommandAckRequest,
        ) {
            lastAckDroneSn = droneSn
            lastAckBody = body
        }
    }
}
