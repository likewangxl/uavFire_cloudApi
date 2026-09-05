package com.yinxin.uavfir.stream

import org.junit.Assert.*
import org.junit.Test

class VideoPolicyStateTest {
    private var now = 100L
    private val policy = VideoPolicyState { now }
    private fun high(sn: String = "A", duration: Long = 15_000) =
        VideoPolicyDecision(1, sn, "instance", "HIGH", 4_000_000, duration, "lease", "high-granted")

    @Test fun startsLowAndExpiresUsingRequestStartInsteadOfResponseTime() {
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
        policy.selectAircraft("A")
        val req = policy.beginRequest()!!
        now += 10_000
        policy.accept(req, "instance", high())
        assertEquals(VideoProfile.HIGH, policy.desiredProfile())
        now += 5_001
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
    }

    @Test fun lateMalformedAndWrongIdentityResponsesCannotRaiseQuality() {
        policy.selectAircraft("A")
        val req = policy.beginRequest()!!
        policy.accept(req, "instance", high(duration = 60_000))
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
        policy.accept(req, "instance", high("B"))
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
        now += 16_000
        policy.accept(req, "instance", high())
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
    }

    @Test fun identityRoundTripAndOutOfOrderResponsesAreRejected() {
        policy.selectAircraft("A")
        val old = policy.beginRequest()!!
        policy.selectAircraft("B"); policy.selectAircraft("A")
        policy.accept(old, "instance", high())
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
        val first = policy.beginRequest()!!
        now++
        val newer = policy.beginRequest()!!
        policy.revoke(newer)
        policy.accept(first, "instance", high())
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
    }

    @Test fun renewalAndExplicitLowWorkWithoutDependingOnWallClock() {
        policy.selectAircraft("A")
        policy.accept(policy.beginRequest()!!, "instance", high())
        now += 14_000
        policy.accept(policy.beginRequest()!!, "instance", high())
        now += 2_000
        assertEquals(VideoProfile.HIGH, policy.desiredProfile())
        policy.accept(policy.beginRequest()!!, "instance", high().copy(profile = "LOW", bitrateBps = 500_000))
        assertEquals(VideoProfile.LOW, policy.desiredProfile())
    }
}
