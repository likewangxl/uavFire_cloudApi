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
            nmsIou = VisibleDetectorContract.NMS_IOU_THRESHOLD,
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
            nmsIou = VisibleDetectorContract.NMS_IOU_THRESHOLD,
        )
    }

    @Test
    fun bindsNmsToPackagedDetectorManifestAndRejectsMismatch() {
        val manifest = VisibleFireModelManifestParser.parse(
            java.io.File("src/main/assets/fire-detection/model-manifest.json").readText(),
        )
        val policy = VisibleConfirmationPolicy(
            maxCenterDistance = 0.08,
            fireConfidence = 0.70f,
            smokeConfidence = 0.65f,
            nmsIou = manifest.iouThreshold,
        )

        assertEquals(manifest.iouThreshold, policy.nmsIou)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            policy.copy(nmsIou = manifest.iouThreshold - 0.01f)
        }
    }
}
