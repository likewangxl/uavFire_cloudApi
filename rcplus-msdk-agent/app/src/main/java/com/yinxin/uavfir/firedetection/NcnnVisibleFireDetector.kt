package com.yinxin.uavfir.firedetection

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

internal data class VisibleFireModelArtifact(
    val path: String,
    val sha256: String,
)

internal data class VisibleFireModelManifest(
    val status: String,
    val enabledByDefault: Boolean,
    val visible960GatePassed: Boolean,
    val runtime: String,
    val runtimeVersion: String,
    val runtimeArchiveSha256: String,
    val modelVersion: String,
    val sourceName: String,
    val sourceSha256: String,
    val classNames: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val normalizationScale: Float,
    val confidenceThreshold: Float,
    val iouThreshold: Float,
    val artifacts: List<VisibleFireModelArtifact>,
) {
    val outputChannels: Int get() = 4 + classNames.size
    val candidateCount: Int get() = listOf(8, 16, 32).sumOf { stride ->
        (inputWidth / stride) * (inputHeight / stride)
    }
}

internal object VisibleFireModelManifestParser {
    private const val APPROVED_STATUS = "PROVISIONAL_NCNN_SELECTED"
    private const val APPROVED_RUNTIME = "ncnn"
    private const val APPROVED_RUNTIME_VERSION = "20260526"
    private const val APPROVED_RUNTIME_ARCHIVE_SHA256 =
        "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1"
    private const val APPROVED_MODEL_VERSION = "visible-fire-wechat-best2-20260728"
    private const val APPROVED_SOURCE_NAME = "$APPROVED_MODEL_VERSION.pt"
    private const val APPROVED_SOURCE_SHA256 =
        "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"
    private const val APPROVED_PARAM_PATH = "fire-detection/visible-fire-960.ncnn.param"
    private const val APPROVED_PARAM_SHA256 =
        "1600cc47b5ca71330e99fc9ae0852409f0309142ea176f4c7a45eae56e87aec7"
    private const val APPROVED_BIN_PATH = "fire-detection/visible-fire-960.ncnn.bin"
    private const val APPROVED_BIN_SHA256 =
        "3bfb2288cff211b3afa2f3c1f9ab6f16cab8bc625f6e3a3e436984acc4a7e84d"
    private const val OUTPUT_LAYOUT = "xywh, class scores; postprocess with NMS"
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun parse(json: String): VisibleFireModelManifest {
        val root = JsonParser.parseString(json).asJsonObject
        check(root.int("schemaVersion") == 1) { "Unsupported visible detector manifest schema" }
        val status = root.string("status")
        val enabledByDefault = root.boolean("enabledByDefault")
        val visible960GatePassed = root.boolean("visible960GatePassed")
        check(status == APPROVED_STATUS && !enabledByDefault && !visible960GatePassed) {
            "Visible-960 detector is development-only and must remain disabled by default"
        }

        val runtime = root.objectValue("runtime")
        val runtimeName = runtime.string("name")
        val runtimeVersion = runtime.string("version")
        val runtimeArchiveSha256 = runtime.string("archiveSha256")
        check(runtimeName == APPROVED_RUNTIME) { "Unsupported visible detector runtime: $runtimeName" }
        check(
            runtimeVersion == APPROVED_RUNTIME_VERSION &&
                runtimeArchiveSha256 == APPROVED_RUNTIME_ARCHIVE_SHA256,
        ) { "Visible detector runtime does not match the approved NCNN archive" }

        val modelVersion = root.string("modelVersion")
        val source = root.objectValue("source")
        val sourceName = source.string("name")
        val sourceSha256 = source.string("sha256")
        check(
            modelVersion == APPROVED_MODEL_VERSION &&
                sourceName == APPROVED_SOURCE_NAME &&
                sourceSha256 == APPROVED_SOURCE_SHA256,
        ) { "Visible detector source model is not the approved production checkpoint" }

        val classes = root.array("classes").strings()
        check(classes == listOf("fire", "smoke")) { "Visible detector class order must be fire, smoke" }
        val input = root.objectValue("input")
        val inputWidth = input.int("width")
        val inputHeight = input.int("height")
        val normalizationScale = input.float("normalizationScale")
        check(inputWidth == 960 && inputHeight == 960) { "Visible detector input must be 960x960" }
        check(input.int("channels") == 3 && input.string("colorSpace") == "RGB") {
            "Visible detector input must be three-channel RGB"
        }
        check(abs(normalizationScale - 1f / 255f) < 1e-9f) { "Unsupported visible detector normalization" }

        val postprocess = root.objectValue("postprocess")
        val confidenceThreshold = postprocess.float("confidenceThreshold")
        val iouThreshold = postprocess.float("iouThreshold")
        check(confidenceThreshold == 0.25f && iouThreshold == 0.7f) {
            "Visible detector thresholds must match the approved export"
        }
        check(postprocess.string("outputLayout") == OUTPUT_LAYOUT) { "Unsupported visible detector output layout" }

        val artifacts = root.array("artifacts").objects().map { artifact ->
            VisibleFireModelArtifact(artifact.string("path"), artifact.string("sha256"))
        }
        val expected = listOf(
            VisibleFireModelArtifact(APPROVED_PARAM_PATH, APPROVED_PARAM_SHA256),
            VisibleFireModelArtifact(APPROVED_BIN_PATH, APPROVED_BIN_SHA256),
        )
        check(artifacts == expected && artifacts.all { it.sha256.matches(SHA256) }) {
            "Visible detector artifacts do not match the approved NCNN param/bin"
        }
        return VisibleFireModelManifest(
            status = status,
            enabledByDefault = enabledByDefault,
            visible960GatePassed = visible960GatePassed,
            runtime = runtimeName,
            runtimeVersion = runtimeVersion,
            runtimeArchiveSha256 = runtimeArchiveSha256,
            modelVersion = modelVersion,
            sourceName = sourceName,
            sourceSha256 = sourceSha256,
            classNames = classes,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            normalizationScale = normalizationScale,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold,
            artifacts = artifacts,
        )
    }
}

