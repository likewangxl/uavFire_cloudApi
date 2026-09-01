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
    fun evidenceIsUploadedBeforeAuthenticatedEventAndDuplicateCompletesDelivery() = runTest {
        val server = MockWebServer()
        server.enqueue(jsonResponse(
            """{"code":0,"message":"success","data":{"event_id":"agent-123","status":"stored","visible_image_url":"/evidence/agent-123.jpg","evidence_sha256":"${"b".repeat(64)}","evidence_captured_at":123}}""",
        ))
        server.enqueue(jsonResponse(
            """{"code":0,"message":"success","data":{"event_id":"agent-123","status":"duplicate"}}""",
        ))
        server.start()
        try {
            val sender = sender(server)
            val original = entry("agent-123")
            val uploaded = sender.send(original)
            assertEquals(
                FireEventDeliveryResult.EvidenceUploaded("/evidence/agent-123.jpg"),
                uploaded,
            )
            val uploadRequest = server.takeRequest()
            assertEquals(
                "/manage/api/v1/dual-stream/agents/DRONE-1/fire-evidence",
                uploadRequest.path,
            )
            assertEquals("jwt-DRONE-1", uploadRequest.getHeader("x-agent-token"))
            assertEquals("1000", uploadRequest.getHeader("x-agent-timestamp"))
            assertEquals("nonce-test", uploadRequest.getHeader("x-agent-nonce"))

            val result = sender.send(original.copy(
                evidenceJpeg = null,
                evidenceUrl = "/evidence/agent-123.jpg",
            ))
            assertEquals(FireEventDeliveryResult.Delivered("duplicate"), result)
            val eventRequest = server.takeRequest()
            assertEquals(
                "/manage/api/v1/dual-stream/tasks/fire-DRONE-1/agent-fire-events",
                eventRequest.path,
            )
            val body = eventRequest.body.readUtf8()
            assertTrue(body.contains("\"event_id\":\"agent-123\""))
            assertTrue(body.contains("\"visible_image_url\":\"/evidence/agent-123.jpg\""))
            assertTrue(body.contains("\"evidence_status\":\"UPLOADED\""))
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
            assertTrue(sender(server).send(entry("agent-503")) is FireEventDeliveryResult.Retryable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unauthorizedResponseInvalidatesCachedTokenAndRemainsRetryable() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val auth = FakeAuth()
        try {
            val result = sender(server, auth).send(entry("agent-401"))
            assertTrue(result is FireEventDeliveryResult.Retryable)
            assertEquals(listOf("DRONE-1"), auth.invalidated)
        } finally {
            server.shutdown()
        }
    }

    private fun sender(server: MockWebServer, auth: FakeAuth = FakeAuth()): RetrofitFireEventSender {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(backendGson()))
            .build()
            .create(FireEventIngestApi::class.java)
        return RetrofitFireEventSender(
            api = api,
            auth = auth,
            clock = { 1_000L },
            nonce = { "nonce-test" },
        )
    }

    private fun entry(eventId: String): FireEventOutboxEntry {
        val payload = backendGson().toJson(
            DualStreamEventRequest(
                eventId = eventId,
                taskId = "fire-DRONE-1",
                droneSn = "DRONE-1",
                sourceTs = 123L,
                thermalScore = 0.0,
                fusionScore = 0.8,
                riskLevel = "HIGH",
                analysisChannel = "agent-visible-onnx",
                modelSha256 = "a".repeat(64),
            ),
        )
        return FireEventOutboxEntry(
            eventId = eventId,
            taskId = "fire-DRONE-1",
            payloadJson = payload,
            evidenceJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
            evidenceSha256 = "b".repeat(64),
            evidenceCapturedAt = 123L,
            nextAttemptAt = 0L,
            createdAt = 0L,
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeAuth : FireEventAuthProvider {
        val invalidated = mutableListOf<String>()
        override suspend fun token(droneSn: String): String = "jwt-$droneSn"
        override fun invalidate(droneSn: String) {
            invalidated += droneSn
        }
    }
}
