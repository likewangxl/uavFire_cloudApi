package com.yinxin.uavfir.firedetection

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceFireDetectionCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sequentialFramesReuseOwnedRgbaBuffer() = runTest {
        val firstProcessed = CountDownLatch(1)
        val secondProcessed = CountDownLatch(1)
        val received = CopyOnWriteArrayList<ByteArray>()
        val engine = object : VisibleFireDetectionEngine {
            override fun detect(rgba: ByteArray, width: Int, height: Int): VisibleInferenceResult {
                received += rgba
                return VisibleInferenceResult(emptyList(), inferenceMs = 1)
            }
        }
        val coordinator = OnDeviceFireDetectionCoordinator(
            scope = this,
            enabled = true,
            reporter = VisibleDetectionReporter { },
            engineFactory = { engine },
            calibrationSink = FireCalibrationSink.NO_OP,
            observationSink = FireDetectionObservationSink { observation ->
                when (observation.sourceTs) {
                    1L -> firstProcessed.countDown()
                    2L -> secondProcessed.countDown()
                }
            },
            inferenceIntervalMs = 0,
        )
        coordinator.start("M300-001")

        fun awaitProcessing(latch: CountDownLatch): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (latch.count > 0 && System.nanoTime() < deadline) {
                runCurrent()
                Thread.sleep(1)
            }
            // The observation is published on the coordinator scope immediately
            // before drainFrames recycles the owned buffer. Running the scheduler
            // once more makes that recycle deterministic before the next frame.
            runCurrent()
            return latch.count == 0L
        }

        coordinator.onVisibleRgbaFrame(byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, 1)
        assertTrue(awaitProcessing(firstProcessed))
        coordinator.onVisibleRgbaFrame(byteArrayOf(5, 6, 7, 8), 0, 4, 1, 1, 2)
        assertTrue(awaitProcessing(secondProcessed))
        advanceUntilIdle()

        assertSame(received[0], received[1])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stop_dropsResultFromInferenceAlreadyInFlight() = runTest {
        val inferenceStarted = CountDownLatch(1)
        val releaseInference = CountDownLatch(1)
        val observations = CopyOnWriteArrayList<FireDetectionObservation>()
        val engine = object : VisibleFireDetectionEngine {
            override fun detect(rgba: ByteArray, width: Int, height: Int): VisibleInferenceResult {
                inferenceStarted.countDown()
                releaseInference.await(2, TimeUnit.SECONDS)
                return VisibleInferenceResult(emptyList(), inferenceMs = 10)
            }
        }
        val coordinator = OnDeviceFireDetectionCoordinator(
            scope = this,
            enabled = true,
            reporter = VisibleDetectionReporter { },
            engineFactory = { engine },
            calibrationSink = FireCalibrationSink.NO_OP,
            observationSink = FireDetectionObservationSink(observations::add),
            inferenceIntervalMs = 0,
        )

        coordinator.start("M300-001")
        coordinator.onVisibleRgbaFrame(
            data = byteArrayOf(0, 0, 0, 0),
            offset = 0,
            length = 4,
            width = 1,
            height = 1,
            timestampMs = 1,
        )
        runCurrent()
        assertTrue(inferenceStarted.await(2, TimeUnit.SECONDS))

        coordinator.stop("M300-001")
        releaseInference.countDown()
        advanceUntilIdle()

        assertFalse(observations.last().active)
    }
}
