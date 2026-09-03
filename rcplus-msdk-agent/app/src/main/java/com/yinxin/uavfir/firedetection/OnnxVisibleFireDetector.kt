package com.yinxin.uavfir.firedetection

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.yinxin.uavfir.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.measureNanoTime

class OnnxVisibleFireDetector(
    private val context: Context,
) : VisibleFireDetectionEngine {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val inputShape = longArrayOf(1, 3, AgentFireModelSpec.INPUT_SIZE.toLong(), AgentFireModelSpec.INPUT_SIZE.toLong())
    private val outputCandidateCount = listOf(8, 16, 32).sumOf { stride ->
        val grid = AgentFireModelSpec.INPUT_SIZE / stride
        grid * grid
    }
    private val outputShape = longArrayOf(
        1,
        (4 + AgentFireModelSpec.CLASS_NAMES.size).toLong(),
        outputCandidateCount.toLong(),
    )
    private val inputValues = FloatArray(AgentFireModelSpec.INPUT_SIZE * AgentFireModelSpec.INPUT_SIZE * 3)
    private val inputBuffer = directFloatBuffer(inputValues.size)
    private val outputBuffer = directFloatBuffer((4 + AgentFireModelSpec.CLASS_NAMES.size) * outputCandidateCount)
    private val sessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadSession() }
    private val session: OrtSession by sessionDelegate
    private val inputTensorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OnnxTensor.createTensor(environment, inputBuffer, inputShape)
    }
    private val outputTensorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OnnxTensor.createTensor(environment, outputBuffer, outputShape)
    }
    private val inputTensor: OnnxTensor by inputTensorDelegate
    private val outputTensor: OnnxTensor by outputTensorDelegate

    override fun prepare() {
        session
        inputTensor
        outputTensor
    }

    @Synchronized
    override fun detect(rgba: ByteArray, width: Int, height: Int): VisibleInferenceResult {
        val letterbox = RgbaLetterboxPreprocessor.preprocess(
            rgba,
            width,
            height,
            reusableTensor = inputValues,
        )
        inputBuffer.clear()
        inputBuffer.put(inputValues)
        inputBuffer.rewind()
        outputBuffer.clear()
        lateinit var detections: List<VisibleDetection>
        val elapsedNs = measureNanoTime {
            session.run(
                mapOf("images" to inputTensor),
                mapOf("output0" to outputTensor),
            ).use {
                detections = YoloV8Postprocessor.decode(
                    channels = outputBuffer,
                    candidateCount = outputCandidateCount,
                    letterbox = letterbox,
                )
            }
        }
        return VisibleInferenceResult(detections, elapsedNs / 1_000_000L)
    }

    @Synchronized
    override fun close() {
        if (outputTensorDelegate.isInitialized()) {
            outputTensor.close()
        }
        if (inputTensorDelegate.isInitialized()) {
            inputTensor.close()
        }
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
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(BuildConfig.AGENT_FIRE_INTRA_OP_THREADS.coerceAtLeast(1))
            options.setInterOpNumThreads(BuildConfig.AGENT_FIRE_INTER_OP_THREADS.coerceAtLeast(1))
            environment.createSession(modelBytes, options).also { loaded ->
                require(loaded.inputNames.contains("images")) { "onnx-input-images-missing" }
                require(loaded.outputNames.contains("output0")) { "onnx-output-output0-missing" }
            }
        } finally {
            options.close()
        }
    }

    private fun directFloatBuffer(elementCount: Int) = ByteBuffer
        .allocateDirect(elementCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
}
