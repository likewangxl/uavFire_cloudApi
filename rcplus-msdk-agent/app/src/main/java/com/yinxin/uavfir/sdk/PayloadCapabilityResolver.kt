package com.yinxin.uavfir.sdk

object PayloadCapabilityResolver {
    fun resolve(
        aircraftModelKey: String?,
        controllerModelKey: String?,
        payloads: List<PayloadCapability>,
        operatorSelectedPositionIndex: Int? = null,
        m300FireClosedLoopEnabled: Boolean = false,
    ): CameraCapability {
        val candidates = payloads.filter { it.visibleSupported && it.laserSupported }
        val selected = when {
            operatorSelectedPositionIndex != null -> candidates.singleOrNull {
                it.payloadPositionIndex == operatorSelectedPositionIndex
            }
            else -> candidates.singleOrNull()
        }
        val reasons = buildList {
            if (aircraftModelKey == "M300" && payloads.isEmpty()) add("compatible-payload-not-found")
            if (aircraftModelKey == "M300" && controllerModelKey != "RC_PLUS") {
                add("m300-requires-rc-plus")
            }
            if (operatorSelectedPositionIndex == null && candidates.size > 1) {
                add("multiple-compatible-payloads-require-operator-selection")
            }
            if (operatorSelectedPositionIndex != null && selected == null) {
                add("operator-selected-payload-unavailable-or-incompatible")
            }
            if (aircraftModelKey == "M300" && !m300FireClosedLoopEnabled) {
                add("m300-fire-closed-loop-feature-disabled")
            }
        }
        return CameraCapability(
            visibleSupported = selected?.visibleSupported ?: payloads.any { it.visibleSupported },
            thermalSupported = selected?.thermalSupported ?: payloads.any { it.thermalSupported },
            aircraftModelKey = aircraftModelKey,
            controllerModelKey = controllerModelKey,
            payloads = payloads,
            selectedPayloadPositionIndex = selected?.payloadPositionIndex,
            laserSupported = selected?.laserSupported == true,
            fireClosedLoopReady = selected != null && reasons.isEmpty(),
            blockingReasons = reasons,
        )
    }
}
