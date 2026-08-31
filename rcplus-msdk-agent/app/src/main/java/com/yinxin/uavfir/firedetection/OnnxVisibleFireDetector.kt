package com.yinxin.uavfir.firedetection

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.system.measureNanoTime

class OnnxVisibleFireDetector(
    private val context: Context,
) : VisibleFireDetectionEngine {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadSession() }
    private val session: OrtSession by sessionDelegate

    override fun prepare() {
        session
    }

    override fun detect(rgba: ByteArray, width: Int, height: Int): VisibleInferenceResult {
        val letterbox = RgbaLetterboxPreprocessor.preprocess(rgba, width, height)
        lateinit var detections: List<VisibleDetection>
        val elapsedNs = measureNanoTime {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(letterbox.tensor),
                longArrayOf(1, 3, AgentFireModelSpec.INPUT_SIZE.toLong(), AgentFireModelSpec.INPUT_SIZE.toLong()),
            ).use { input ->
                session.run(mapOf("images" to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val batch = result[0].value as Array<Array<FloatArray>>
                    require(batch.size == 1) { "unexpected-output-batch=${batch.size}" }
                    detections = YoloV8Postprocessor.decode(batch[0], letterbox)
                }
            }
        }
        return VisibleInferenceResult(detections, elapsedNs / 1_000_000L)
    }

    override fun close() {
        if (sessionDelegate.isInitialized()) {
            session.close()
        }
    }

    private fun loadSession(): OrtSession {
        val modelBytes = context.assets.open(AgentFireModelSpec.ASSET_PATH).use { it.readBytes() }
        val actualSha = MessageDigest.getInstance("SHA-256")
            .digest(modelBytes)
            .joinToString("") { "%02x".format(it) }
        require(actualSha == AgentFireModelSpec.MODEL_SHA256) {
            "onnx-model-sha256-mismatch expected=${AgentFireModelSpec.MODEL_SHA256} actual=$actualSha"
        }
        val options = OrtSession.SessionOptions()
        return try {
            environment.createSession(modelBytes, options).also { loaded ->
                require(loaded.inputNames.contains("images")) { "onnx-input-images-missing" }
                require(loaded.outputNames.contains("output0")) { "onnx-output-output0-missing" }
            }
        } finally {
            options.close()
        }
    }
}
