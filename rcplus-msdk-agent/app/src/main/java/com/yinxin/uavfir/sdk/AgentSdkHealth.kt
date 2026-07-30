package com.yinxin.uavfir.sdk

internal data class AgentSdkHealthSnapshot(
    val schemaVersion: Int = 1,
    val sdkRegistered: Boolean,
    val uxsdkReal: Boolean,
    val msdkReady: Boolean,
    val buildId: String,
    val versionName: String,
    val versionCode: Long,
) {
    val healthy: Boolean get() = sdkRegistered && uxsdkReal && msdkReady
}

internal class AgentSdkHealthTracker(
    private val buildId: String,
    private val versionName: String,
    private val versionCode: Long,
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

    fun snapshot() = AgentSdkHealthSnapshot(
        sdkRegistered = sdkRegistered,
        uxsdkReal = uxsdkReal,
        msdkReady = msdkReady,
        buildId = buildId,
        versionName = versionName,
        versionCode = versionCode,
    )
}
