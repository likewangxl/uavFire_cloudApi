package com.yinxin.uavfir.sdk

interface DjiRuntimeAdapter {
    suspend fun initialize(): Boolean

    suspend fun isAircraftConnected(): Boolean

    suspend fun loadCapability(): CameraCapability

    suspend fun loadAircraftName(): String? = null

    suspend fun loadAircraftModel(): String?

    suspend fun loadFlightLimit(): DjiFlightLimit

    suspend fun loadDeviceIdentity(): DjiDeviceIdentity?
}
