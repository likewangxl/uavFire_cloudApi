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
        )

        val json = backendGson().toJson(payload)
        val node = JsonParser.parseString(json).asJsonObject

        assertTrue(node.has("drone_sn"))
        assertTrue(node.has("connection_state"))
        assertTrue(node.has("session_state"))
        assertFalse(node.has("droneSn"))
        assertFalse(node.has("connectionState"))
        assertFalse(node.has("sessionState"))
    }
}
