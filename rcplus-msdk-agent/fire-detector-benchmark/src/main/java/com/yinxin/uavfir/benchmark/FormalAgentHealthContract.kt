package com.yinxin.uavfir.benchmark

internal data class FormalAgentHealth(
    val buildId: String,
    val versionName: String,
    val versionCode: Long,
)

internal object FormalAgentHealthContract {
    fun parse(
        values: Map<String, Any?>,
        trust: FormalAgentTrust,
        nowElapsedRealtimeMillis: Long,
    ): FormalAgentHealth {
        check(values.keys == setOf(
            "schemaVersion",
            "sdkRegistered",
            "uxsdkReal",
            "msdkReady",
            "buildId",
            "versionName",
            "versionCode",
            "observedAtElapsedRealtimeMillis",
            "maxAgeMillis",
        )) { "Agent health response is incomplete" }
        check((values["schemaVersion"] as? Number)?.toInt() == 2) {
            "Unsupported Agent health schema"
        }
        check(values["sdkRegistered"] == true && values["uxsdkReal"] == true && values["msdkReady"] == true) {
            "Formal Agent has not completed real UXSDK/MSDK registration"
        }
        val buildId = values["buildId"] as? String ?: error("Agent health buildId is invalid")
        val versionName = values["versionName"] as? String ?: error("Agent health versionName is invalid")
        val versionCode = (values["versionCode"] as? Number)?.toLong()
            ?: error("Agent health versionCode is invalid")
        val observedAt = (values["observedAtElapsedRealtimeMillis"] as? Number)?.toLong()
            ?: error("Agent health observation time is invalid")
        val maxAge = (values["maxAgeMillis"] as? Number)?.toLong()
            ?: error("Agent health maximum age is invalid")
        check(
            maxAge in 1..MAX_ALLOWED_AGE_MILLIS &&
                observedAt in (nowElapsedRealtimeMillis - maxAge)..nowElapsedRealtimeMillis,
        ) {
            "Formal Agent SDK health observation is stale"
        }
        check(
            buildId == trust.buildId &&
                versionName == trust.versionName &&
                versionCode == trust.versionCode,
        ) { "Agent health response does not match the trusted release" }
        return FormalAgentHealth(buildId, versionName, versionCode)
    }

    private const val MAX_ALLOWED_AGE_MILLIS = 5_000L
}
