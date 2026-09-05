package com.yinxin.uavfir.api

import com.yinxin.uavfir.firedetection.outbox.FireEventAuthProvider
import com.yinxin.uavfir.stream.VideoPolicyDecision
import com.yinxin.uavfir.stream.VideoPolicyReport
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID

interface VideoPolicyApi {
    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/video-policy")
    suspend fun policy(
        @Header("x-agent-token") token: String,
        @Header("x-agent-timestamp") timestamp: String,
        @Header("x-agent-nonce") nonce: String,
        @Path("droneSn") droneSn: String,
        @Body report: VideoPolicyReport,
    ): AgentApiEnvelope<VideoPolicyDecision>
}

fun interface VideoPolicyRequester {
    suspend fun request(droneSn: String, report: VideoPolicyReport): VideoPolicyDecision
}

class AuthenticatedVideoPolicyRequester(
    private val api: VideoPolicyApi,
    private val auth: FireEventAuthProvider,
) : VideoPolicyRequester {
    override suspend fun request(droneSn: String, report: VideoPolicyReport): VideoPolicyDecision {
        try {
            val token = auth.token(droneSn)
            return api.policy(token, System.currentTimeMillis().toString(), UUID.randomUUID().toString(), droneSn, report).data
                ?: throw IllegalStateException("missing-video-policy")
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) auth.invalidate(droneSn)
            throw e
        }
    }
}
