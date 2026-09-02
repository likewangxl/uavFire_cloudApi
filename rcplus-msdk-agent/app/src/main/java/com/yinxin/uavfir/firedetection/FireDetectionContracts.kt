package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.BuildConfig
import java.util.Locale

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
    val evidenceJpeg: ByteArray,
    val evidenceSha256: String,
    val evidenceCapturedAt: Long,
)

fun interface VisibleDetectionReporter {
    suspend fun report(report: VisibleDetectionReport)
}

interface VisibleFireDetectionEngine : AutoCloseable {
    fun prepare() = Unit
    fun detect(rgba: ByteArray, width: Int, height: Int): VisibleInferenceResult
    override fun close() = Unit
}

data class AgentFireModelProfile(
    val name: String,
    val assetPath: String,
    val manifestPath: String,
    val modelVersion: String,
    val modelSha256: String,
    val inputSize: Int,
)

object AgentFireModelProfiles {
    val LEGACY_416 = AgentFireModelProfile(
        name = "legacy416",
        assetPath = "fire-detection/best.onnx",
        manifestPath = "fire-detection/model-manifest.json",
        modelVersion = "best-20260808",
        modelSha256 = "68db8102b3ae591d2f1bca3e585d8bc1850933d608ae132a86f61eee89d42271",
        inputSize = 416,
    )
    val VISIBLE_960 = AgentFireModelProfile(
        name = "visible960",
        assetPath = "fire-detection/best-fire-smoke-960.onnx",
        manifestPath = "fire-detection/model-manifest-960.json",
        modelVersion = "best-fire-smoke-960-20260902",
        modelSha256 = "24563198eb66e797ac3f32123dfe78410aeeb6ef766b936e825c3146686137a6",
        inputSize = 960,
    )

    fun resolve(name: String): AgentFireModelProfile = when (name.trim().lowercase(Locale.US)) {
        LEGACY_416.name.lowercase(Locale.US) -> LEGACY_416
        VISIBLE_960.name.lowercase(Locale.US) -> VISIBLE_960
        else -> error("unsupported-agent-fire-model-profile:$name")
    }
}

object AgentFireModelSpec {
    val activeProfile: AgentFireModelProfile = AgentFireModelProfiles.resolve(BuildConfig.AGENT_FIRE_MODEL_PROFILE)
    val ASSET_PATH: String get() = activeProfile.assetPath
    val MANIFEST_PATH: String get() = activeProfile.manifestPath
    val MODEL_VERSION: String get() = activeProfile.modelVersion
    val MODEL_SHA256: String get() = activeProfile.modelSha256
    val INPUT_SIZE: Int get() = activeProfile.inputSize
    const val CONFIDENCE_THRESHOLD = 0.25
    const val IOU_THRESHOLD = 0.70
    val CLASS_NAMES = arrayOf("fire", "smoke")
}
