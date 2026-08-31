package com.yinxin.uavfir.sdk

class DjiSdkGatewayImpl(
    private val runtimeAdapter: DjiRuntimeAdapter = defaultRuntimeAdapter(),
) : DjiSdkGateway {
    override suspend fun initialize(): Boolean = runtimeAdapter.initialize()

    override suspend fun isAircraftConnected(): Boolean = runtimeAdapter.isAircraftConnected()

    override suspend fun loadCapability(): CameraCapability = runtimeAdapter.loadCapability()

    override suspend fun loadAircraftName(): String? = runtimeAdapter.loadAircraftName()

    override suspend fun loadAircraftModel(): String? = runtimeAdapter.loadAircraftModel()

    override suspend fun loadFlightLimit(): DjiFlightLimit = runtimeAdapter.loadFlightLimit()

    override suspend fun loadDeviceIdentity(): DjiDeviceIdentity? = runtimeAdapter.loadDeviceIdentity()
}

private class StubDjiRuntimeAdapter : DjiRuntimeAdapter {
    override suspend fun initialize(): Boolean = true

    override suspend fun isAircraftConnected(): Boolean = false

    override suspend fun loadCapability(): CameraCapability = CameraCapability(
        visibleSupported = false,
        thermalSupported = false,
    )

    override suspend fun loadAircraftName(): String? = null

    override suspend fun loadAircraftModel(): String? = null

    override suspend fun loadFlightLimit(): DjiFlightLimit = DjiFlightLimit()

    override suspend fun loadDeviceIdentity(): DjiDeviceIdentity? = null
}

private fun defaultRuntimeAdapter(): DjiRuntimeAdapter {
    val appContext = com.yinxin.uavfir.AppContextHolder.get()
    return if (appContext != null) {
        DjiMsdkRuntimeAdapter(appContext)
    } else {
        StubDjiRuntimeAdapter()
    }
}
