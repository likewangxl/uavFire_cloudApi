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
    val frameTimestampsMillis: List<Long>,
    val policyVersion: String,
) {
    init {
        require(confidence in 0f..1f)
        require(frameTimestampsMillis.size == 2)
        require(frameTimestampsMillis.zipWithNext().all { (first, second) -> second > first })
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
    data class TerminalResultReady(
        val locationStatus: LocationStatus,
        val geoMethod: GeoMethod,
    ) : FireSessionEvent
    data object TerminalResultDurable : FireSessionEvent
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
    data class PersistTerminalResult(
        val locationStatus: LocationStatus,
        val geoMethod: GeoMethod,
    ) : FireSessionEffect {
        init {
            require(locationStatus != LocationStatus.LASER_LOCATING) {
                "A locating status is not a terminal result"
            }
            require(
                (locationStatus == LocationStatus.PRECISE && geoMethod == GeoMethod.LASER_RANGEFINDER) ||
                    (locationStatus == LocationStatus.DEGRADED_OSD &&
                        geoMethod == GeoMethod.AIRCRAFT_OBSERVATION),
            ) { "Terminal location status and geo method do not match" }
        }
    }
    data object ResumeMission : FireSessionEffect
}

data class FireSessionReduction(
    val state: FireSessionState,
    val effects: List<FireSessionEffect> = emptyList(),
    val accepted: Boolean,
)
