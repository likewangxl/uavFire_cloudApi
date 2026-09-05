package com.yinxin.uavfir

class TrialExpirationPolicy(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun isExpired(): Boolean = nowMs() >= BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS

    fun remainingMs(): Long = (BuildConfig.TRIAL_EXPIRES_AT_EPOCH_MS - nowMs()).coerceAtLeast(0L)

    companion object {
        const val EXPIRES_AT_DISPLAY = "2026-10-01 00:00:00 Asia/Shanghai"
    }
}
