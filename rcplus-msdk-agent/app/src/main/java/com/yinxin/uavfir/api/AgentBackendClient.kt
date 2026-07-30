package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionState
import java.util.Locale

class AgentBackendClient(
    private val api: DualStreamApi,
) {
    suspend fun sendHeartbeat(
        droneSn: String,
        connectionState: AgentConnectionState,
        sessionState: DualStreamSessionState,
    ) {
        debug("heartbeat request drone=$droneSn state=$connectionState session=$sessionState")
        api.heartbeat(droneSn, buildHeartbeatRequest(droneSn, connectionState, sessionState))
        debug("heartbeat response drone=$droneSn")
    }

    fun buildHeartbeatRequest(
        droneSn: String,
        connectionState: AgentConnectionState,
        sessionState: DualStreamSessionState,
    ): AgentHeartbeatRequest = AgentHeartbeatRequest(
        droneSn = droneSn,
        connectionState = connectionState.name,
        sessionState = sessionState.name,
    )

    suspend fun sendStatus(
        droneSn: String,
        connectionState: AgentConnectionState,
        message: String,
        runtimeStatus: DualStreamCommandExecutor.RuntimeStatus = DualStreamCommandExecutor.RuntimeStatus(
            sessionState = DualStreamSessionState.INIT,
        ),
    ) {
        debug("status request drone=$droneSn state=$connectionState")
        api.status(droneSn, buildStatusRequest(droneSn, connectionState, message, runtimeStatus))
        debug("status response drone=$droneSn")
    }

    fun buildStatusRequest(
        droneSn: String,
        connectionState: AgentConnectionState,
        message: String,
        runtimeStatus: DualStreamCommandExecutor.RuntimeStatus = DualStreamCommandExecutor.RuntimeStatus(
            sessionState = DualStreamSessionState.INIT,
        ),
    ): AgentStatusRequest = AgentStatusRequest(
        droneSn = droneSn,
        connectionState = connectionState.name,
        message = message,
        liveStatus = runtimeStatus.sessionState.name,
        currentMode = when {
            runtimeStatus.visibleState != null && runtimeStatus.thermalState != null -> "DUAL"
            runtimeStatus.visibleState != null -> "VISIBLE_ONLY"
            runtimeStatus.thermalState != null -> "THERMAL_ONLY"
            else -> "IDLE"
        },
        visibleState = runtimeStatus.visibleState?.toApiStreamState(),
        thermalState = runtimeStatus.thermalState?.toApiThermalState(runtimeStatus.failureReason),
        statusReason = runtimeStatus.failureReason,
        playbackStatus = runtimeStatus.playbackStatus ?: "awaiting-media-url",
        visiblePlayUrl = null,
        thermalPlayUrl = null,
        thermalCenterTemperatureC = runtimeStatus.thermalCenterTemperatureC,
    )

    suspend fun sendCapability(
        droneSn: String,
        capability: CameraCapability,
    ) {
        debug("capability request drone=$droneSn visible=${capability.visibleSupported} thermal=${capability.thermalSupported}")
        api.capability(droneSn, buildCapabilityReportRequest(droneSn, capability))
        debug("capability response drone=$droneSn")
    }

    fun buildCapabilityReportRequest(
        droneSn: String,
        capability: CameraCapability,
    ): CapabilityReportRequest = CapabilityReportRequest(
        droneSn = droneSn,
        visibleSupported = capability.visibleSupported,
        thermalSupported = capability.thermalSupported,
    )

    suspend fun sendMsdkDeviceState(
        request: MsdkDeviceStateRequest,
    ) {
        debug("msdk state request aircraft=${request.aircraftSn} state=${request.connectionState}")
        api.reportMsdkDeviceState(request)
        debug("msdk state response aircraft=${request.aircraftSn}")
    }

    fun buildMsdkDeviceStateRequest(
        aircraftSn: String,
        gatewaySn: String,
        online: Boolean,
        connectionState: String,
        deviceName: String? = null,
        model: String? = null,
        mode: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        height: Double? = null,
        elevation: Double? = null,
        homeDistance: Double? = null,
        horizontalSpeed: Double? = null,
        verticalSpeed: Double? = null,
        windSpeed: Double? = null,
        batteryPercent: Int? = null,
        gpsCount: Int? = null,
        rtkCount: Int? = null,
        positionFixed: Boolean? = null,
        visibleSupported: Boolean = false,
        thermalSupported: Boolean = false,
        updatedAt: Long = System.currentTimeMillis(),
    ): MsdkDeviceStateRequest = MsdkDeviceStateRequest(
        gatewaySn = gatewaySn,
        aircraftSn = aircraftSn,
        online = online,
        connectionState = connectionState,
        deviceName = deviceName,
        model = model,
        mode = mode,
        latitude = latitude,
        longitude = longitude,
        height = height,
        elevation = elevation,
        homeDistance = homeDistance,
        horizontalSpeed = horizontalSpeed,
        verticalSpeed = verticalSpeed,
        windSpeed = windSpeed,
        batteryPercent = batteryPercent,
        gpsCount = gpsCount,
        rtkCount = rtkCount,
        positionFixed = positionFixed,
        updatedAt = updatedAt,
        capabilities = mapOf(
            "takeoff" to false,
            "land" to false,
            "returnHome" to false,
            "cancelReturnHome" to false,
            "emergencyStop" to false,
            "hover" to false,
            "virtualStick" to false,
            "flyToPoint" to false,
            "gimbal" to false,
            "gimbalReset" to false,
            "gimbalRotate" to false,
            "camera" to false,
            "cameraPhoto" to false,
            "cameraRecord" to false,
            "cameraStreamSource" to false,
            "cameraZoom" to false,
            "nightScene" to false,
            "navigationLight" to false,
            "laserFillLight" to false,
            "visibleStream" to visibleSupported,
            "thermalFocus" to thermalSupported,
            "thermalSecondStream" to false,
        ),
    )

    suspend fun pollCommand(
        droneSn: String,
    ): AgentCommandResponse? {
        debug("poll request drone=$droneSn")
        val response = api.pollCommand(droneSn)?.data
        debug("poll response drone=$droneSn command=${response?.commandId ?: "none"}")
        return response
    }

    suspend fun latestVisibleRoi(
        taskId: String,
        afterSourceTs: Long,
    ): VisibleRoiSnapshotResponse? =
        api.latestVisibleRoi(taskId, afterSourceTs)?.data

    suspend fun ackCommand(
        droneSn: String,
        commandId: String,
        status: String,
        message: String? = null,
        taskId: String? = null,
        sourceTs: Long? = null,
        thermalTemperature: Double? = null,
        thermalMeasureRoi: Map<String, Double>? = null,
        eventId: String? = null,
        fireLat: Double? = null,
        fireLng: Double? = null,
        fireAlt: Double? = null,
        geoMethod: String? = null,
        geoQuality: String? = null,
        geoErrorRadiusM: Double? = null,
    ) {
        debug("ack request drone=$droneSn command=$commandId status=$status")
        api.ackCommand(
            droneSn = droneSn,
            body = AgentCommandAckRequest(
                commandId = commandId,
                status = status,
                message = message,
                taskId = taskId,
                sourceTs = sourceTs,
                thermalTemperature = thermalTemperature,
                thermalMeasureRoi = thermalMeasureRoi,
                eventId = eventId,
                fireLat = fireLat,
                fireLng = fireLng,
                fireAlt = fireAlt,
                geoMethod = geoMethod,
                geoQuality = geoQuality,
                geoErrorRadiusM = geoErrorRadiusM,
            ),
        )
        debug("ack response drone=$droneSn command=$commandId")
    }

    suspend fun recordThermalHotspotEvent(
        taskId: String,
        droneSn: String,
        sourceTs: Long,
        temperatureC: Double,
        thermalMeasureRoi: Map<String, Double>,
        thermalMeasurements: List<ThermalMeasurementPayload> = emptyList(),
        thermalImageUrl: String? = null,
        geoSnapshot: GeoSnapshot? = null,
        fireLat: Double? = null,
        fireLng: Double? = null,
        fireAlt: Double? = null,
        geoMethod: String? = null,
        geoErrorRadiusM: Double? = null,
    ) {
        debug("thermal hotspot event task=$taskId drone=$droneSn temp=$temperatureC roi=$thermalMeasureRoi")
        api.recordTaskEvent(
            taskId = taskId,
            body = buildThermalHotspotEventRequest(
                taskId = taskId,
                droneSn = droneSn,
                sourceTs = sourceTs,
                temperatureC = temperatureC,
                thermalMeasureRoi = thermalMeasureRoi,
                thermalMeasurements = thermalMeasurements,
                thermalImageUrl = thermalImageUrl,
                geoSnapshot = geoSnapshot,
                fireLat = fireLat,
                fireLng = fireLng,
                fireAlt = fireAlt,
                geoMethod = geoMethod,
                geoErrorRadiusM = geoErrorRadiusM,
            ),
        )
        debug("thermal hotspot event response task=$taskId drone=$droneSn")
    }

    fun buildThermalHotspotEventRequest(
        taskId: String,
        droneSn: String,
        sourceTs: Long,
        temperatureC: Double,
        thermalMeasureRoi: Map<String, Double>,
        thermalMeasurements: List<ThermalMeasurementPayload> = emptyList(),
        thermalImageUrl: String? = null,
        geoSnapshot: GeoSnapshot? = null,
        fireLat: Double? = null,
        fireLng: Double? = null,
        fireAlt: Double? = null,
        geoMethod: String? = null,
        geoErrorRadiusM: Double? = null,
    ): DualStreamEventRequest {
        val score = temperatureToThermalScore(temperatureC)
        return DualStreamEventRequest(
            taskId = taskId,
            droneSn = droneSn,
            sourceTs = sourceTs,
            thermalScore = score,
            fusionScore = score,
            riskLevel = temperatureToRiskLevel(temperatureC),
            thermalImageUrl = thermalImageUrl,
            thermalTemperature = temperatureC,
            thermalMeasureRoi = thermalMeasureRoi,
            thermalMeasurements = thermalMeasurements.ifEmpty {
                listOf(ThermalMeasurementPayload(temperatureC, thermalMeasureRoi))
            },
            geoSnapshot = geoSnapshot,
            fireLat = fireLat,
            fireLng = fireLng,
            fireAlt = fireAlt,
            geoMethod = geoMethod,
            geoErrorRadiusM = geoErrorRadiusM,
        )
    }

    suspend fun recordVisibleConfirmationStatus(
        taskId: String,
        droneSn: String,
        sourceTs: Long,
        reviewStatus: String,
        thermalSourceEventId: String,
        thermalImageUrl: String,
        visibleImageUrl: String? = null,
    ) {
        debug("visible confirmation status task=$taskId drone=$droneSn status=$reviewStatus")
        api.recordTaskEvent(
            taskId = taskId,
            body = DualStreamEventRequest(
                taskId = taskId,
                droneSn = droneSn,
                sourceTs = sourceTs,
                visibleScore = 0.0,
                thermalScore = 0.0,
                fusionScore = 0.0,
                riskLevel = "LOW",
                analysisChannel = "visible",
                reviewStatus = reviewStatus,
                visibleImageUrl = visibleImageUrl,
                thermalSourceEventId = thermalSourceEventId,
                thermalImageUrl = thermalImageUrl,
            ),
        )
        debug("visible confirmation status response task=$taskId drone=$droneSn status=$reviewStatus")
    }

    suspend fun pollMsdkCommand(
        aircraftSn: String,
    ): MsdkCommandResponse? {
        debug("msdk poll request aircraft=$aircraftSn")
        val response = api.pollMsdkCommand(aircraftSn)?.data
        debug("msdk poll response aircraft=$aircraftSn command=${response?.commandId ?: "none"}")
        return response
    }

    suspend fun ackMsdkCommand(
        aircraftSn: String,
        commandId: String,
        status: String,
        message: String? = null,
    ) {
        val normalizedStatus = status.uppercase(Locale.US)
        debug("msdk ack request aircraft=$aircraftSn command=$commandId status=$normalizedStatus")
        api.ackMsdkCommand(
            aircraftSn = aircraftSn,
            body = MsdkCommandAckRequest(
                commandId = commandId,
                status = normalizedStatus,
                message = message,
            ),
        )
        debug("msdk ack response aircraft=$aircraftSn command=$commandId")
    }

    private fun debug(message: String) {
        println("AgentBackendClient: $message")
    }

    private fun temperatureToThermalScore(temperatureC: Double): Double = when {
        temperatureC >= 80.0 -> 1.0
        temperatureC >= 60.0 -> 0.72
        temperatureC >= 45.0 -> 0.48
        else -> 0.0
    }

    private fun temperatureToRiskLevel(temperatureC: Double): String = when {
        temperatureC >= 80.0 -> "HIGH"
        temperatureC >= 45.0 -> "MEDIUM"
        else -> "LOW"
    }

    private fun com.yinxin.uavfir.stream.BoundStreamState.toApiStreamState(): String = when (this) {
        com.yinxin.uavfir.stream.BoundStreamState.BOUND -> "running"
        com.yinxin.uavfir.stream.BoundStreamState.IDLE -> "idle"
    }

    private fun com.yinxin.uavfir.stream.BoundStreamState.toApiThermalState(reason: String?): String = when {
        reason != null && this != com.yinxin.uavfir.stream.BoundStreamState.BOUND -> "degraded"
        else -> toApiStreamState()
    }
}
