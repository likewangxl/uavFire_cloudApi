package com.yinxin.uavfir.sdk

class DjiSdkGatewayImpl(
    private val runtimeAdapter: DjiRuntimeAdapter = defaultRuntimeAdapter(),
) : DjiSdkGateway {
    override suspend fun initialize(): Boolean = runtimeAdapter.initialize()

    override suspend fun isAircraftConnected(): Boolean = runtimeAdapter.isAircraftConnected()

    override suspend fun loadCapability(): CameraCapability = runtimeAdapter.loadCapability()
}

private class StubDjiRuntimeAdapter : DjiRuntimeAdapter {
    override suspend fun initialize(): Boolean = true

    override suspend fun isAircraftConnected(): Boolean = false

    override suspend fun loadCapability(): CameraCapability = CameraCapability(
        visibleSupported = false,
        thermalSupported = false,
    )
}

private fun defaultRuntimeAdapter(): DjiRuntimeAdapter {
    val appContext = com.yinxin.uavfir.AppContextHolder.get()
    return if (appContext != null) {
        DjiMsdkRuntimeAdapter(appContext)
    } else {
        StubDjiRuntimeAdapter()
    }
}
