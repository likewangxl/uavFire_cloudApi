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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class OnnxEngineAdapter(context: Context, manifest: ModelManifest) : EngineAdapter {
    override val engine = Engine.ONNX
    private val preprocessor = RgbaTensorPreprocessor(manifest)
    private val postprocessor = YoloPostprocessor(manifest, preprocessor)
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(
        BenchmarkAssetStore.copyVerified(context, manifest.artifact(engine).single()).absolutePath,
        OrtSession.SessionOptions(),
    )

    override fun infer(frame: RgbaFrame): List<Detection> {
        val input = preprocessor.prepare(frame)
        OnnxTensor.createTensor(environment, input.nchw, longArrayOf(1, 3, 640, 640)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = (results[0].value as Array<Array<FloatArray>>)[0]
                return postprocessor.process(output, input)
            }
        }
    }

    override fun close() = session.close()
}

internal class TfliteEngineAdapter(context: Context, manifest: ModelManifest) : EngineAdapter {
    override val engine = Engine.TFLITE
    private val preprocessor = RgbaTensorPreprocessor(manifest)
    private val postprocessor = YoloPostprocessor(manifest, preprocessor)
    private val output = Array(1) { Array(5) { FloatArray(8400) } }
    private val interpreter = Interpreter(BenchmarkAssetStore.map(context, manifest.artifact(engine).single()))

    override fun infer(frame: RgbaFrame): List<Detection> {
        val input = preprocessor.prepare(frame)
        interpreter.run(input.nhwc, output)
        return postprocessor.process(output[0], input)
    }

    override fun close() = interpreter.close()
}

internal class NcnnEngineAdapter(context: Context, manifest: ModelManifest) : EngineAdapter {
    override val engine = Engine.NCNN
    private val preprocessor = RgbaTensorPreprocessor(manifest)
    private val postprocessor = YoloPostprocessor(manifest, preprocessor)
    private val handle: Long

    init {
        try {
            System.loadLibrary("fire_detector_ncnn")
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("NCNN bridge is absent. Provision the checksum-recorded SDK and build the JNI bridge as documented in README.md.", error)
        }
        val artifacts = manifest.artifact(engine)
        val param = artifacts.single { it.path.endsWith(".param") }
        val bin = artifacts.single { it.path.endsWith(".bin") }
        handle = NcnnBridge.create(
            BenchmarkAssetStore.copyVerified(context, param).absolutePath,
            BenchmarkAssetStore.copyVerified(context, bin).absolutePath,
        )
        check(handle != 0L) { "NCNN bridge rejected the verified benchmark model files" }
    }

    override fun infer(frame: RgbaFrame): List<Detection> {
        val input = preprocessor.prepare(frame)
        return postprocessor.process(NcnnBridge.infer(handle, input.nchw), input)
    }

    override fun close() = NcnnBridge.close(handle)
}

private object NcnnBridge {
    external fun create(paramPath: String, binPath: String): Long
    external fun infer(handle: Long, input: java.nio.ByteBuffer): FloatArray
    external fun close(handle: Long)
}

internal object BenchmarkAssetStore {
    fun copyVerified(context: Context, artifact: ModelArtifact): File {
        val destination = File(context.cacheDir, "fire-detector-benchmark/${artifact.path}")
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        context.assets.open(artifact.path).use { input ->
            temporary.outputStream().use { output ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                check(actualSha256 == artifact.sha256) {
                    "SHA-256 mismatch for ${artifact.path}"
                }
            }
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return destination
    }

    fun map(context: Context, artifact: ModelArtifact): MappedByteBuffer = FileInputStream(copyVerified(context, artifact))
        .channel.use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
}
