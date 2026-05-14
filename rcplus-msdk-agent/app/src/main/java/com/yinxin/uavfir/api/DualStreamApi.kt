package com.yinxin.uavfir.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface DualStreamApi {
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
}
