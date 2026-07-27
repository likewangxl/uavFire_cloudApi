package com.yinxin.uavfir.benchmark

import org.json.JSONArray
import org.json.JSONObject

internal data class ModelArtifact(val path: String, val sha256: String)

internal data class ModelManifest(
    val inputWidth: Int,
    val inputHeight: Int,
    val normalizationScale: Float,
    val confidenceThreshold: Float,
    val iouThreshold: Float,
    private val artifactsByEngine: Map<Engine, List<ModelArtifact>>,
) {
    fun artifact(engine: Engine): List<ModelArtifact> = artifactsByEngine.getValue(engine)
}

internal object ModelManifestParser {
    private const val EXPECTED_OUTPUT_LAYOUT = "xywh, class scores; postprocess with NMS"

    fun parse(json: String): ModelManifest {
        val root = JSONObject(json)
        val inputSize = root.getJSONArray("inputSize")
        check(inputSize.length() == 2) { "Model manifest inputSize must have two dimensions" }
        val normalization = root.getJSONObject("normalization")
        check(root.getString("outputLayout") == EXPECTED_OUTPUT_LAYOUT) { "Unsupported model output contract" }
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
            inputWidth = inputSize.getInt(0),
            inputHeight = inputSize.getInt(1),
            normalizationScale = normalization.getDouble("scale").toFloat(),
            confidenceThreshold = root.getDouble("confidenceThreshold").toFloat(),
            iouThreshold = root.getDouble("iouThreshold").toFloat(),
            artifactsByEngine = artifacts,
        ).also {
            check(it.inputWidth == 640 && it.inputHeight == 640) { "Unsupported model input dimensions" }
        }
    }
}

private fun JSONArray.isZeroes(): Boolean = (0 until length()).all { getDouble(it) == 0.0 }
private fun JSONArray.isOnes(): Boolean = (0 until length()).all { getDouble(it) == 1.0 }
private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until this@mapObjects.length()) add(transform(this@mapObjects.getJSONObject(index)))
}
