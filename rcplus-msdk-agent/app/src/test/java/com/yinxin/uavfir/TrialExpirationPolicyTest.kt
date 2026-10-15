package com.yinxin.uavfir

import org.junit.Assert.assertEquals
import org.junit.Test

class TrialExpirationPolicyTest {
    @Test
    fun cutoffMatchesBeijingMidnightOnOctoberFirst() {
        assertEquals(1_790_784_000_000L, BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS)
        assertEquals("2026-10-01 00:00:00 Asia/Shanghai", TrialExpirationPolicy.EXPIRES_AT_DISPLAY)
    }

    @Test
    fun remainsUsableImmediatelyBeforeCutoff() {
        val policy = untilPolicy(BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS - 1L)

        assertEquals(false, policy.isExpired())
        assertEquals(1L, policy.remainingMs())
    }

    @Test
    fun expiresAtAndAfterCutoff() {
        assertEquals(true, untilPolicy(BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS).isExpired())
        assertEquals(true, untilPolicy(BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS + 1L).isExpired())
    }

    @Test
    fun debugOffModeDoesNotExpireAndReleaseOffModeFailsClosed() {
        val now = BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS + 1L
        assertEquals(false, policy(now, mode = "off", releaseBuild = false, allowUnlimited = false).isExpired())
        assertEquals(true, policy(now, mode = "off", releaseBuild = true, allowUnlimited = false).isExpired())
        assertEquals(false, policy(now, mode = "off", releaseBuild = true, allowUnlimited = true).isExpired())
    }

    private fun untilPolicy(now: Long) = policy(now, mode = "until", releaseBuild = false, allowUnlimited = false)

    private fun policy(now: Long, mode: String, releaseBuild: Boolean, allowUnlimited: Boolean) =
        TrialExpirationPolicy(
            nowMs = { now },
            mode = mode,
            expiresAtEpochMs = BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS,
            releaseBuild = releaseBuild,
            allowUnlimited = allowUnlimited,
        )
}
