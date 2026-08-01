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
import okhttp3.RequestBody
import retrofit2.Response
import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.store.OutboxRow
import com.yinxin.uavfir.firedetection.store.OutboxStatus
import com.yinxin.uavfir.firedetection.store.SendOutcome
import com.yinxin.uavfir.firedetection.store.sha256
import okio.Buffer

class AgentBackendClientTest {
    @Test
    fun sendAgentFireReport_preservesDurablePayload() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api)
        val payload = """{"eventId":"event-1","sequence":1}"""
        val row = OutboxRow(
            "event-1", "session-1", 1, 100_000, FireSessionState.VISUAL_CONFIRMED,
            payload, sha256(payload), OutboxStatus.PENDING, 0, 0, null, null,
        )

        assertEquals(SendOutcome.Acknowledged, client.sendAgentFireReport(row))
        assertEquals(payload, api.lastAgentFireBody)
    }

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
    fun buildStatusRequest_includesLatestThermalCenterTemperature() {
        val client = AgentBackendClient(api = RecordingDualStreamApi())

        val payload = client.buildStatusRequest(
            droneSn = "DRONE-THERMAL",
            connectionState = AgentConnectionState.CAPABILITY_READY,
            message = "thermal-center-temperature-ready",
            runtimeStatus = DualStreamCommandExecutor.RuntimeStatus(
                sessionState = DualStreamSessionState.RUNNING,
                visibleState = BoundStreamState.BOUND,
                thermalState = BoundStreamState.BOUND,
                thermalCenterTemperatureC = 87.6,
            ),
        )

        assertEquals(87.6, payload.thermalCenterTemperatureC ?: -1.0, 1e-6)
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
    fun buildMsdkDeviceStateRequest_mapsRuntimeStateAndControlCapabilities() {
        val client = AgentBackendClient(api = RecordingDualStreamApi())

        val payload = client.buildMsdkDeviceStateRequest(
            aircraftSn = "AIRCRAFT-001",
            gatewaySn = "RC-001",
            online = true,
            connectionState = "CAPABILITY_READY",
            deviceName = "DJI Matrice 4T",
            model = "Matrice 4T",
            latitude = 34.123456,
            longitude = 108.123456,
            height = 120.5,
            elevation = 411.0,
            horizontalSpeed = 4.5,
            verticalSpeed = -0.2,
            batteryPercent = 82,
            visibleSupported = true,
            thermalSupported = true,
        )

        assertEquals("AIRCRAFT-001", payload.aircraftSn)
        assertEquals("RC-001", payload.gatewaySn)
        assertEquals(true, payload.online)
        assertEquals("CAPABILITY_READY", payload.connectionState)
        assertEquals("DJI Matrice 4T", payload.deviceName)
        assertEquals("Matrice 4T", payload.model)
        assertEquals(34.123456, payload.latitude)
        assertEquals(108.123456, payload.longitude)
        assertEquals(120.5, payload.height)
        assertEquals(411.0, payload.elevation)
        assertEquals(4.5, payload.horizontalSpeed)
        assertEquals(-0.2, payload.verticalSpeed)
        assertEquals(82, payload.batteryPercent)
        assertEquals(true, payload.capabilities["visibleStream"])
        assertEquals(true, payload.capabilities["thermalFocus"])
        assertEquals(false, payload.capabilities["thermalSecondStream"])
        assertEquals(false, payload.capabilities["takeoff"])
        assertEquals(false, payload.capabilities["land"])
        assertEquals(false, payload.capabilities["flyToPoint"])
        assertEquals(false, payload.capabilities["returnHome"])
        assertEquals(false, payload.capabilities["gimbalReset"])
        assertEquals(false, payload.capabilities["gimbalRotate"])
        assertEquals(false, payload.capabilities["cameraPhoto"])
        assertEquals(false, payload.capabilities["cameraRecord"])
        assertEquals(false, payload.capabilities["cameraStreamSource"])
    }

    @Test
    fun sendMsdkDeviceState_postsPayloadToMsdkStateApi() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)
        val payload = client.buildMsdkDeviceStateRequest(
            aircraftSn = "AIRCRAFT-002",
            gatewaySn = "RC-002",
            online = true,
            connectionState = "SDK_READY",
            deviceName = "DJI Matrice 4T",
            batteryPercent = 76,
        )

        client.sendMsdkDeviceState(payload)

        assertEquals("AIRCRAFT-002", api.lastMsdkDeviceState?.aircraftSn)
        assertEquals("RC-002", api.lastMsdkDeviceState?.gatewaySn)
        assertEquals("DJI Matrice 4T", api.lastMsdkDeviceState?.deviceName)
        assertEquals(76, api.lastMsdkDeviceState?.batteryPercent)
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

    @Test
    fun recordThermalHotspotEvent_postsThermalEventToTaskEndpoint() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)
        val roi = mapOf("x" to 0.42, "y" to 0.46, "width" to 0.08, "height" to 0.08)
        val measurements = listOf(
            ThermalMeasurementPayload(
                temperatureC = 153.0,
                roi = roi,
            ),
            ThermalMeasurementPayload(
                temperatureC = 88.5,
                roi = mapOf("x" to 0.10, "y" to 0.75, "width" to 0.06, "height" to 0.06),
            ),
        )

        client.recordThermalHotspotEvent(
            taskId = "fire-DRONE-001",
            droneSn = "DRONE-001",
            sourceTs = 1780059017562L,
            temperatureC = 153.0,
            thermalMeasureRoi = roi,
            thermalMeasurements = measurements,
        )

        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals("DRONE-001", api.lastTaskEventBody?.droneSn)
        assertEquals("thermal", api.lastTaskEventBody?.analysisChannel)
        assertEquals("HIGH", api.lastTaskEventBody?.riskLevel)
        assertEquals(153.0, api.lastTaskEventBody?.thermalTemperature ?: -1.0, 1e-6)
        assertEquals(roi, api.lastTaskEventBody?.thermalMeasureRoi)
        assertEquals(measurements, api.lastTaskEventBody?.thermalMeasurements)
    }

    @Test
    fun recordThermalHotspotEvent_includesThermalImageUrlWhenAvailable() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.recordThermalHotspotEvent(
            taskId = "fire-DRONE-001",
            droneSn = "DRONE-001",
            sourceTs = 1780059017562L,
            temperatureC = 153.0,
            thermalMeasureRoi = mapOf("x" to 0.42, "y" to 0.46, "width" to 0.08, "height" to 0.08),
            thermalImageUrl = "http://ai/snapshots/fire-DRONE-001-1780059017562-annotated.jpg",
        )

        assertEquals(
            "http://ai/snapshots/fire-DRONE-001-1780059017562-annotated.jpg",
            api.lastTaskEventBody?.thermalImageUrl,
        )
    }

    @Test
    fun recordVisibleConfirmationStatus_postsVisibleStatusToTaskEndpoint() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.recordVisibleConfirmationStatus(
            taskId = "fire-DRONE-001",
            droneSn = "DRONE-001",
            sourceTs = 1780059018562L,
            reviewStatus = "VISIBLE_CAPTURE_FAILED",
            thermalSourceEventId = "fire-DRONE-001-1780059017562",
            thermalImageUrl = "http://ai/snapshots/thermal.jpg",
        )

        assertEquals("fire-DRONE-001", api.lastTaskEventTaskId)
        assertEquals("DRONE-001", api.lastTaskEventBody?.droneSn)
        assertEquals("visible", api.lastTaskEventBody?.analysisChannel)
        assertEquals("VISIBLE_CAPTURE_FAILED", api.lastTaskEventBody?.reviewStatus)
        assertEquals("fire-DRONE-001-1780059017562", api.lastTaskEventBody?.thermalSourceEventId)
        assertEquals("http://ai/snapshots/thermal.jpg", api.lastTaskEventBody?.thermalImageUrl)
    }

    @Test
    fun pollMsdkCommand_returnsCommandAndParamsFromMsdkApi() = runTest {
        val api = RecordingDualStreamApi().apply {
            nextMsdkCommand = AgentApiEnvelope(
                data = MsdkCommandResponse(
                    commandId = "msdk-1",
                    aircraftSn = "AIRCRAFT-003",
                    command = "fly_to_point",
                    params = mapOf("latitude" to 34.1, "longitude" to 108.1),
                    status = "PENDING",
                ),
            )
        }
        val client = AgentBackendClient(api = api)

        val command = client.pollMsdkCommand("AIRCRAFT-003")

        assertEquals("AIRCRAFT-003", api.lastMsdkPollAircraftSn)
        assertEquals("msdk-1", command?.commandId)
        assertEquals("fly_to_point", command?.command)
        assertEquals(34.1, command?.params?.get("latitude"))
    }

    @Test
    fun ackMsdkCommand_postsUppercaseStatusToMsdkAckApi() = runTest {
        val api = RecordingDualStreamApi()
        val client = AgentBackendClient(api = api)

        client.ackMsdkCommand(
            aircraftSn = "AIRCRAFT-004",
            commandId = "msdk-2",
            status = "APPLIED",
            message = "focus-visible applied",
        )

        assertEquals("AIRCRAFT-004", api.lastMsdkAckAircraftSn)
        assertEquals("msdk-2", api.lastMsdkAckBody?.commandId)
        assertEquals("APPLIED", api.lastMsdkAckBody?.status)
        assertEquals("focus-visible applied", api.lastMsdkAckBody?.message)
    }

    private class RecordingDualStreamApi : DualStreamApi {
        var lastAgentFireBody: String? = null
        override suspend fun reportAgentFire(body: RequestBody): Response<okhttp3.ResponseBody> =
            Response.success(okhttp3.ResponseBody.create(null,
                """{"eventId":"event-1","acceptedSequence":1,"eventPersisted":true,"notificationQueued":false}"""))
                .also { lastAgentFireBody = Buffer().also { buffer -> body.writeTo(buffer) }.readUtf8() }
        var lastHeartbeatDroneSn: String? = null
        var lastHeartbeatBody: AgentHeartbeatRequest? = null
        var lastStatusDroneSn: String? = null
        var lastStatusBody: AgentStatusRequest? = null
        var lastCapabilityDroneSn: String? = null
        var lastCapabilityBody: CapabilityReportRequest? = null
        var nextCommand: AgentApiEnvelope<AgentCommandResponse>? = null
        var lastAckDroneSn: String? = null
        var lastAckBody: AgentCommandAckRequest? = null
        var lastTaskEventTaskId: String? = null
        var lastTaskEventBody: DualStreamEventRequest? = null
        var lastMsdkDeviceState: MsdkDeviceStateRequest? = null
        var nextMsdkCommand: AgentApiEnvelope<MsdkCommandResponse>? = null
        var lastMsdkPollAircraftSn: String? = null
        var lastMsdkAckAircraftSn: String? = null
        var lastMsdkAckBody: MsdkCommandAckRequest? = null

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

        override suspend fun recordTaskEvent(
            taskId: String,
            body: DualStreamEventRequest,
        ) {
            lastTaskEventTaskId = taskId
            lastTaskEventBody = body
        }

        override suspend fun reportMsdkDeviceState(body: MsdkDeviceStateRequest) {
            lastMsdkDeviceState = body
        }

        override suspend fun pollMsdkCommand(aircraftSn: String): AgentApiEnvelope<MsdkCommandResponse>? {
            lastMsdkPollAircraftSn = aircraftSn
            return nextMsdkCommand
        }

        override suspend fun ackMsdkCommand(
            aircraftSn: String,
            body: MsdkCommandAckRequest,
        ) {
            lastMsdkAckAircraftSn = aircraftSn
            lastMsdkAckBody = body
        }
    }
}
