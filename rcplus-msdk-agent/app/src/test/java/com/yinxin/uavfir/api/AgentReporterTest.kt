package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamSessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReporterTest {
    @Test
    fun reporter_postsHeartbeatToBackend() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api)
        val reporter = AgentReporter(client)

        reporter.reportHeartbeat(
            droneSn = "DRONE-001",
            connectionState = AgentConnectionState.STREAMING,
            sessionState = DualStreamSessionState.RUNNING,
        )

        assertEquals("DRONE-001", api.lastHeartbeatDroneSn)
        assertEquals("RUNNING", api.lastHeartbeatBody?.sessionState)
    }

    @Test
    fun reporter_postsCapabilityFlags() = runTest {
        val api = RecordingDualStreamApi()
        val reporter = AgentReporter(AgentBackendClient(api))

        reporter.reportCapability(
            droneSn = "DRONE-001",
            capability = CameraCapability(
                visibleSupported = true,
                thermalSupported = true,
            ),
        )

        assertTrue(api.lastCapabilityBody?.visibleSupported == true)
        assertTrue(api.lastCapabilityBody?.thermalSupported == true)
    }

    @Test
    fun reporter_postsStatusMessageToBackend() = runTest {
        val api = RecordingDualStreamApi()
        val reporter = AgentReporter(AgentBackendClient(api))

        reporter.reportStatus(
            droneSn = "DRONE-002",
            connectionState = AgentConnectionState.DEGRADED,
            message = "thermal stream reconnecting",
        )

        assertEquals("DRONE-002", api.lastStatusDroneSn)
        assertEquals("thermal stream reconnecting", api.lastStatusBody?.message)
    }

    private class RecordingDualStreamApi : DualStreamApi {
        override suspend fun reportAgentFire(body: okhttp3.RequestBody) =
            retrofit2.Response.success(okhttp3.ResponseBody.create(null, "{}"))
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

        override suspend fun recordTaskEvent(
            taskId: String,
            body: DualStreamEventRequest,
        ) = Unit

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) = Unit

        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? = null

        override suspend fun ackMsdkCommand(
            aircraftSn: String,
            body: MsdkCommandAckRequest,
        ) = Unit
    }
}
