package com.yinxin.uavfir.benchmark

import org.json.JSONArray
import org.json.JSONObject

internal data class ModelArtifact(val path: String, val sha256: String)

internal data class ModelManifest(
    val sha256: String,
    val schemaVersion: Int,
    val modelVersion: String,
    val sourceName: String,
    val sourceSha256: String,
    val classNames: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val normalizationScale: Float,
    val confidenceThreshold: Float,
    val iouThreshold: Float,
    private val artifactsByEngine: Map<Engine, List<ModelArtifact>>,
) {
    fun artifact(engine: Engine): List<ModelArtifact> = artifactsByEngine.getValue(engine)
    val outputChannels: Int get() = 4 + classNames.size
    val candidateCount: Int get() = listOf(8, 16, 32).sumOf { stride ->
        (inputWidth / stride) * (inputHeight / stride)
    }
}

internal object ModelManifestParser {
    private const val EXPECTED_SCHEMA_VERSION = 2
    private const val EXPECTED_INPUT_SIZE = 960
    private const val APPROVED_SOURCE_NAME = "visible-fire-wechat-best2-20260728.pt"
    private const val APPROVED_SOURCE_SHA256 = "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"
    private const val EXPECTED_OUTPUT_LAYOUT = "xywh, class scores; postprocess with NMS"
    private val EXPECTED_CLASSES = listOf("fire", "smoke")

    fun parse(json: String): ModelManifest {
        val root = JSONObject(json)
        check(root.optInt("schemaVersion", -1) == EXPECTED_SCHEMA_VERSION) {
            "Only visible model manifest schema v2 is supported"
        }
        check(root.getString("modality") == "visible") { "Only visible model manifests are supported" }
        val modelVersion = root.getString("modelVersion")
        val source = root.getJSONObject("source")
        check(
            modelVersion == APPROVED_SOURCE_NAME.removeSuffix(".pt") &&
                source.getString("name") == APPROVED_SOURCE_NAME &&
                source.getString("sha256") == APPROVED_SOURCE_SHA256,
        ) {
            "Visible benchmark requires the approved production source model"
        }
        val classes = root.getJSONArray("classes").strings()
        check(classes == EXPECTED_CLASSES) { "Visible benchmark classes must be fire and smoke" }
        val input = root.getJSONObject("input")
        check(input.getInt("width") == EXPECTED_INPUT_SIZE && input.getInt("height") == EXPECTED_INPUT_SIZE) {
            "Visible benchmark input must be 960x960"
        }
        check(input.getInt("channels") == 3 && input.getString("colorSpace") == "RGB") {
            "Visible benchmark input must be three-channel RGB"
        }
        val normalization = input.getJSONObject("normalization")
        val postprocess = root.getJSONObject("postprocess")
        check(postprocess.getString("outputLayout") == EXPECTED_OUTPUT_LAYOUT) { "Unsupported model output contract" }
        val artifacts = root.getJSONArray("candidates").mapObjects { candidate ->
            Engine.valueOf(candidate.getString("engine").uppercase()) to candidate.getJSONArray("artifacts").mapObjects {
                artifact -> ModelArtifact(artifact.getString("path"), artifact.getString("sha256"))
            }
        }.toMap()
        check(artifacts.keys == Engine.entries.toSet()) { "Manifest must provide ONNX, TFLite, and NCNN candidates" }
        check(normalization.getJSONArray("mean").isZeroes() && normalization.getJSONArray("std").isOnes()) {
            "Unsupported normalization mean/std"
        }
        return ModelManifest(
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) },
            schemaVersion = root.getInt("schemaVersion"),
            modelVersion = modelVersion,
            sourceName = source.getString("name"),
            sourceSha256 = source.getString("sha256"),
            classNames = classes,
            inputWidth = input.getInt("width"),
            inputHeight = input.getInt("height"),
            normalizationScale = normalization.getDouble("scale").toFloat(),
            confidenceThreshold = postprocess.getDouble("confidenceThreshold").toFloat(),
            iouThreshold = postprocess.getDouble("iouThreshold").toFloat(),
            artifactsByEngine = artifacts,
        )
    }
}

private fun JSONArray.isZeroes(): Boolean = (0 until length()).all { getDouble(it) == 0.0 }
private fun JSONArray.isOnes(): Boolean = (0 until length()).all { getDouble(it) == 1.0 }
private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until this@mapObjects.length()) add(transform(this@mapObjects.getJSONObject(index)))
}
