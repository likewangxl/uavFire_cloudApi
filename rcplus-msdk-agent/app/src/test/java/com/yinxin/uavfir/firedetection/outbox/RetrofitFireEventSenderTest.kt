package com.yinxin.uavfir.firedetection.outbox

import com.yinxin.uavfir.api.DualStreamEventRequest
import com.yinxin.uavfir.api.backendGson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RetrofitFireEventSenderTest {
    @Test
    fun duplicateReceipt_isDeliveredAndUsesSnakeCaseWireFormat() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"code":0,"message":"success","data":{"event_id":"agent-123","status":"duplicate"}}""",
                ),
        )
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create(backendGson()))
                .build()
                .create(FireEventIngestApi::class.java)
            val payload = backendGson().toJson(
                DualStreamEventRequest(
                    eventId = "agent-123",
                    taskId = "fire-DRONE-1",
                    droneSn = "DRONE-1",
                    sourceTs = 123L,
                    thermalScore = 0.0,
                    fusionScore = 0.8,
                    riskLevel = "HIGH",
                    analysisChannel = "agent-visible-onnx",
                ),
            )

            val result = RetrofitFireEventSender(api).send(
                FireEventOutboxEntry(
                    eventId = "agent-123",
                    taskId = "fire-DRONE-1",
                    payloadJson = payload,
                    nextAttemptAt = 0L,
                    createdAt = 0L,
                ),
            )

            assertEquals(FireEventDeliveryResult.Delivered("duplicate"), result)
            val request = server.takeRequest()
            assertEquals(
                "/manage/api/v1/dual-stream/tasks/fire-DRONE-1/agent-fire-events",
                request.path,
            )
            assertTrue(request.body.readUtf8().contains("\"event_id\":\"agent-123\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun serverError_isRetryable() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create(backendGson()))
                .build()
                .create(FireEventIngestApi::class.java)
            val result = RetrofitFireEventSender(api).send(
                FireEventOutboxEntry(
                    eventId = "agent-503",
                    taskId = "fire-DRONE-1",
                    payloadJson = """{"event_id":"agent-503","drone_sn":"DRONE-1","source_ts":503,"thermal_score":0.0,"fusion_score":0.8,"risk_level":"HIGH"}""",
                    nextAttemptAt = 0L,
                    createdAt = 0L,
                ),
            )

            assertTrue(result is FireEventDeliveryResult.Retryable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun endpointNotDeployedYet_isRetryable() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create(backendGson()))
                .build()
                .create(FireEventIngestApi::class.java)
            val result = RetrofitFireEventSender(api).send(
                FireEventOutboxEntry(
                    eventId = "agent-404",
                    taskId = "fire-DRONE-1",
                    payloadJson = """{"event_id":"agent-404","drone_sn":"DRONE-1","source_ts":404,"thermal_score":0.0,"fusion_score":0.8,"risk_level":"HIGH"}""",
                    nextAttemptAt = 0L,
                    createdAt = 0L,
                ),
            )

            assertTrue(result is FireEventDeliveryResult.Retryable)
        } finally {
            server.shutdown()
        }
    }
}
