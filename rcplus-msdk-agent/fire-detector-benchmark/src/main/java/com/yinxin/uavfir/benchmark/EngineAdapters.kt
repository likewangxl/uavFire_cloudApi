package com.yinxin.uavfir.benchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal class OnnxEngineAdapter(context: Context) : EngineAdapter {
    override val engine = Engine.ONNX
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(
        BenchmarkAssetStore.copy(context, ONNX_ASSET).absolutePath,
        OrtSession.SessionOptions(),
    )

    override fun infer(frame: RgbaFrame): List<Detection> {
        val input = RgbaTensorPreprocessor.prepare(frame)
        OnnxTensor.createTensor(environment, input.nchw, longArrayOf(1, 3, 640, 640)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = (results[0].value as Array<Array<FloatArray>>)[0]
                return YoloPostprocessor.process(output.flatMap(FloatArray::asIterable).toFloatArray(), input)
            }
        }
    }

    override fun close() {
        session.close()
    }
}

internal class TfliteEngineAdapter(context: Context) : EngineAdapter {
    override val engine = Engine.TFLITE
    private val interpreter = Interpreter(BenchmarkAssetStore.map(context, TFLITE_ASSET))

    override fun infer(frame: RgbaFrame): List<Detection> {
        val input = RgbaTensorPreprocessor.prepare(frame)
        val output = Array(1) { Array(5) { FloatArray(8400) } }
        interpreter.run(input.nhwc, output)
        return YoloPostprocessor.process(output[0].flatMap(FloatArray::asIterable).toFloatArray(), input)
    }

    override fun close() {
        interpreter.close()
    }
}

/**
 * The ncnn runtime is a JNI runtime. The release build must package the official arm64-v8a bridge
 * before this adapter can be measured; the production app never references this benchmark-only API.
 */
internal class NcnnEngineAdapter(context: Context) : EngineAdapter {
    override val engine = Engine.NCNN
    private val handle: Long

    init {
        try {
            System.loadLibrary("fire_detector_ncnn")
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "NCNN benchmark bridge is not provisioned. Expected arm64-v8a libncnn.so and " +
                    "libfire_detector_ncnn.so under fire-detector-benchmark/src/main/jniLibs; see README.md.",
                error,
            )
        }
        handle = NcnnBridge.create(
            BenchmarkAssetStore.copy(context, NCNN_PARAM_ASSET).absolutePath,
            BenchmarkAssetStore.copy(context, NCNN_BIN_ASSET).absolutePath,
        )
        check(handle != 0L) { "NCNN bridge rejected the benchmark model files" }
    }

    override fun infer(frame: RgbaFrame): List<Detection> {
        val input = RgbaTensorPreprocessor.prepare(frame)
        return YoloPostprocessor.process(NcnnBridge.infer(handle, input.nchw), input)
    }

    override fun close() {
        NcnnBridge.close(handle)
    }
}

private object NcnnBridge {
    external fun create(paramPath: String, binPath: String): Long
    external fun infer(handle: Long, input: java.nio.ByteBuffer): FloatArray
    external fun close(handle: Long)
}

internal object BenchmarkAssetStore {
    fun copy(context: Context, asset: String): File {
        val destination = File(context.cacheDir, "fire-detector-benchmark/$asset")
        if (destination.isFile && destination.length() > 0L) return destination
        destination.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }
        return destination
    }

    fun map(context: Context, asset: String): MappedByteBuffer = FileInputStream(BenchmarkAssetStore.copy(context, asset))
        .channel
        .use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
}

internal const val ONNX_ASSET = "thermal-fire-yolov8n-640-gt-20260709.onnx"
internal const val TFLITE_ASSET = "thermal-fire-yolov8n-640-gt-20260709_float32.tflite"
internal const val NCNN_PARAM_ASSET = "thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model.ncnn.param"
internal const val NCNN_BIN_ASSET = "thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model.ncnn.bin"
