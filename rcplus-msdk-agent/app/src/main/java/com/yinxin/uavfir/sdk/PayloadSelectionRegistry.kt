package com.yinxin.uavfir.sdk

import dji.sdk.keyvalue.value.common.ComponentIndexType

/** Single source of truth for camera, gimbal, Tap Zoom and laser component keys. */
object PayloadSelectionRegistry {
    // DJI enum initialization touches Android runtime classes, so defer it until
    // a real MSDK key is built. Pure JVM command tests only need capability state.
    private val positionToComponent by lazy {
        mapOf(
            0 to ComponentIndexType.LEFT_OR_MAIN,
            1 to ComponentIndexType.RIGHT,
            2 to ComponentIndexType.UP,
        )
    }

    @Volatile
    private var capability: CameraCapability = CameraCapability(false, false)

    fun update(value: CameraCapability) {
        capability = value
    }

    fun currentCapability(): CameraCapability = capability

    fun selectedComponentIndex(): ComponentIndexType =
        capability.selectedPayloadPositionIndex
            ?.let(positionToComponent::get)
            ?: ComponentIndexType.LEFT_OR_MAIN

    fun componentForPosition(positionIndex: Int): ComponentIndexType? = positionToComponent[positionIndex]
}
