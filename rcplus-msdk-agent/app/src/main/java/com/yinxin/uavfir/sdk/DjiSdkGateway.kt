package com.yinxin.uavfir.sdk

interface DjiSdkGateway {
    suspend fun initialize(): Boolean

    suspend fun isAircraftConnected(): Boolean

    suspend fun loadCapability(): CameraCapability
}
