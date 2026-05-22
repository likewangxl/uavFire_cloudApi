package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.CameraCapability
import com.yinxin.uavfir.session.AgentConnectionState
import com.yinxin.uavfir.session.DualStreamCommandExecutor
import com.yinxin.uavfir.session.DualStreamSessionState

class AgentReporter(
    private val client: AgentBackendClient,
) {
    suspend fun reportHeartbeat(
        droneSn: String,
        connectionState: AgentConnectionState,
        sessionState: DualStreamSessionState,
    ) {
        client.sendHeartbeat(droneSn, connectionState, sessionState)
    }

    suspend fun reportStatus(
        droneSn: String,
        connectionState: AgentConnectionState,
        message: String,
        runtimeStatus: DualStreamCommandExecutor.RuntimeStatus = DualStreamCommandExecutor.RuntimeStatus(
            sessionState = DualStreamSessionState.INIT,
        ),
    ) {
        client.sendStatus(droneSn, connectionState, message, runtimeStatus)
    }

    suspend fun reportCapability(
        droneSn: String,
        capability: CameraCapability,
    ) {
        client.sendCapability(droneSn, capability)
    }

    suspend fun reportMsdkDeviceState(
        request: MsdkDeviceStateRequest,
    ) {
        client.sendMsdkDeviceState(request)
    }
}
