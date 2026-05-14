package com.yinxin.uavfir.sdk

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager

interface MsdkKeyValueClient {
    fun isAircraftConnected(): Boolean

    fun loadCapability(): CameraCapability
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
}
