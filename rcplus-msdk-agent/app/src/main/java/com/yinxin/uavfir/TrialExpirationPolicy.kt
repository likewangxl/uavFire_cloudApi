package com.yinxin.uavfir

class TrialExpirationPolicy(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val mode: String = BuildConfig.TRIAL_MODE,
    private val expiresAtEpochMs: Long = BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS,
    private val releaseBuild: Boolean = BuildConfig.TRIAL_RELEASE_BUILD,
    private val allowUnlimited: Boolean = BuildConfig.TRIAL_ALLOW_UNLIMITED,
) {
    fun isExpired(): Boolean {
        if (!mode.equals("until", ignoreCase = true)) {
            return releaseBuild && !allowUnlimited
        }
        return nowMs() >= expiresAtEpochMs
    }

    fun remainingMs(): Long {
        if (!mode.equals("until", ignoreCase = true)) {
            return if (isExpired()) 0L else Long.MAX_VALUE / 4
        }
        return (expiresAtEpochMs - nowMs()).coerceAtLeast(0L)
    }

    companion object {
        const val EXPIRES_AT_DISPLAY = "2026-10-01 00:00:00 Asia/Shanghai"
    }
}
