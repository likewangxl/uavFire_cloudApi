package com.yinxin.uavfir.sdk

import android.content.Context

class DjiMsdkRuntimeAdapter(
    private val sdkClient: MsdkSdkClient,
    private val keyValueClient: MsdkKeyValueClient,
) : DjiRuntimeAdapter {
    constructor(context: Context) : this(
        sdkClient = RealMsdkSdkClient(context),
        keyValueClient = RealMsdkKeyValueClient(),
    )

    override suspend fun initialize(): Boolean {
        if (!sdkClient.initialize()) {
            AgentSdkHealthState.tracker.markMsdkUnavailable()
            return false
        }
        AgentSdkHealthState.tracker.markMsdkInitialized()
        val registered = sdkClient.registerApp()
        if (registered) {
            AgentSdkHealthState.tracker.markSdkRegistered()
        } else {
            AgentSdkHealthState.tracker.markMsdkUnavailable()
        }
        return registered
    }

    override suspend fun isAircraftConnected(): Boolean = keyValueClient.isAircraftConnected()

    override suspend fun loadCapability(): CameraCapability = keyValueClient.loadCapability()

    override suspend fun loadAircraftModel(): String? = keyValueClient.loadAircraftModel()

    override suspend fun loadFlightLimit(): DjiFlightLimit = keyValueClient.loadFlightLimit()

    override suspend fun loadDeviceIdentity(): DjiDeviceIdentity? = keyValueClient.loadDeviceIdentity()
}
