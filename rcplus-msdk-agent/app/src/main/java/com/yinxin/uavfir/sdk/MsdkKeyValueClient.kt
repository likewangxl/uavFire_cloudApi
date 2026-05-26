package com.yinxin.uavfir.sdk

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager

interface MsdkKeyValueClient {
    fun isAircraftConnected(): Boolean

    fun loadCapability(): CameraCapability

    fun loadAircraftModel(): String?

    fun loadFlightLimit(): DjiFlightLimit
}

class RealMsdkKeyValueClient : MsdkKeyValueClient {
    override fun isAircraftConnected(): Boolean {
        return KeyManager.getInstance().getValue(
            KeyTools.createKey(ProductKey.KeyConnection),
        ) == true
    }

    override fun loadCapability(): CameraCapability {
        val sourceRange = KeyManager.getInstance().getValue(
            KeyTools.createKey(
                CameraKey.KeyCameraVideoStreamSourceRange,
                ComponentIndexType.LEFT_OR_MAIN,
            ),
        ) as? List<*>

        val sourceNames = sourceRange
            ?.mapNotNull { it?.toString() }
            .orEmpty()

        return CameraCapability(
            visibleSupported = sourceNames.any { it != "INFRARED_CAMERA" },
            thermalSupported = sourceNames.any { it == "INFRARED_CAMERA" },
        )
    }

    override fun loadAircraftModel(): String? {
        return runCatching {
            KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeyProductType))?.toString()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    override fun loadFlightLimit(): DjiFlightLimit {
        val heightLimit = runCatching {
            FlightControllerKey.KeyHeightLimit.create().get(0)
        }.getOrNull()?.takeIf { it > 0 }
        val distanceEnabled = runCatching {
            FlightControllerKey.KeyDistanceLimitEnabled.create().get(false)
        }.getOrNull()
        val distanceLimit = runCatching {
            FlightControllerKey.KeyDistanceLimit.create().get(0)
        }.getOrNull()?.takeIf { it > 0 }

        return DjiFlightLimit(
            heightLimitMeters = heightLimit,
            distanceLimitEnabled = distanceEnabled,
            distanceLimitMeters = distanceLimit,
        )
    }
}
