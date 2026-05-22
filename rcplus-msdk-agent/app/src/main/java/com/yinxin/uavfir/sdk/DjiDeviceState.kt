package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.session.AgentConnectionState

data class DjiDeviceState(
    val connectionState: AgentConnectionState,
    val capability: CameraCapability? = null,
    val telemetry: DjiTelemetry? = null,
)

data class DjiTelemetry(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val height: Double? = null,
    val elevation: Double? = null,
    val horizontalSpeed: Double? = null,
    val verticalSpeed: Double? = null,
    val batteryPercent: Int? = null,
)
