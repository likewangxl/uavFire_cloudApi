package com.yinxin.uavfir.wayline

import com.yinxin.uavfir.api.AgentApiEnvelope
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface WaylineAgentApi {

    @POST("/wayline-agent/api/v1/auth/token")
    suspend fun issueToken(@Body body: WaylineAgentTokenRequest): AgentApiEnvelope<WaylineAgentTokenResponse>

    @GET("/wayline-agent/api/v1/agents/{droneSn}/command")
    suspend fun pollCommand(
        @Header("x-agent-token") token: String,
        @Path("droneSn") droneSn: String,
    ): AgentApiEnvelope<WaylineAgentCommand>?

    @POST("/wayline-agent/api/v1/agents/{droneSn}/command/ack")
    suspend fun ackCommand(
        @Header("x-agent-token") token: String,
        @Path("droneSn") droneSn: String,
        @Body body: WaylineAgentCommandAck,
    )
}
