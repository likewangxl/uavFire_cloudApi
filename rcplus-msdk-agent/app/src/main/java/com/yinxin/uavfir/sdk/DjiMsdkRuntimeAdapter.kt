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
            return false
        }
        return sdkClient.registerApp()
    }

    override suspend fun isAircraftConnected(): Boolean = keyValueClient.isAircraftConnected()

    override suspend fun loadCapability(): CameraCapability = keyValueClient.loadCapability()
}
