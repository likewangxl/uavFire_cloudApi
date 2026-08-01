package com.yinxin.uavfir.api

import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBackendApiFactoryTest {
    @Test
    fun backendGson_serializesAgentPayloadsAsSnakeCase() {
        val payload = AgentHeartbeatRequest(
            droneSn = "RC_PLUS_LOCAL",
            connectionState = "CAPABILITY_READY",
            sessionState = "INIT",
            detectorIntent = "ARMED",
            detectorState = "BLOCKED",
            detectorHealth = "UNHEALTHY",
            detectorReason = "feature-disabled",
            detectorIntentVersion = 9L,
        )

        val json = backendGson().toJson(payload)
        val node = JsonParser.parseString(json).asJsonObject

        assertTrue(node.has("drone_sn"))
        assertTrue(node.has("connection_state"))
        assertTrue(node.has("session_state"))
        assertTrue(node.has("detector_intent"))
        assertTrue(node.has("detector_state"))
        assertTrue(node.has("detector_health"))
        assertTrue(node.has("detector_reason"))
        assertTrue(node.has("detector_intent_version"))
        assertFalse(node.has("droneSn"))
        assertFalse(node.has("connectionState"))
        assertFalse(node.has("sessionState"))
    }
}
