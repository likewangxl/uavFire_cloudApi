package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionState

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

    suspend fun pollCommand(
        droneSn: String,
    ): AgentCommandResponse? {
        debug("poll request drone=$droneSn")
        val response = api.pollCommand(droneSn)?.data
        debug("poll response drone=$droneSn command=${response?.commandId ?: "none"}")
        return response
    }

    suspend fun ackCommand(
        droneSn: String,
        commandId: String,
        status: String,
        message: String? = null,
    ) {
        debug("ack request drone=$droneSn command=$commandId status=$status")
        api.ackCommand(
            droneSn = droneSn,
            body = AgentCommandAckRequest(
                commandId = commandId,
                status = status,
                message = message,
            ),
        )
        debug("ack response drone=$droneSn command=$commandId")
    }

    private fun debug(message: String) {
        println("AgentBackendClient: $message")
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
