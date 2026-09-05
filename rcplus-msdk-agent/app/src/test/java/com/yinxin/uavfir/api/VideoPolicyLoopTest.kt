package com.yinxin.uavfir.api

import com.yinxin.uavfir.stream.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPolicyLoopTest {
    @Test fun networkHangCannotBlockIndependentLowQualityWatchdog() = runTest {
        val state = VideoPolicyState { testScheduler.currentTime }
        state.selectAircraft("A")
        var calls = 0
        var observed = VideoProfile.LOW
        val port = object : VideoPolicyPort {
            override suspend fun enforcePolicy() { observed = state.desiredProfile() }
            override fun videoReport(instanceId: String) = VideoPolicyReport(instanceId = instanceId, streaming = true)
        }
        val loop = VideoPolicyLoop(this, state, port, VideoPolicyRequester { _, _ ->
            calls++
            if (calls > 1) CompletableDeferred<Unit>().await()
            VideoPolicyDecision(1, "A", "instance", "HIGH", 4_000_000, 15_000, "lease", "high-granted")
        }, instanceId = "instance")
        loop.start(); runCurrent()
        assertEquals(VideoProfile.HIGH, observed)
        advanceTimeBy(16_000); runCurrent()
        assertEquals(VideoProfile.LOW, observed)
        loop.stop(); runCurrent()
    }

    @Test fun wireContractUsesSnakeCaseForBothDirections() {
        val gson = backendGson()
        val report = gson.toJson(VideoPolicyReport(instanceId = "instance", configuredBitrateBps = 500_000))
        assertTrue(report.contains("\"instance_id\""))
        assertTrue(report.contains("\"configured_bitrate_bps\":500000"))
        val result = gson.fromJson("""{"protocol_version":1,"drone_sn":"A","instance_id":"instance","profile":"HIGH","bitrate_bps":4000000,"valid_for_ms":15000,"lease_id":"one"}""", VideoPolicyDecision::class.java)
        assertEquals(15_000, result.validForMs)
        assertEquals(4_000_000, result.bitrateBps)
    }
}
