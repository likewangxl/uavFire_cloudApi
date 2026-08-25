package com.yinxin.uavfir.sdk

data class CameraCapability(
    val visibleSupported: Boolean,
    val thermalSupported: Boolean,
    val aircraftModelKey: String? = null,
    val controllerModelKey: String? = null,
    val payloads: List<PayloadCapability> = emptyList(),
    val selectedPayloadPositionIndex: Int? = null,
    val laserSupported: Boolean = false,
    val fireClosedLoopReady: Boolean = false,
    val blockingReasons: List<String> = emptyList(),
)

data class PayloadCapability(
    val payloadModelKey: String,
    val payloadPositionIndex: Int,
    val visibleSupported: Boolean,
    val thermalSupported: Boolean,
    val laserSupported: Boolean,
    val tapZoomSupported: Boolean,
    val liveStreamSupported: Boolean = true,
    val waylineSupported: Boolean = true,
)
