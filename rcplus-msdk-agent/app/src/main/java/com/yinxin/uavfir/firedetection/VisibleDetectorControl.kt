package com.yinxin.uavfir.firedetection

data class VisibleDetectorStatus(
    val intent: String,
    val state: String,
    val health: String,
    val reason: String? = null,
) {
    val running: Boolean get() = state == "ARMED" && health == "HEALTHY"

    companion object {
        fun disarmed() = VisibleDetectorStatus("DISARMED", "DISARMED", "HEALTHY", "operator-disarmed")
    }
}

class VisibleDetectorControl(
    private val armingHealth: () -> CoordinatorArmingHealth,
) {
    @Volatile
    private var armRequested = false

    fun isArmRequested(): Boolean = armRequested

    fun arm(): VisibleDetectorStatus {
        armRequested = true
        return snapshot()
    }

    fun disarm(): VisibleDetectorStatus {
        armRequested = false
        return snapshot()
    }

    fun snapshot(): VisibleDetectorStatus {
        if (!armRequested) return VisibleDetectorStatus.disarmed()
        val gates = armingHealth().copy(detectorArmRequested = true)
        val reason = gates.firstFailureReason()
        return if (reason == null) {
            VisibleDetectorStatus("ARMED", "ARMED", "HEALTHY")
        } else {
            VisibleDetectorStatus("ARMED", "BLOCKED", "UNHEALTHY", reason)
        }
    }
}

private fun CoordinatorArmingHealth.firstFailureReason(): String? = when {
    !featureEnabled -> "feature-disabled"
    !visibleSourceActive -> "visible-source-inactive"
    !sourceGenerationValid -> "source-generation-invalid"
    !detectorHealthy -> "detector-unhealthy"
    !storeHealthy -> "store-unhealthy"
    !outboxHealthy -> "outbox-unhealthy"
    !missionAdaptersHealthy -> "mission-adapter-unhealthy"
    !safetyAdaptersHealthy -> "safety-adapter-unhealthy"
    manualHoldActive -> "manual-hold-active"
    competingOwnerActive -> "competing-owner-active"
    else -> null
}
