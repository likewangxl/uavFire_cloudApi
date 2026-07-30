package com.yinxin.uavfir.firedetection

data class VisibleConfirmationPolicy(
    val policyVersion: String = "agent-visible-v1",
    val requiredFreshFrames: Int = 2,
    val maxFrameAgeMs: Long = 300L,
    val maxCenterDistance: Double,
    val fireConfidence: Float,
    val smokeConfidence: Float,
    val nmsIou: Float,
    val fireColorRedMin: Int = 180,
    val fireColorGreenMin: Int = 80,
    val fireColorBlueMax: Int = 120,
    val fireColorMinPixels: Int = 5,
    val isDefaultOff: Boolean = true,
) {
    init {
        require(policyVersion.isNotBlank()) { "Confirmation policy version is required" }
        require(requiredFreshFrames == 2) { "Visible confirmation requires exactly two fresh frames" }
        require(maxFrameAgeMs >= 0L) { "Maximum frame age must be non-negative" }
        require(maxCenterDistance in 0.0..1.0) { "Maximum center distance must be normalized" }
        require(fireConfidence in 0f..1f && smokeConfidence in 0f..1f && nmsIou in 0f..1f) {
            "Detection thresholds must be normalized"
        }
        require(nmsIou == VisibleDetectorContract.NMS_IOU_THRESHOLD) {
            "Confirmation NMS must match the packaged detector contract"
        }
        require(fireColorRedMin in 0..255 && fireColorGreenMin in 0..255 && fireColorBlueMax in 0..255) {
            "Fire-color channel thresholds must be bytes"
        }
        require(fireColorMinPixels > 0) { "Fire-color minimum pixel count must be positive" }
        require(isDefaultOff) { "Provisional visible confirmation must remain default-off" }
    }
}
