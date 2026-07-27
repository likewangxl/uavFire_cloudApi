package com.yinxin.uavfir.benchmark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal class BenchmarkAssets(private val context: Context) {
    private val assets = context.assets
    val manifest = JSONObject(read("benchmark-set/manifest.json"))
    val baseline = JSONObject(read("benchmark-set/pytorch-baseline.json"))

    fun verifyIntegrity() {
        val candidates = JSONObject(read("model-candidates.json")).getJSONArray("candidates")
        for (candidateIndex in 0 until candidates.length()) {
            val artifacts = candidates.getJSONObject(candidateIndex).getJSONArray("artifacts")
            for (artifactIndex in 0 until artifacts.length()) {
                val artifact = artifacts.getJSONObject(artifactIndex)
                verifyHash(artifact.getString("path"), artifact.getString("sha256"))
            }
        }
        val samples = manifest.getJSONArray("samples")
        for (sampleIndex in 0 until samples.length()) {
            val sample = samples.getJSONObject(sampleIndex)
            verifyHash("benchmark-set/${sample.getString("image")}", sample.getString("imageSha256"))
        }
    }

    fun samples(): List<BenchmarkSample> = manifest.getJSONArray("samples").mapObjects { sample ->
        BenchmarkSample(
            id = sample.getString("id"),
            imageAsset = "benchmark-set/${sample.getString("image")}",
            expected = sample.getJSONArray("expectedBoxes").mapObjects { expected ->
                Detection(
                    left = (expected.getDouble("x") - expected.getDouble("width") / 2.0).toFloat(),
                    top = (expected.getDouble("y") - expected.getDouble("height") / 2.0).toFloat(),
                    right = (expected.getDouble("x") + expected.getDouble("width") / 2.0).toFloat(),
                    bottom = (expected.getDouble("y") + expected.getDouble("height") / 2.0).toFloat(),
                    confidence = 1f,
                )
            },
        )
    }

    fun pytorchDetections(): Map<String, List<Detection>> = baseline.getJSONArray("samples").mapObjects { sample ->
        sample.getString("id") to sample.getJSONArray("detections").mapObjects { detection ->
            val xyxy = detection.getJSONArray("xyxy")
            Detection(
                left = (xyxy.getDouble(0) / 640.0).toFloat(),
                top = (xyxy.getDouble(1) / 640.0).toFloat(),
                right = (xyxy.getDouble(2) / 640.0).toFloat(),
                bottom = (xyxy.getDouble(3) / 640.0).toFloat(),
                confidence = detection.getDouble("confidence").toFloat(),
            )
        }
    }.toMap()

    fun openImage(asset: String) = assets.open(asset)

    private fun verifyHash(asset: String, expected: String) {
        val digest = assets.open(asset).use { input ->
            val messageDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
            messageDigest.digest().joinToString("") { "%02x".format(it) }
        }
        check(digest == expected) { "SHA-256 mismatch for $asset" }
    }

    private fun read(asset: String): String = assets.open(asset).bufferedReader().use { it.readText() }
}

internal data class BenchmarkSample(
    val id: String,
    val imageAsset: String,
    val expected: List<Detection>,
)

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val count = length()
    return buildList {
        for (index in 0 until count) add(transform(this@mapObjects.getJSONObject(index)))
    }
}
