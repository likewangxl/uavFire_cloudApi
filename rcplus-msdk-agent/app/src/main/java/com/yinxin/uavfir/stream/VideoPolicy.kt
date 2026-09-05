package com.yinxin.uavfir.stream

enum class VideoProfile(val bitrateBps: Int) {
    LOW(500_000), HIGH(4_000_000),
}

data class VideoPolicyDecision(
    val protocolVersion: Int = 0,
    val droneSn: String = "",
    val instanceId: String = "",
    val profile: String = "",
    val bitrateBps: Int = 0,
    val validForMs: Long = 0,
    val leaseId: String = "",
    val reason: String = "",
)

data class VideoPolicyRequestContext(val droneSn: String, val generation: Long, val startedAtMs: Long)

/** Local monotonic deadlines; a delayed HTTP response never starts a fresh full-length lease. */
class VideoPolicyState(private val clockMs: () -> Long) {
    private var droneSn: String? = null
    private var generation = 0L
    private var highUntilMs = Long.MIN_VALUE
    private var lastRequestAtMs = Long.MIN_VALUE

    @Synchronized fun selectAircraft(sn: String) {
        if (droneSn != sn) {
            droneSn = sn
            generation++
            highUntilMs = Long.MIN_VALUE
            lastRequestAtMs = Long.MIN_VALUE
        }
    }

    @Synchronized fun beginRequest(): VideoPolicyRequestContext? = droneSn?.let {
        VideoPolicyRequestContext(it, generation, clockMs())
    }

    @Synchronized fun accept(context: VideoPolicyRequestContext, instanceId: String, decision: VideoPolicyDecision) {
        if (context.droneSn != droneSn || context.generation != generation || context.startedAtMs < lastRequestAtMs) return
        lastRequestAtMs = context.startedAtMs
        highUntilMs = Long.MIN_VALUE
        if (decision.protocolVersion != 1 || decision.droneSn != droneSn || decision.instanceId != instanceId) return
        if (decision.profile == VideoProfile.HIGH.name && decision.bitrateBps == VideoProfile.HIGH.bitrateBps
            && decision.validForMs in 1..MAX_LEASE_MS && decision.leaseId.isNotBlank()
        ) {
            highUntilMs = context.startedAtMs + decision.validForMs
        }
    }

    @Synchronized fun revoke(context: VideoPolicyRequestContext) {
        if (context.droneSn == droneSn && context.generation == generation && context.startedAtMs >= lastRequestAtMs) {
            lastRequestAtMs = context.startedAtMs
            highUntilMs = Long.MIN_VALUE
        }
    }

    @Synchronized fun desiredProfile(): VideoProfile =
        if (clockMs() < highUntilMs) VideoProfile.HIGH else VideoProfile.LOW

    companion object { const val MAX_LEASE_MS = 15_000L }
}

data class VideoPolicyReport(
    val protocolVersion: Int = 1,
    val instanceId: String,
    val appliedProfile: String? = null,
    val streaming: Boolean = false,
    val configuredBitrateBps: Int? = null,
    val sdkVbps: Int? = null,
    val sdkFps: Int? = null,
    val sdkWidth: Int? = null,
    val sdkHeight: Int? = null,
    val sdkSampleAgeMs: Long? = null,
    val error: String? = null,
)

interface VideoPolicyPort {
    suspend fun enforcePolicy()
    fun videoReport(instanceId: String): VideoPolicyReport
}

/** Serial caller owns the SDK mutex. This layer also runs in JVM tests without native DJI classes. */
class VideoEncodingGuard(
    private val apply: (VideoProfile) -> Unit,
    private val stopPublishing: suspend () -> Unit,
    private val clockMs: () -> Long,
) {
    @Volatile var appliedProfile: VideoProfile? = null
        private set
    @Volatile private var lastError: String? = null
    @Volatile private var faultUntilMs = Long.MIN_VALUE

    fun reportError(message: String) {
        lastError = message.take(256)
        faultUntilMs = clockMs() + 10_000
    }

    fun error(streaming: Boolean): String? = lastError?.takeIf { clockMs() < faultUntilMs || !streaming }

    fun configureForStart(requested: VideoProfile) {
        val profile = if (clockMs() < faultUntilMs) VideoProfile.LOW else requested
        appliedProfile = null
        try {
            apply(profile)
            appliedProfile = profile
            lastError = null
        } catch (error: Exception) {
            reportError("profile-start:${error.message}")
            throw error
        }
    }

    suspend fun enforce(requested: VideoProfile) {
        val desired = if (clockMs() < faultUntilMs) VideoProfile.LOW else requested
        if (appliedProfile == desired) return
        try {
            apply(desired)
            appliedProfile = desired
            if (clockMs() >= faultUntilMs) lastError = null
        } catch (error: Exception) {
            reportError("profile-apply:${error.message}")
            appliedProfile = null
            try {
                apply(VideoProfile.LOW)
                appliedProfile = VideoProfile.LOW
            } catch (fallback: Exception) {
                reportError("profile-fallback:${fallback.message}")
                stopPublishing()
            }
        }
    }
}
