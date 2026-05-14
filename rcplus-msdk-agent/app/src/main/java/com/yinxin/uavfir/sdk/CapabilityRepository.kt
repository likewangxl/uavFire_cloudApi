package com.yinxin.uavfir.sdk

class CapabilityRepository(
    private val djiSdkGateway: DjiSdkGateway,
) {
    suspend fun load(): CameraCapability {
        if (!djiSdkGateway.initialize()) {
            return CameraCapability(
                visibleSupported = false,
                thermalSupported = false,
            )
        }

        return djiSdkGateway.loadCapability()
    }
}
