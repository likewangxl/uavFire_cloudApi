package com.yinxin.uavfir.firedetection

fun interface VisibleFrameConsumer {
    fun onVisibleRgbaFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        timestampMs: Long,
    )

    companion object {
        val NO_OP = VisibleFrameConsumer { _, _, _, _, _, _ -> }
    }
}

interface VisibleAiControl {
    suspend fun start(droneSn: String): VisibleAiControlResult
    suspend fun stop(droneSn: String): VisibleAiControlResult
}

data class VisibleAiControlResult(
    val applied: Boolean,
    val message: String,
)

data class NormalizedRoi(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0
}

data class VisibleDetection(
    val classId: Int,
    val label: String,
    val confidence: Double,
    val roi: NormalizedRoi,
)

data class VisibleInferenceResult(
    val detections: List<VisibleDetection>,
    val inferenceMs: Long,
)

data class VisibleDetectionReport(
    val taskId: String,
    val droneSn: String,
    val sourceTs: Long,
    val detection: VisibleDetection,
    val inferenceMs: Long,
    val modelVersion: String,
    val modelSha256: String,
)

fun interface VisibleDetectionReporter {
    suspend fun report(report: VisibleDetectionReport)
}

interface VisibleFireDetectionEngine : AutoCloseable {
    fun prepare() = Unit
    fun detect(rgba: ByteArray, width: Int, height: Int): VisibleInferenceResult
    override fun close() = Unit
}

object AgentFireModelSpec {
    const val ASSET_PATH = "fire-detection/best.onnx"
    const val MANIFEST_PATH = "fire-detection/model-manifest.json"
    const val MODEL_VERSION = "best-20260808"
    const val MODEL_SHA256 = "68db8102b3ae591d2f1bca3e585d8bc1850933d608ae132a86f61eee89d42271"
    const val INPUT_SIZE = 416
    const val CONFIDENCE_THRESHOLD = 0.25
    const val IOU_THRESHOLD = 0.70
    val CLASS_NAMES = arrayOf("fire", "smoke")
}
