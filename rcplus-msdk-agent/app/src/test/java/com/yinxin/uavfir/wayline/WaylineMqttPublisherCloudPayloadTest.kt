package com.yinxin.uavfir.wayline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaylineMqttPublisherCloudPayloadTest {
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    @Test
    fun buildCloudOsdEnvelopeMatchesCloudSdkTopicShape() {
        val envelope = WaylineMqttPublisher.buildCloudOsdEnvelope(
            gatewaySn = "RC-GW-001",
            timestamp = 1234L,
            bid = "bid-001",
            tid = "tid-001",
            data = mapOf(
                "latitude" to 34.123,
                "longitude" to 108.456,
                "height" to 42.0,
                "mode_code" to null,
                "battery" to mapOf("capacity_percent" to 88),
            ),
        )
        val json = gson.toJson(envelope)

        assertTrue(json.contains("\"bid\":\"bid-001\""))
        assertTrue(json.contains("\"tid\":\"tid-001\""))
        assertTrue(json.contains("\"timestamp\":1234"))
        assertTrue(json.contains("\"gateway\":\"RC-GW-001\""))
        assertTrue(json.contains("\"latitude\":34.123"))
        assertTrue(json.contains("\"battery\":{\"capacity_percent\":88}"))
        assertTrue("mode_code null must remain explicit to avoid ordinal leakage", json.contains("\"mode_code\":null"))
    }

    @Test
    fun buildCloudEventEnvelopeMatchesCloudSdkEventShape() {
        val envelope = WaylineMqttPublisher.buildCloudEventEnvelope(
            gatewaySn = "RC-GW-001",
            method = "hms",
            timestamp = 5678L,
            bid = "bid-002",
            tid = "tid-002",
            data = mapOf("list" to emptyList<Map<String, Any?>>()),
        )

        assertEquals("bid-002", envelope["bid"])
        assertEquals("tid-002", envelope["tid"])
        assertEquals(5678L, envelope["timestamp"])
        assertEquals("RC-GW-001", envelope["gateway"])
        assertEquals("hms", envelope["method"])
        assertEquals(0, envelope["need_reply"])
        assertEquals(mapOf("list" to emptyList<Map<String, Any?>>()), envelope["data"])
    }
}
