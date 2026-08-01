package com.yinxin.uavfir.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface DualStreamApi {
    @POST("/manage/api/v1/fire-events/agent-report")
    suspend fun reportAgentFire(
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/heartbeat")
    suspend fun heartbeat(
        @Path("droneSn") droneSn: String,
        @Body body: AgentHeartbeatRequest,
    )

    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/status")
    suspend fun status(
        @Path("droneSn") droneSn: String,
        @Body body: AgentStatusRequest,
    )

    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/capability")
    suspend fun capability(
        @Path("droneSn") droneSn: String,
        @Body body: CapabilityReportRequest,
    )

    @GET("/manage/api/v1/dual-stream/agents/{droneSn}/command")
    suspend fun pollCommand(
        @Path("droneSn") droneSn: String,
    ): AgentApiEnvelope<AgentCommandResponse>?

    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/command/ack")
    suspend fun ackCommand(
        @Path("droneSn") droneSn: String,
        @Body body: AgentCommandAckRequest,
    )

    @POST("/manage/api/v1/dual-stream/tasks/{taskId}/events")
    suspend fun recordTaskEvent(
        @Path("taskId") taskId: String,
        @Body body: DualStreamEventRequest,
    )

    @POST("/manage/api/v1/msdk/devices/state")
    suspend fun reportMsdkDeviceState(
        @Body body: MsdkDeviceStateRequest,
    )

    @POST("/manage/api/v1/msdk/devices/{aircraftSn}/commands/poll")
    suspend fun pollMsdkCommand(
        @Path("aircraftSn") aircraftSn: String,
    ): AgentApiEnvelope<MsdkCommandResponse>?

    @POST("/manage/api/v1/msdk/devices/{aircraftSn}/commands/ack")
    suspend fun ackMsdkCommand(
        @Path("aircraftSn") aircraftSn: String,
        @Body body: MsdkCommandAckRequest,
    )
}
