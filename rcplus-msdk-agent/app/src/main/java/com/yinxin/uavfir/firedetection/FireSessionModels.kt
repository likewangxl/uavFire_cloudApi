package com.yinxin.uavfir.firedetection

enum class FireSessionState {
    DISARMED,
    ARMING,
    SCANNING,
    VISUAL_CONFIRMING,
    VISUAL_CONFIRMED,
    HOLD_REQUESTED,
    HOVER_VERIFYING,
    TARGET_ALIGNING,
    LASER_MEASURING,
    RESULT_DURABLE,
    RESUME_REQUESTED,
    MISSION_RESUMED,
    MANUAL_HOLD,
}

enum class DetectionKind {
    FIRE,
    SMOKE,
}

enum class LocationStatus {
    LASER_LOCATING,
    PRECISE,
    DEGRADED_OSD,
}

enum class GeoMethod {
    LASER_RANGEFINDER,
    AIRCRAFT_OBSERVATION,
}

data class NormalizedRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "ROI coordinates must be normalized"
        }
        require(right > left && bottom > top) { "ROI must have positive area" }
    }

    val centerX: Double get() = (left + right).toDouble() / 2.0
    val centerY: Double get() = (top + bottom).toDouble() / 2.0

    companion object {
        fun from(detection: VisibleDetection) = NormalizedRoi(
            left = detection.left,
            top = detection.top,
            right = detection.right,
            bottom = detection.bottom,
        )
    }
}

data class VisibleConfirmation(
    val kind: DetectionKind,
    val confidence: Float,
    val roi: NormalizedRoi,
    val firstFrameTimestampMillis: Long,
    val secondFrameTimestampMillis: Long,
    val policyVersion: String,
) {
    val frameTimestampsMillis: List<Long>
        get() = mutableListOf(firstFrameTimestampMillis, secondFrameTimestampMillis)

    init {
        require(confidence in 0f..1f)
        require(firstFrameTimestampMillis >= 0L)
        require(secondFrameTimestampMillis > firstFrameTimestampMillis)
        require(policyVersion.isNotBlank())
    }
}

data class ConfirmationHealth(
    val frameHealthy: Boolean,
    val modelHealthy: Boolean,
    val runtimeHealthy: Boolean,
    val storeHealthy: Boolean,
) {
    val allHealthy: Boolean
        get() = frameHealthy && modelHealthy && runtimeHealthy && storeHealthy
}

data class VisibleConfirmationInput(
    val frame: VisibleRgbaFrame,
    val result: VisibleDetectionResult,
    val observedAtMillis: Long,
    val health: ConfirmationHealth,
) {
    init {
        require(observedAtMillis >= 0L)
    }
}

data class VisibleConfirmationOutcome(
    val confirmation: VisibleConfirmation? = null,
    val reset: Boolean = false,
)

enum class FireSessionFailure {
    UNKNOWN_MISSION_STATE,
    MISSING_BREAKPOINT,
    MANUAL_INTERVENTION,
    STORAGE_FAILURE,
    DETECTOR_FAILURE,
    RESUME_FAILURE,
}

data class TerminalPersistenceRequest(
    val requestId: String,
    val locationStatus: LocationStatus,
    val geoMethod: GeoMethod,
) {
    val isValidTerminalMapping: Boolean
        get() = (locationStatus == LocationStatus.PRECISE &&
            geoMethod == GeoMethod.LASER_RANGEFINDER) ||
            (locationStatus == LocationStatus.DEGRADED_OSD &&
                geoMethod == GeoMethod.AIRCRAFT_OBSERVATION)

    init {
        require(requestId.isNotBlank()) { "Terminal persistence request ID is required" }
    }
}

data class FireSessionPhase(
    val state: FireSessionState,
    val pendingTerminal: TerminalPersistenceRequest? = null,
    val durableTerminal: TerminalPersistenceRequest? = null,
) {
    val terminalPersistenceVerified: Boolean
        get() = durableTerminal != null

    init {
        require(pendingTerminal == null || state == FireSessionState.LASER_MEASURING) {
            "Pending terminal persistence is only valid while laser measurement is active"
        }
        require(durableTerminal == null || state == FireSessionState.RESULT_DURABLE) {
            "Terminal persistence proof is only valid for a durable result"
        }
        require(pendingTerminal == null || durableTerminal == null) {
            "Terminal persistence cannot be pending and verified simultaneously"
        }
        require(pendingTerminal?.isValidTerminalMapping != false) {
            "Pending terminal persistence mapping is invalid"
        }
        require(durableTerminal?.isValidTerminalMapping != false) {
            "Durable terminal persistence mapping is invalid"
        }
    }

    companion object {
        fun unproven(state: FireSessionState) = FireSessionPhase(state)
    }
}

sealed interface FireSessionEvent {
    data object ArmRequested : FireSessionEvent
    data object Armed : FireSessionEvent
    data object CandidateObserved : FireSessionEvent
    data object CandidateCleared : FireSessionEvent
    data class VisualConfirmed(val confirmation: VisibleConfirmation) : FireSessionEvent
    data object InitialAlertDurable : FireSessionEvent
    data object MissionPaused : FireSessionEvent
    data object HoverStable : FireSessionEvent
    data object TargetAligned : FireSessionEvent
    data class TerminalResultReady(val request: TerminalPersistenceRequest) : FireSessionEvent
    data class TerminalResultDurable(val requestId: String) : FireSessionEvent {
        init {
            require(requestId.isNotBlank())
        }
    }
    data object ResumeRequested : FireSessionEvent
    data object MissionResumeConfirmed : FireSessionEvent
    data object ScanContinued : FireSessionEvent
    data object RecoveryVerified : FireSessionEvent
    data object DisarmRequested : FireSessionEvent
    data class UnsafeFailure(val failure: FireSessionFailure) : FireSessionEvent
}

sealed interface FireSessionEffect {
    data class PersistInitialAlert(val confirmation: VisibleConfirmation) : FireSessionEffect
    data object PauseMission : FireSessionEffect
    data object AlignTarget : FireSessionEffect
    data object MeasureLaser : FireSessionEffect
    data class PersistTerminalResult(val request: TerminalPersistenceRequest) : FireSessionEffect
    data object ResumeMission : FireSessionEffect
}

data class FireSessionReduction(
    val phase: FireSessionPhase,
    val effects: List<FireSessionEffect> = emptyList(),
    val accepted: Boolean,
) {
    val state: FireSessionState
        get() = phase.state
}
