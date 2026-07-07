package com.yinxin.uavfir.stream

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class ThermalFrameProbeSourceTest {
    @Test
    fun onFrame_doesNotThrottleThermalSamplingWhenResidualVisibleFrameIsSkipped() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt"),
        ))
        val onFrameBody = source.substringAfter("private fun onFrame")
            .substringBefore("private fun maybeDetectHotspot")

        assertTrue(
            "thermal residual visible frames must be classified before updating lastThermalSavedAtMs",
            onFrameBody.indexOf("ThermalFrameClassifier.looksLikeThermalFrame") in 0 until
            onFrameBody.indexOf("lastThermalSavedAtMs = now"),
        )
    }

    @Test
    fun immediateVisibleSnapshotRequestBypassesRegularVisibleSampleThrottle() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt"),
        ))
        val onFrameBody = source.substringAfter("private fun onFrame")
            .substringBefore("private fun maybeDetectHotspot")

        assertTrue(
            "ThermalFrameProbe should expose a one-shot immediate visible snapshot request",
            source.contains("fun requestImmediateVisibleSnapshot"),
        )
        assertTrue(
            "visible frames should check one-shot immediate requests before returning on SAMPLE_INTERVAL_MS throttle",
            onFrameBody.indexOf("consumeImmediateVisibleSnapshotRequest(now)") in 0 until
                onFrameBody.indexOf("now - lastSavedAtMs < SAMPLE_INTERVAL_MS"),
        )
        assertTrue(
            "regular thermal/visible sample throttling should remain in place",
            onFrameBody.contains("now - lastSavedAtMs < SAMPLE_INTERVAL_MS"),
        )
    }

    @Test
    fun hotspotDetectionNotifiesInjectedCandidateListener() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt"),
        ))
        val detectBody = source.substringAfter("private fun maybeDetectHotspot")
            .substringBefore("private fun maybeLogStats")

        assertTrue(
            "ThermalFrameProbe should define a minimal hotspot candidate listener interface",
            source.contains("fun interface ThermalHotspotCandidateListener"),
        )
        assertTrue(
            "ThermalFrameProbe constructor should inject a default no-op candidate listener",
            source.contains("hotspotCandidateListener: ThermalHotspotCandidateListener = ThermalHotspotCandidateListener.NO_OP"),
        )
        assertTrue(
            "a detected frame hotspot should notify the injected candidate listener",
            detectBody.contains("hotspotCandidateListener.onThermalHotspotCandidate"),
        )
    }
}
