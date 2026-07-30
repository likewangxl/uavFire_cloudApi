package com.yinxin.uavfir.firedetection

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleInferenceLoopTest {
    @Test
    fun targetCadenceIsFiveFramesPerSecond() {
        assertEquals(5, VisibleInferenceLoop.TARGET_FPS)
        assertEquals(200L, VisibleInferenceLoop.TARGET_INTERVAL_MILLIS)
    }

    @Test
    fun runningLoopStartsAtMostFiveInferencesInOneSecond() = runTest {
        lateinit var buffer: LatestVisibleFrameBuffer
        var capturedAt = 0L
        val detector = RecordingDetector {
            capturedAt += VisibleInferenceLoop.TARGET_INTERVAL_MILLIS
            buffer.offer(frame(capturedAt))
        }
        buffer = LatestVisibleFrameBuffer().apply { offer(frame(0)) }
        val loop = VisibleInferenceLoop(buffer, detector, nowMillis = { currentTime })

        loop.start(this)
        advanceTimeBy(999)

        assertEquals(5, detector.calls.get())
        loop.close()
    }

    @Test
    fun staleFrameAboveThreeHundredMillisIsRejectedAndReleased() = runTest {
        var released = 0
        val detector = RecordingDetector()
        val buffer = LatestVisibleFrameBuffer().apply {
            offer(frame(capturedAt = 699, onRelease = { released += 1 }))
        }
        val loop = VisibleInferenceLoop(buffer, detector, nowMillis = { 1_000 })

        assertTrue(loop.processLatest())

        assertEquals(0, detector.calls.get())
        assertEquals(1, released)
        assertEquals(1, loop.snapshot().staleRejectedFrames)
        assertEquals(301L, loop.snapshot().lastFrameAgeMillis)
    }

    @Test
    fun exactlyThreeHundredMillisOldFrameMayRunAndPublishesTimestamps() = runTest {
        var now = 1_000L
        val detector = RecordingDetector(onDetect = { now = 1_020 })
        val buffer = LatestVisibleFrameBuffer().apply { offer(frame(capturedAt = 700)) }
        val loop = VisibleInferenceLoop(buffer, detector, nowMillis = { now })

        assertTrue(loop.processLatest())

        val health = loop.snapshot()
        assertEquals(1, detector.calls.get())
        assertEquals(700L, health.lastCapturedAtMillis)
        assertEquals(1_000L, health.lastStartedAtMillis)
        assertEquals(1_020L, health.lastCompletedAtMillis)
        assertEquals(300L, health.lastFrameAgeMillis)
        assertEquals(VisibleInferenceStatus.HEALTHY, health.status)
    }

    @Test
    fun concurrentCallsNeverOverlapInferenceAndLeaveLatestFrameForNextCycle() = runTest {
        val entered = CompletableDeferred<Unit>()
        val releaseDetector = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val detector = RecordingDetector {
            val count = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, count) }
            entered.complete(Unit)
            releaseDetector.await()
            active.decrementAndGet()
        }
        val buffer = LatestVisibleFrameBuffer().apply { offer(frame(1_000)) }
        val loop = VisibleInferenceLoop(buffer, detector, nowMillis = { 1_000 })

        val first = async { loop.processLatest() }
        runCurrent()
        entered.await()
        buffer.offer(frame(1_001))
        assertFalse(loop.processLatest())
        releaseDetector.complete(Unit)
        assertTrue(first.await())
        assertTrue(loop.processLatest())

        assertEquals(1, maxActive.get())
        assertEquals(2, detector.calls.get())
        assertEquals(1, loop.snapshot().busySkippedCycles)
    }

    @Test
    fun detectorFailureReleasesFrameAndPublishesDegradedHealth() = runTest {
        var released = 0
        val buffer = LatestVisibleFrameBuffer().apply {
            offer(frame(1_000, onRelease = { released += 1 }))
        }
        val detector = RecordingDetector { error("synthetic") }
        val loop = VisibleInferenceLoop(buffer, detector, nowMillis = { 1_000 })

        assertTrue(loop.processLatest())

        assertEquals(1, released)
        assertEquals(1, loop.snapshot().failedInferences)
        assertEquals(VisibleInferenceStatus.DEGRADED, loop.snapshot().status)
    }

    @Test
    fun startAndCloseAreLinearizableWhenCloseWaitsForJobPublication() {
        val publicationEntered = CountDownLatch(1)
        val continuePublication = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loop = VisibleInferenceLoop(
            LatestVisibleFrameBuffer(),
            RecordingDetector(),
            hooks = VisibleInferenceLoopHooks(
                beforeJobPublication = {
                    publicationEntered.countDown()
                    assertTrue(continuePublication.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val executor = Executors.newFixedThreadPool(2)
        val started = executor.submit { loop.start(scope) }
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))
        val closed = executor.submit { loop.close() }
        continuePublication.countDown()
        started.get(5, TimeUnit.SECONDS)
        closed.get(5, TimeUnit.SECONDS)

        assertEquals(VisibleInferenceStatus.CLOSED, loop.snapshot().status)
        assertFalse(loop.hasLiveJobForTesting())
        assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.children.none { it.isActive })
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        executor.shutdownNow()
    }

    @Test
    fun closeBeforeStartRejectsStartWithoutPublishingAJob() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loop = VisibleInferenceLoop(LatestVisibleFrameBuffer(), RecordingDetector())
        loop.close()

        val failure = runCatching { loop.start(scope) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(loop.hasLiveJobForTesting())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun closeDuringDetectKeepsClosedTerminalAfterDetectReturns() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val buffer = LatestVisibleFrameBuffer().apply { offer(frame(1_000)) }
        val loop = VisibleInferenceLoop(
            buffer,
            RecordingDetector {
                entered.complete(Unit)
                release.await()
            },
            nowMillis = { 1_000 },
        )
        val processing = async { loop.processLatest() }
        runCurrent()
        entered.await()
        loop.close()
        release.complete(Unit)
        processing.await()

        assertEquals(VisibleInferenceStatus.CLOSED, loop.snapshot().status)
        assertFalse(loop.snapshot().inFlight)
    }

    @Test
    fun concurrentBusyUpdatesAreAtomicAndClosedRemainsTerminal() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val buffer = LatestVisibleFrameBuffer().apply { offer(frame(1_000)) }
        val loop = VisibleInferenceLoop(
            buffer,
            RecordingDetector {
                entered.complete(Unit)
                release.await()
            },
            nowMillis = { 1_000 },
        )
        val processing = async { loop.processLatest() }
        runCurrent()
        entered.await()
        val attempts = (1..100).map { async(Dispatchers.Default) { loop.processLatest() } }
        attempts.forEach { assertFalse(it.await()) }
        loop.close()
        release.complete(Unit)
        processing.await()

        assertEquals(100L, loop.snapshot().busySkippedCycles)
        assertEquals(VisibleInferenceStatus.CLOSED, loop.snapshot().status)
    }

    private fun frame(capturedAt: Long, onRelease: () -> Unit = {}) =
        VisibleRgbaFrame(byteArrayOf(1, 2, 3, 4), 1, 1, capturedAt, onRelease)

    private class RecordingDetector(
        private val onDetect: suspend () -> Unit = {},
    ) : VisibleFireDetector {
        val calls = AtomicInteger()

        override suspend fun detect(frame: VisibleRgbaFrame): VisibleDetectionResult {
            calls.incrementAndGet()
            onDetect()
            return VisibleDetectionResult(frame.capturedAtMillis, emptyList())
        }

        override fun close() = Unit
    }
}
