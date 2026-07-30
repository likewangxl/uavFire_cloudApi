package com.yinxin.uavfir.firedetection

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
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
    fun nativeCreateLinkageFailureReturnsTypedFailure() {
        val runtime = RecordingRuntime(failCreate = UnsatisfiedLinkError("missing create symbol"))

        val result = create(runtime)

        assertTrue(result is VisibleFireDetectorArmingResult.Failed)
        assertTrue(
            (result as VisibleFireDetectorArmingResult.Failed).failure is
                VisibleFireDetectorArmingFailure.NativeLoadFailed,
        )
        assertTrue(runtime.libraryLoaded.get())
        assertTrue(runtime.sessionOpened.get())
    }

    @Test
    fun nativeCreateDoesNotSwallowFatalVmErrors() {
        val fatal = OutOfMemoryError("fixture fatal error")
        val runtime = RecordingRuntime(failCreate = fatal)

        val escaped = try {
            create(runtime)
            null
        } catch (error: OutOfMemoryError) {
            error
        }

        assertTrue(escaped === fatal)
    }

    @Test
    fun nativeCreateSecurityFailureReturnsTypedSessionFailure() {
        val runtime = RecordingRuntime(failCreate = SecurityException("native access denied"))

        val result = create(runtime)

        assertTrue(result is VisibleFireDetectorArmingResult.Failed)
        assertTrue(
            (result as VisibleFireDetectorArmingResult.Failed).failure is
                VisibleFireDetectorArmingFailure.SessionOpenFailed,
        )
    }

    @Test
    fun atomicMoveFallbackFailureReturnsTypedAssetIoFailure() {
        val runtime = RecordingRuntime()
        val calls = AtomicInteger()
        val mover = VisibleFireFileMover { _, _, options ->
            if (calls.getAndIncrement() == 0) {
                throw AtomicMoveNotSupportedException("source", "target", "fixture")
            }
            assertFalse(options.contains(StandardCopyOption.ATOMIC_MOVE))
            throw IOException("fallback failed")
        }

        val result = create(runtime, mover)

        assertTrue(result is VisibleFireDetectorArmingResult.Failed)
        assertTrue(
            (result as VisibleFireDetectorArmingResult.Failed).failure is
                VisibleFireDetectorArmingFailure.AssetIoFailed,
        )
        assertFalse(runtime.libraryLoaded.get())
        assertFalse(runtime.sessionOpened.get())
        assertTrue(calls.get() == 2)
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

    @Test
    fun concurrentFactoriesPublishOneImmutableContentAddressedDirectory() {
        val stagingRoot = temporaryFolder.newFolder()
        val runtime = RecordingRuntime()
        val binReadersReady = CountDownLatch(2)
        val allowBinRead = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<VisibleFireDetectorArmingResult>())
        val workers = List(2) {
            thread(start = true, name = "visible-factory-$it") {
                results += VisibleFireDetectorFactory.createForTesting(
                    enabled = true,
                    manifestJson = productionManifest(),
                    openAsset = { path ->
                        if (path.endsWith(".bin")) {
                            binReadersReady.countDown()
                            check(allowBinRead.await(5, TimeUnit.SECONDS))
                        }
                        productionAsset(path).inputStream()
                    },
                    stagingDirectory = stagingRoot,
                    runtime = runtime,
                )
            }
        }
        assertTrue(binReadersReady.await(5, TimeUnit.SECONDS))
        allowBinRead.countDown()
        workers.forEach { it.join(15_000) }

        assertTrue(workers.none(Thread::isAlive))
        assertTrue(results.size == 2 && results.all { it is VisibleFireDetectorArmingResult.Armed })
        assertTrue(runtime.sessionOpenCount.get() == 2)
        assertTrue(runtime.openedModelDirectories.toSet().size == 1)
        val modelDirectory = runtime.openedModelDirectories.first()
        assertTrue(modelDirectory.parentFile?.name == "models")
        assertTrue(modelDirectory.name.startsWith("visible-fire-wechat-best2-20260728-"))
        assertTrue(
            stagingRoot.walkTopDown().none {
                it.name.startsWith(".verify-") || it.name.endsWith(".tmp")
            },
        )
        results.filterIsInstance<VisibleFireDetectorArmingResult.Armed>().forEach { it.detector.close() }
        assertTrue(runtime.sessionCloseCount.get() == 2)
    }

    private fun create(
        runtime: RecordingRuntime,
        mover: VisibleFireFileMover = VisibleFireFileMover.SYSTEM,
    ): VisibleFireDetectorArmingResult =
        VisibleFireDetectorFactory.createForTesting(
            enabled = true,
            manifestJson = productionManifest(),
            openAsset = { path -> productionAsset(path).inputStream() },
            stagingDirectory = temporaryFolder.newFolder(),
            runtime = runtime,
            fileMover = mover,
        )

    private fun productionManifest(): String =
        File("src/main/assets/fire-detection/model-manifest.json").readText()

    private fun productionAsset(path: String): File = File("src/main/assets", path)

    private class RecordingRuntime(
        private val failLoad: Boolean = false,
        private val failCreate: Throwable? = null,
    ) : VisibleFireNcnnRuntime {
        val libraryLoaded = AtomicBoolean(false)
        val sessionOpened = AtomicBoolean(false)
        val sessionClosed = AtomicBoolean(false)
        val sessionOpenCount = AtomicInteger()
        val sessionCloseCount = AtomicInteger()
        val openedModelDirectories = Collections.synchronizedList(mutableListOf<File>())
        private val nextHandle = AtomicLong(7L)

        override fun loadLibrary() {
            libraryLoaded.set(true)
            if (failLoad) throw UnsatisfiedLinkError("missing JNI")
        }

        override fun create(paramPath: String, binPath: String): Long {
            sessionOpened.set(true)
            sessionOpenCount.incrementAndGet()
            openedModelDirectories += File(paramPath).parentFile
            failCreate?.let { throw it }
            return nextHandle.getAndIncrement()
        }

        override fun infer(handle: Long, input: ByteBuffer): FloatArray = FloatArray(6 * 18_900)

        override fun close(handle: Long) {
            sessionClosed.set(true)
            sessionCloseCount.incrementAndGet()
        }
    }
}
