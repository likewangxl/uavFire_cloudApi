package com.yinxin.uavfir.firedetection

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnVisibleFireDetectorLifecycleTest {
    @Test
    fun closeWaitsForInFlightDetectDeletesOnceAndFutureDetectGetsTypedFailure() {
        val runtime = BlockingRuntime()
        val manifest = VisibleFireModelManifestParser.parse(
            File("src/main/assets/fire-detection/model-manifest.json").readText(),
        )
        val detector = NcnnVisibleFireDetector(manifest, runtime, 17L)
        val detectFailure = AtomicReference<Throwable?>()
        val detectThread = thread(start = true, name = "visible-detect") {
            detectFailure.set(
                runCatching {
                    runBlocking {
                        detector.detect(
                            VisibleRgbaFrame(
                                pixels = byteArrayOf(1, 2, 3, 4),
                                width = 1,
                                height = 1,
                                capturedAtMillis = 10L,
                            ),
                        )
                    }
                }.exceptionOrNull(),
            )
        }
        assertTrue(runtime.inferEntered.await(5, TimeUnit.SECONDS))

        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeThread = thread(start = true, name = "visible-close") {
            closeStarted.countDown()
            detector.close()
            closeFinished.countDown()
        }
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        assertFalse(closeFinished.await(250, TimeUnit.MILLISECONDS))
        assertTrue(runtime.closeCount.get() == 0)

        runtime.allowInferToFinish.countDown()
        detectThread.join(5_000)
        closeThread.join(5_000)
        assertFalse(detectThread.isAlive)
        assertFalse(closeThread.isAlive)
        assertTrue(detectFailure.get() == null)
        assertTrue(runtime.closeCount.get() == 1)

        detector.close()
        assertTrue(runtime.closeCount.get() == 1)
        val closedFailure = runCatching {
            runBlocking {
                detector.detect(
                    VisibleRgbaFrame(byteArrayOf(1, 2, 3, 4), 1, 1, 11L),
                )
            }
        }.exceptionOrNull()
        assertTrue(closedFailure is VisibleFireDetectionFailure.Closed)
        assertTrue(runtime.inferCount.get() == 1)
    }

    private class BlockingRuntime : VisibleFireNcnnRuntime {
        val inferEntered = CountDownLatch(1)
        val allowInferToFinish = CountDownLatch(1)
        val inferCount = AtomicInteger()
        val closeCount = AtomicInteger()

        override fun loadLibrary() = Unit
        override fun create(paramPath: String, binPath: String): Long = 17L

        override fun infer(handle: Long, input: ByteBuffer): FloatArray {
            inferCount.incrementAndGet()
            inferEntered.countDown()
            check(allowInferToFinish.await(5, TimeUnit.SECONDS))
            return FloatArray(6 * 18_900)
        }

        override fun close(handle: Long) {
            closeCount.incrementAndGet()
        }
    }
}
