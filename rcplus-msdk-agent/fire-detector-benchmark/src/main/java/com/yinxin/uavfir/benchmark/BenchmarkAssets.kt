package com.yinxin.uavfir.benchmark

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal class BenchmarkAssets(private val context: Context) {
    private val assets = context.assets
    private val manifestJson = read("benchmark-set/manifest.json")
    private val baselineJson = read("benchmark-set/pytorch-baseline.json")
    val manifest = JSONObject(manifestJson)
    val baseline = JSONObject(baselineJson)
    private val modelManifestJson = read("model-candidates.json")
    val modelManifest = ModelManifestParser.parse(modelManifestJson)
    val benchmarkManifestSha256 = sha256(manifestJson.toByteArray())
    val pytorchBaselineSha256 = sha256(baselineJson.toByteArray())

    fun verifyIntegrity() {
        BenchmarkRunContract.validateVisibleDataset(manifest, modelManifest.classNames)
        BenchmarkRunContract.validateVisibleBaseline(baseline, manifest, modelManifest)
        for (engine in Engine.entries) {
            for (artifact in modelManifest.artifact(engine)) {
                verifyHash(artifact.path, artifact.sha256)
            }
        }
        val samples = manifest.getJSONArray("samples")
        for (sampleIndex in 0 until samples.length()) {
            val sample = samples.getJSONObject(sampleIndex)
            verifyHash("benchmark-set/${sample.getString("image")}", sample.getString("imageSha256"))
        }
    }

    fun samples(): List<BenchmarkSample> = manifest.getJSONArray("samples").mapObjects { sample ->
        val imageAsset = "benchmark-set/${sample.getString("image")}"
        val dimensions = imageDimensions(imageAsset)
        BenchmarkSample(
            id = sample.getString("id"),
            imageAsset = imageAsset,
            sourceWidth = dimensions.first,
            sourceHeight = dimensions.second,
            expected = sample.getJSONArray("expectedBoxes").mapObjects { expected ->
                Detection(
                    left = (expected.getDouble("x") - expected.getDouble("width") / 2.0).toFloat().coerceIn(0f, 1f),
                    top = (expected.getDouble("y") - expected.getDouble("height") / 2.0).toFloat().coerceIn(0f, 1f),
                    right = (expected.getDouble("x") + expected.getDouble("width") / 2.0).toFloat().coerceIn(0f, 1f),
                    bottom = (expected.getDouble("y") + expected.getDouble("height") / 2.0).toFloat().coerceIn(0f, 1f),
                    confidence = 1f,
                    classIndex = expected.getInt("class"),
                )
            },
        )
    }

    fun pytorchDetections(sample: BenchmarkSample): List<Detection> {
        val baselineSample = baseline.getJSONArray("samples").mapObjects { it }.single { it.getString("id") == sample.id }
        return baselineSample.getJSONArray("detections").mapObjects { detection ->
            val xyxy = detection.getJSONArray("xyxy")
            BaselineNormalizer.normalize(
                xyxy = floatArrayOf(xyxy.getDouble(0).toFloat(), xyxy.getDouble(1).toFloat(), xyxy.getDouble(2).toFloat(), xyxy.getDouble(3).toFloat()),
                sourceWidth = sample.sourceWidth,
                sourceHeight = sample.sourceHeight,
                confidence = detection.getDouble("confidence").toFloat(),
                classIndex = detection.getInt("class"),
            )
        }
    }

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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun imageDimensions(asset: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(asset).use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "Unable to determine source dimensions for $asset" }
        return options.outWidth to options.outHeight
    }
}

internal data class BenchmarkSample(
    val id: String,
    val imageAsset: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val expected: List<Detection>,
)

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val count = length()
    return buildList {
        for (index in 0 until count) add(transform(this@mapObjects.getJSONObject(index)))
    }
}
