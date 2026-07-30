package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleConfirmationPolicyTest {
    @Test
    fun ownsEveryConfirmationThresholdAndVersion() {
        val policy = VisibleConfirmationPolicy(
            maxCenterDistance = 0.08,
            fireConfidence = 0.70f,
            smokeConfidence = 0.65f,
            nmsIou = 0.45f,
        )

        assertEquals("agent-visible-v1", policy.policyVersion)
        assertEquals(2, policy.requiredFreshFrames)
        assertEquals(300L, policy.maxFrameAgeMs)
        assertEquals(180, policy.fireColorRedMin)
        assertEquals(80, policy.fireColorGreenMin)
        assertEquals(120, policy.fireColorBlueMax)
        assertEquals(5, policy.fireColorMinPixels)
        assertTrue(policy.isDefaultOff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsConfirmationCountsOtherThanCanonicalTwo() {
        VisibleConfirmationPolicy(
            requiredFreshFrames = 3,
            maxCenterDistance = 0.08,
            fireConfidence = 0.70f,
            smokeConfidence = 0.65f,
            nmsIou = 0.45f,
        )
    }
}