internal interface VisibleFireNcnnRuntime {
    fun loadLibrary()
    fun create(paramPath: String, binPath: String): Long
    fun infer(handle: Long, input: ByteBuffer): FloatArray
    fun close(handle: Long)
}

internal object AndroidVisibleFireNcnnRuntime : VisibleFireNcnnRuntime {
    override fun loadLibrary() = System.loadLibrary("visible_fire_ncnn")
    override fun create(paramPath: String, binPath: String): Long =
        VisibleFireNcnnBridge.create(paramPath, binPath)

    override fun infer(handle: Long, input: ByteBuffer): FloatArray =
        VisibleFireNcnnBridge.infer(handle, input)

    override fun close(handle: Long) = VisibleFireNcnnBridge.close(handle)
}

internal object VisibleFireNcnnBridge {
    external fun create(paramPath: String, binPath: String): Long
    external fun infer(handle: Long, input: ByteBuffer): FloatArray
    external fun close(handle: Long)
}

internal class NcnnVisibleFireDetector(
    private val manifest: VisibleFireModelManifest,
    private val runtime: VisibleFireNcnnRuntime,
    private val handle: Long,
) : VisibleFireDetector {
    private val preprocessor = VisibleRgbaTensorPreprocessor(manifest)
    private val postprocessor = VisibleYoloPostprocessor(manifest, preprocessor)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun detect(frame: VisibleRgbaFrame): VisibleDetectionResult = mutex.withLock {
        check(!closed.get()) { "Visible detector is closed" }
        val prepared = preprocessor.prepare(frame)
        val output = runtime.infer(handle, prepared.nchw)
        VisibleDetectionResult(
            frameCapturedAtMillis = frame.capturedAtMillis,
            detections = postprocessor.process(output, prepared),
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.close(handle)
    }
}

internal data class VisiblePreparedInput(
    val nchw: ByteBuffer,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** Reuses one direct NCHW tensor and converts an existing RGBA frame without Bitmap allocation. */
internal class VisibleRgbaTensorPreprocessor(private val manifest: VisibleFireModelManifest) {
    private val pixelCount = manifest.inputWidth * manifest.inputHeight
    private val nchw = ByteBuffer.allocateDirect(pixelCount * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    fun prepare(frame: VisibleRgbaFrame): VisiblePreparedInput {
        val scale = minOf(manifest.inputWidth.toFloat() / frame.width, manifest.inputHeight.toFloat() / frame.height)
        val padX = (manifest.inputWidth - frame.width * scale) / 2f
        val padY = (manifest.inputHeight - frame.height * scale) / 2f
        nchw.clear()
        for (y in 0 until manifest.inputHeight) {
            for (x in 0 until manifest.inputWidth) {
                val sourceX = ((x - padX) / scale).toInt()
                val sourceY = ((y - padY) / scale).toInt()
                val position = y * manifest.inputWidth + x
                if (sourceX in 0 until frame.width && sourceY in 0 until frame.height) {
                    val pixelOffset = (sourceY * frame.width + sourceX) * 4
                    putRgb(
                        position,
                        frame.pixels[pixelOffset].toInt() and 0xff,
                        frame.pixels[pixelOffset + 1].toInt() and 0xff,
                        frame.pixels[pixelOffset + 2].toInt() and 0xff,
                    )
                } else {
                    putRgb(position, 114, 114, 114)
                }
            }
        }
        nchw.rewind()
        return VisiblePreparedInput(nchw, frame.width, frame.height)
    }

    private fun putRgb(position: Int, red: Int, green: Int, blue: Int) {
        val scale = manifest.normalizationScale
        nchw.putFloat(position * Float.SIZE_BYTES, red * scale)
        nchw.putFloat((pixelCount + position) * Float.SIZE_BYTES, green * scale)
        nchw.putFloat((2 * pixelCount + position) * Float.SIZE_BYTES, blue * scale)
    }

    fun mapToSource(
        prepared: VisiblePreparedInput,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
    ): VisibleDetection? = VisibleBoxMapper.mapToSource(
        modelWidth = manifest.inputWidth,
        modelHeight = manifest.inputHeight,
        sourceWidth = prepared.sourceWidth,
        sourceHeight = prepared.sourceHeight,
        centerX = centerX,
        centerY = centerY,
        width = width,
        height = height,
    )
}

internal class VisibleYoloPostprocessor(
    private val manifest: VisibleFireModelManifest,
    private val preprocessor: VisibleRgbaTensorPreprocessor,
) {
    fun process(output: FloatArray, input: VisiblePreparedInput): List<VisibleDetection> {
        val candidateCount = manifest.candidateCount
        require(output.size == manifest.outputChannels * candidateCount) {
            "Unexpected visible YOLO output length ${output.size}"
        }
        val candidates = ArrayList<VisibleDetection>()
        for (index in 0 until candidateCount) {
            val classIndex = manifest.classNames.indices.maxBy { output[(4 + it) * candidateCount + index] }
            val confidence = output[(4 + classIndex) * candidateCount + index]
            if (confidence < manifest.confidenceThreshold) continue
            val mapped = preprocessor.mapToSource(
                input,
                centerX = output[index],
                centerY = output[candidateCount + index],
                width = output[2 * candidateCount + index],
                height = output[3 * candidateCount + index],
            ) ?: continue
            candidates += mapped.copy(
                confidence = confidence,
                classIndex = classIndex,
                className = manifest.classNames[classIndex],
            )
        }
        candidates.sortByDescending(VisibleDetection::confidence)
        return candidates.fold(mutableListOf()) { kept, candidate ->
            if (kept.none {
                    it.classIndex == candidate.classIndex &&
                        intersectionOverUnion(it, candidate) > manifest.iouThreshold
                }
            ) {
                kept += candidate
            }
            kept
        }
    }

    private fun intersectionOverUnion(first: VisibleDetection, second: VisibleDetection): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        return if (intersection == 0f) 0f else intersection / (firstArea + secondArea - intersection)
    }
}

private fun JsonObject.string(name: String): String = get(name).asString
private fun JsonObject.int(name: String): Int = get(name).asInt
private fun JsonObject.float(name: String): Float = get(name).asFloat
private fun JsonObject.boolean(name: String): Boolean = get(name).asBoolean
private fun JsonObject.objectValue(name: String): JsonObject = getAsJsonObject(name)
private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name)
private fun JsonArray.strings(): List<String> = map { it.asString }
private fun JsonArray.objects(): List<JsonObject> = map { it.asJsonObject }
