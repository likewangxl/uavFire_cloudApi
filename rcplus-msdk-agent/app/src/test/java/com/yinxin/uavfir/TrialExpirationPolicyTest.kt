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
        val policy = TrialExpirationPolicy { BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS - 1L }

        assertEquals(false, policy.isExpired())
        assertEquals(1L, policy.remainingMs())
    }

    @Test
    fun expiresAtAndAfterCutoff() {
        assertEquals(
            true,
            TrialExpirationPolicy { BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS }.isExpired(),
        )
        assertEquals(
            true,
            TrialExpirationPolicy { BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS + 1L }.isExpired(),
        )
    }
}
