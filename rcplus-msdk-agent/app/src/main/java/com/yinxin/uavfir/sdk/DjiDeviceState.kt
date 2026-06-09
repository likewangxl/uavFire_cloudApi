package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.session.AgentConnectionState

data class DjiDeviceState(
    val connectionState: AgentConnectionState,
    val identity: DjiDeviceIdentity? = null,
    val capability: CameraCapability? = null,
    val telemetry: DjiTelemetry? = null,
    val aircraftModel: String? = null,
    val flightLimit: DjiFlightLimit = DjiFlightLimit(),
)

data class DjiDeviceIdentity(
    val gatewaySn: String,
    val aircraftSn: String,
) {
    fun isValid(): Boolean = gatewaySn.isNotBlank() && aircraftSn.isNotBlank()
}

data class DjiTelemetry(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val height: Double? = null,
    val elevation: Double? = null,
    val horizontalSpeed: Double? = null,
    val verticalSpeed: Double? = null,
    val batteryPercent: Int? = null,
)

data class DjiFlightLimit(
    val heightLimitMeters: Int? = null,
    val distanceLimitEnabled: Boolean? = null,
    val distanceLimitMeters: Int? = null,
)

data class DjiStorageStatus(
    val freeBytes: Long?,
    val totalBytes: Long?,
)

data class DjiHomeStatus(
    val flightLimitText: String,
    val taskSpaceText: String,
    val aircraftStatusText: String,
)
