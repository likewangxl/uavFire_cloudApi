package com.yinxin.uavfir.api

import com.yinxin.uavfir.sdk.PayloadCapability

data class CapabilityReportRequest(
    val droneSn: String,
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
