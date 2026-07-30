package com.yinxin.uavfir.firedetection

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisibleFireDetectorFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun hashMismatchReturnsTypedFailureAndNeverLoadsNativeRuntimeOrOpensSession() {
        val param = "wrong param".toByteArray()
        val runtime = RecordingRuntime()
        val result = VisibleFireDetectorFactory.createForTesting(
            enabled = true,
            manifestJson = productionManifest(),
            openAsset = { path ->
                if (path.endsWith(".param")) ByteArrayInputStream(param) else productionAsset(path).inputStream()
            },
            stagingDirectory = temporaryFolder.newFolder(),
            runtime = runtime,
        )

        assertTrue(result is VisibleFireDetectorArmingResult.Failed)
        assertTrue((result as VisibleFireDetectorArmingResult.Failed).failure is VisibleFireDetectorArmingFailure.ArtifactHashMismatch)
        assertFalse(runtime.libraryLoaded.get())
        assertFalse(runtime.sessionOpened.get())
    }

    @Test
    fun nativeLoadFailureReturnsTypedFailureAfterBothAssetsWereVerified() {
        val runtime = RecordingRuntime(failLoad = true)
        val result = create(runtime)

        assertTrue(result is VisibleFireDetectorArmingResult.Failed)
        assertTrue((result as VisibleFireDetectorArmingResult.Failed).failure is VisibleFireDetectorArmingFailure.NativeLoadFailed)
        assertTrue(runtime.libraryLoaded.get())
        assertFalse(runtime.sessionOpened.get())
    }

    @Test
    fun defaultDisabledManifestNeverLoadsAssetsOrNativeRuntime() {
        val assetOpened = AtomicBoolean(false)
        val runtime = RecordingRuntime()
        val result = VisibleFireDetectorFactory.createForTesting(
            enabled = false,
            manifestJson = validManifest(),
            openAsset = {
                assetOpened.set(true)
                ByteArrayInputStream(ByteArray(0))
            },
            stagingDirectory = temporaryFolder.newFolder(),
            runtime = runtime,
        )

        assertTrue(result is VisibleFireDetectorArmingResult.Failed)
        assertTrue((result as VisibleFireDetectorArmingResult.Failed).failure is VisibleFireDetectorArmingFailure.Disabled)
        assertFalse(assetOpened.get())
        assertFalse(runtime.libraryLoaded.get())
        assertFalse(runtime.sessionOpened.get())
    }

    @Test
    fun verifiedArtifactsOpenExactlyOneNcnnSession() {
        val runtime = RecordingRuntime()
        val result = create(runtime)

        assertTrue(result is VisibleFireDetectorArmingResult.Armed)
        assertTrue(runtime.libraryLoaded.get())
        assertTrue(runtime.sessionOpened.get())
        (result as VisibleFireDetectorArmingResult.Armed).detector.close()
        assertTrue(runtime.sessionClosed.get())
    }

    private fun create(runtime: RecordingRuntime): VisibleFireDetectorArmingResult =
        VisibleFireDetectorFactory.createForTesting(
            enabled = true,
            manifestJson = productionManifest(),
            openAsset = { path -> productionAsset(path).inputStream() },
            stagingDirectory = temporaryFolder.newFolder(),
            runtime = runtime,
        )

    private fun productionManifest(): String =
        File("src/main/assets/fire-detection/model-manifest.json").readText()

    private fun productionAsset(path: String): File = File("src/main/assets", path)

    private class RecordingRuntime(private val failLoad: Boolean = false) : VisibleFireNcnnRuntime {
        val libraryLoaded = AtomicBoolean(false)
        val sessionOpened = AtomicBoolean(false)
        val sessionClosed = AtomicBoolean(false)

        override fun loadLibrary() {
            libraryLoaded.set(true)
            if (failLoad) throw UnsatisfiedLinkError("missing JNI")
        }

        override fun create(paramPath: String, binPath: String): Long {
            sessionOpened.set(true)
            return 7L
        }

        override fun infer(handle: Long, input: ByteBuffer): FloatArray = FloatArray(6 * 18_900)

        override fun close(handle: Long) {
            sessionClosed.set(true)
        }
    }
}
