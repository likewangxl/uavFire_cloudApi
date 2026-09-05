package com.yinxin.uavfir.stream

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VideoEncodingGuardTest {
    @Test fun sourceProfilesChangeWithoutRestartAndUnchangedLeaseDoesNotReconfigure() = runTest {
        val applied = mutableListOf<VideoProfile>()
        var stops = 0
        val encoding = VideoEncodingGuard({ applied += it }, { stops++ }, { 0 })
        encoding.configureForStart(VideoProfile.LOW)
        encoding.enforce(VideoProfile.HIGH)
        encoding.enforce(VideoProfile.HIGH)
        encoding.enforce(VideoProfile.LOW)
        assertEquals(listOf(VideoProfile.LOW, VideoProfile.HIGH, VideoProfile.LOW), applied)
        assertEquals(0, stops)
        assertEquals(VideoProfile.LOW, encoding.appliedProfile)
    }

    @Test fun failedHighFallsBackLowAndBacksOffInsteadOfHammeringEncoder() = runTest {
        var now = 0L
        var highAttempts = 0
        val encoding = VideoEncodingGuard({ if (it == VideoProfile.HIGH) { highAttempts++; error("unsupported") } }, {}, { now })
        encoding.configureForStart(VideoProfile.LOW)
        encoding.enforce(VideoProfile.HIGH)
        repeat(20) { encoding.enforce(VideoProfile.HIGH) }
        assertEquals(1, highAttempts)
        assertEquals(VideoProfile.LOW, encoding.appliedProfile)
        assertNotNull(encoding.error(true))
        now = 10_001
        encoding.enforce(VideoProfile.HIGH)
        assertEquals(2, highAttempts)
    }

    @Test fun failedLowFallbackStopsOnlyPublishingAndNeverClaimsApplied() = runTest {
        var broken = false
        var stopped = false
        val encoding = VideoEncodingGuard({ if (broken) error("encoder-unavailable") }, { stopped = true }, { 0 })
        encoding.configureForStart(VideoProfile.HIGH)
        broken = true
        encoding.enforce(VideoProfile.LOW)
        assertTrue(stopped)
        assertNull(encoding.appliedProfile)
        assertNotNull(encoding.error(false))
    }

    @Test fun restartDuringFaultUsesLowEvenIfHighLeaseStillExists() {
        val applied = mutableListOf<VideoProfile>()
        val encoding = VideoEncodingGuard({ applied += it }, {}, { 0 })
        encoding.reportError("network-error")
        encoding.configureForStart(VideoProfile.HIGH)
        assertEquals(listOf(VideoProfile.LOW), applied)
    }
}
