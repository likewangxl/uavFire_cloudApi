package com.yinxin.uavfir.api

import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.store.OutboxRow
import com.yinxin.uavfir.firedetection.store.OutboxStatus
import com.yinxin.uavfir.firedetection.store.SendOutcome
import com.yinxin.uavfir.firedetection.store.sha256
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentFireReportTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: AgentFireReportTransport

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val api = AgentBackendApiFactory.create(AgentBackendConfig(server.url("/").toString()))
        transport = AgentFireReportTransport(api) { "trusted-agent-token" }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `posts exact durable JSON to staged path`() = runTest {
        server.enqueue(success())
        val row = row()

        assertEquals(SendOutcome.Acknowledged, transport.send(row))

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/manage/api/v1/fire-events/agent-report", request.path)
        assertEquals("trusted-agent-token", request.getHeader("x-agent-token"))
        assertEquals(row.payload, request.body.readUtf8())
    }

    @Test fun `exact duplicate is acknowledged idempotently`() = runTest {
        server.enqueue(success(duplicate = true))
        assertEquals(SendOutcome.ExactDuplicate, transport.send(row()))
    }

    @Test fun `conflict and permanent client errors quarantine`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        assertTrue(transport.send(row()) is SendOutcome.PayloadConflict)
        server.enqueue(MockResponse().setResponseCode(422))
        assertTrue(transport.send(row()) is SendOutcome.PayloadConflict)
    }

    @Test fun `retryable statuses remain transient`() = runTest {
        listOf(408, 425, 429, 503).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue("$code", transport.send(row()) is SendOutcome.TransientFailure)
        }
    }

    @Test fun `mismatched or incomplete success is never an ack`() = runTest {
        server.enqueue(success(eventId = "other"))
        assertTrue(transport.send(row()) is SendOutcome.PayloadConflict)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"eventId":"event-1","acceptedSequence":1,"eventPersisted":true}"""))
        assertTrue(transport.send(row()) is SendOutcome.PayloadConflict)
    }

    @Test fun `duplicate must be a present JSON boolean`() = runTest {
        listOf(
            """{"eventId":"event-1","acceptedSequence":1,"eventPersisted":true,"notificationQueued":false}""",
            """{"eventId":"event-1","acceptedSequence":1,"eventPersisted":true,"notificationQueued":false,"duplicate":null}""",
            """{"eventId":"event-1","acceptedSequence":1,"eventPersisted":true,"notificationQueued":false,"duplicate":"false"}""",
        ).forEach { body ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))
            assertTrue(transport.send(row()) is SendOutcome.PayloadConflict)
        }
    }

    @Test fun `malformed success is fail closed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("{"))
        assertTrue(transport.send(row()) is SendOutcome.PayloadConflict)
    }

    @Test fun `network failure is transient`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(transport.send(row()) is SendOutcome.TransientFailure)
    }

    @Test fun `corrupted durable payload is quarantined without network use`() = runTest {
        assertTrue(transport.send(row().copy(payloadSha256 = "0".repeat(64))) is SendOutcome.PayloadConflict)
        assertEquals(0, server.requestCount)
    }

    @Test fun `caller cancellation is preserved for bounded terminal observation`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var cancelled = false
        try {
            withTimeout(100) { transport.send(row()) }
        } catch (_: TimeoutCancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun success(eventId: String = "event-1", duplicate: Boolean = false) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
            """{"eventId":"$eventId","acceptedSequence":1,"eventPersisted":true,"notificationQueued":false,"duplicate":$duplicate}""",
        )

    private fun row(): OutboxRow {
        val payload = """{"agentId":"agent-1","confidence":0.91,"coordinatorGeneration":9,"detectionKind":"FIRE","droneSn":"drone-1","eventId":"event-1","eventTimestamp":100000,"inputSize":960,"locationStatus":"LASER_LOCATING","modelHash":"${"a".repeat(64)}","modelVersion":"visible-fire-wechat-best2-20260728","policyVersion":"agent-visible-v1","runtime":"NCNN","sequence":1,"sessionId":"session-1","sourceGeneration":7,"state":"VISUAL_CONFIRMED","taskId":"fire-drone-1","visibleRoi":{"height":0.2,"width":0.2,"x":0.4,"y":0.4}}"""
        return OutboxRow(
            eventId = "event-1", sessionId = "session-1", sequence = 1,
            eventTimestampWallMillis = 100_000, state = FireSessionState.VISUAL_CONFIRMED,
            payload = payload,
            payloadSha256 = sha256(payload), status = OutboxStatus.PENDING, attemptCount = 0,
            nextAttemptElapsedMillis = 0, leaseUntilElapsedMillis = null, lastError = null,
        )
    }
}
