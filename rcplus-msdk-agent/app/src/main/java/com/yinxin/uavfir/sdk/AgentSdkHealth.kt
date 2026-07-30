package com.yinxin.uavfir.sdk

internal data class AgentSdkHealthSnapshot(
    val schemaVersion: Int = 2,
    val sdkRegistered: Boolean,
    val uxsdkReal: Boolean,
    val msdkReady: Boolean,
    val buildId: String,
    val versionName: String,
    val versionCode: Long,
    val observedAtElapsedRealtimeMillis: Long,
    val maxAgeMillis: Long,
) {
    val healthy: Boolean get() = sdkRegistered && uxsdkReal && msdkReady
}

internal class AgentSdkHealthTracker(
    private val buildId: String,
    private val versionName: String,
    private val versionCode: Long,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile private var sdkRegistered = false
    @Volatile private var uxsdkReal = false
    @Volatile private var msdkReady = false

    fun markUxSdkInitialized(sourceSha256: String, runtimeClassPresent: Boolean) {
        uxsdkReal = sourceSha256.matches(Regex("[0-9a-f]{64}")) && runtimeClassPresent
    }

    fun markMsdkInitialized() {
        msdkReady = true
    }

    fun markSdkRegistered() {
        sdkRegistered = msdkReady
    }

    fun markMsdkUnavailable() {
        sdkRegistered = false
        msdkReady = false
    }

    fun snapshot(liveSdkRegistered: Boolean = sdkRegistered): AgentSdkHealthSnapshot {
        val currentlyReady = msdkReady && liveSdkRegistered
        return AgentSdkHealthSnapshot(
            sdkRegistered = sdkRegistered && liveSdkRegistered,
            uxsdkReal = uxsdkReal,
            msdkReady = currentlyReady,
            buildId = buildId,
            versionName = versionName,
            versionCode = versionCode,
            observedAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            maxAgeMillis = MAX_AGE_MILLIS,
        )
    }

    private companion object {
        const val MAX_AGE_MILLIS = 5_000L
    }
}
