package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestVisibleFrameBufferTest {
    @Test
    fun capacityOneReleasesReplacedFrameDeterministically() {
        var released = 0
        val buffer = LatestVisibleFrameBuffer()
        buffer.offer(frame(1, onRelease = { released += 1 }))
        buffer.offer(frame(2, onRelease = { released += 1 }))

        assertEquals(1, released)
        assertEquals(2, buffer.takeLatest()!!.pixels[0].toInt())
        assertNull(buffer.takeLatest())
        assertEquals(1, buffer.snapshot().replacedFrames)
    }

    @Test
    fun ingressAcceptsOnlyVisibleRgbaAndCopiesOnlyAcceptedBytes() {
        var now = 1_000L
        val buffer = LatestVisibleFrameBuffer(admissionNowMillis = { now })
        val source = byteArrayOf(10, 20, 30, 40)

        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(
                0,
                VisibleFrameFormat.OTHER,
                source, 0, source.size, 1, 1, 10,
            ),
        )
        val generation = buffer.onSourceSwitchStarted(VisibleFrameSource.VISIBLE)
        buffer.onSourceSwitchCompleted(generation, success = true)
        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(
                generation,
                VisibleFrameFormat.RGBA_8888,
                source, 0, 3, 1, 1, 12,
            ),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)
        now += LatestVisibleFrameBuffer.SOURCE_QUARANTINE_MILLIS

        assertEquals(
            VisibleFrameOfferResult.PUBLISHED,
            buffer.offerVisibleFrame(
                generation,
                VisibleFrameFormat.RGBA_8888,
                source, 0, source.size, 1, 1, 13,
            ),
        )
        source[0] = 99
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), buffer.takeLatest()!!.pixels)
        assertEquals(1, buffer.snapshot().copiedFrames)
    }

    @Test
    fun closeReleasesOwnedFrameAndRejectsWithoutCopying() {
        var released = 0
        val buffer = LatestVisibleFrameBuffer()
        buffer.offer(frame(1, onRelease = { released += 1 }))
        buffer.close()

        assertEquals(1, released)
        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(
                0,
                VisibleFrameFormat.RGBA_8888,
                byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, 2,
            ),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)
    }

    @Test
    fun cadenceAndAtomicReservationRejectBeforeCopy() {
        var now = 1_000L
        val ready = readyVisibleBuffer(nowMillis = { now })
        val buffer = ready.buffer
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertEquals(
            VisibleFrameOfferResult.PUBLISHED,
            buffer.offerVisibleFrame(ready.generation, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now),
        )
        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(ready.generation, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now + 1),
        )
        assertEquals(1, buffer.snapshot().copiedFrames)
        assertEquals(1, buffer.snapshot().cadenceRejectedFrames)

        now += LatestVisibleFrameBuffer.ADMISSION_INTERVAL_MILLIS
        assertEquals(
            VisibleFrameOfferResult.PUBLISHED,
            buffer.offerVisibleFrame(ready.generation, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now),
        )
        assertEquals(2, buffer.snapshot().copiedFrames)
    }

    @Test
    fun closeWinningBeforeCopyReservationPreventsAllocation() {
        val reserved = CountDownLatch(1)
        val continueOffer = CountDownLatch(1)
        val ready = readyVisibleBuffer(
            nowMillis = { 1_000L },
            hooks = LatestVisibleFrameBufferHooks(
                afterAdmissionReserved = {
                    reserved.countDown()
                    assertTrue(continueOffer.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val buffer = ready.buffer
        val executor = Executors.newSingleThreadExecutor()
        val offered = executor.submit<VisibleFrameOfferResult> {
            buffer.offerVisibleFrame(
                ready.generation,
                VisibleFrameFormat.RGBA_8888,
                byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, 1_000,
            )
        }
        assertTrue(reserved.await(5, TimeUnit.SECONDS))
        buffer.close()
        continueOffer.countDown()

        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            offered.get(5, TimeUnit.SECONDS),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)
        assertEquals(0, buffer.snapshot().acceptedFrames)
        executor.shutdownNow()
    }

    @Test
    fun sourceSwitchWinningBeforeCopyReservationPreventsAllocation() {
        val reserved = CountDownLatch(1)
        val continueOffer = CountDownLatch(1)
        val ready = readyVisibleBuffer(
            nowMillis = { 1_000L },
            hooks = LatestVisibleFrameBufferHooks(
                afterAdmissionReserved = {
                    reserved.countDown()
                    assertTrue(continueOffer.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val buffer = ready.buffer
        val executor = Executors.newSingleThreadExecutor()
        val offered = executor.submit<VisibleFrameOfferResult> {
            buffer.offerVisibleFrame(
                ready.generation,
                VisibleFrameFormat.RGBA_8888,
                byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, 1_000,
            )
        }
        assertTrue(reserved.await(5, TimeUnit.SECONDS))
        buffer.onSourceSwitchStarted(VisibleFrameSource.THERMAL)
        continueOffer.countDown()

        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            offered.get(5, TimeUnit.SECONDS),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)
        assertEquals(0, buffer.snapshot().acceptedFrames)
        executor.shutdownNow()
    }

    @Test
    fun closeAfterCopyPermissionCopiesThenDiscardsAndReleasesExactlyOnce() {
        assertCopyingWinnerIsDiscardedExactlyOnce { buffer ->
            buffer.close()
        }
    }

    @Test
    fun sourceSwitchAfterCopyPermissionCopiesThenDiscardsAndReleasesExactlyOnce() {
        assertCopyingWinnerIsDiscardedExactlyOnce { buffer ->
            buffer.onSourceSwitchStarted(VisibleFrameSource.THERMAL)
        }
    }

    @Test
    fun visibleToThermalTransitionDrainsQueuedFrameAndRejectsCallbacksWithoutCopy() {
        var now = 1_000L
        var released = 0
        val ready = readyVisibleBuffer(nowMillis = { now })
        val buffer = ready.buffer
        buffer.offer(frame(1, onRelease = { released += 1 }))

        val thermalGeneration = buffer.onSourceSwitchStarted(VisibleFrameSource.THERMAL)
        buffer.onSourceSwitchCompleted(thermalGeneration, success = true)
        assertEquals(1, released)
        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(
                ready.generation,
                VisibleFrameFormat.RGBA_8888,
                byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, now,
            ),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)
    }

    @Test
    fun thermalToVisibleRequiresCompletedGenerationAndDrainBeforeAdmission() {
        var now = 1_000L
        val buffer = LatestVisibleFrameBuffer(admissionNowMillis = { now })
        val thermal = buffer.onSourceSwitchStarted(VisibleFrameSource.THERMAL)
        buffer.onSourceSwitchCompleted(thermal, success = true)
        val visible = buffer.onSourceSwitchStarted(VisibleFrameSource.VISIBLE)
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(visible, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now),
        )
        buffer.onSourceSwitchCompleted(visible - 1, success = true)
        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(visible, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now),
        )
        buffer.onSourceSwitchCompleted(visible, success = true)
        assertEquals(
            VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
            buffer.offerVisibleFrame(visible, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now),
        )
        now += LatestVisibleFrameBuffer.SOURCE_QUARANTINE_MILLIS
        assertEquals(
            VisibleFrameOfferResult.PUBLISHED,
            buffer.offerVisibleFrame(visible, VisibleFrameFormat.RGBA_8888, bytes, 0, 4, 1, 1, now),
        )
        assertEquals(1, buffer.snapshot().copiedFrames)
    }

    @Test
    fun delayedOldThermalSessionFramesRemainRejectedAfterVisibleQuarantineExpires() {
        var now = 1_000L
        val buffer = LatestVisibleFrameBuffer(admissionNowMillis = { now })
        val thermalGeneration = buffer.onSourceSwitchStarted(VisibleFrameSource.THERMAL)
        buffer.onSourceSwitchCompleted(thermalGeneration, success = true)
        val visibleGeneration = buffer.onSourceSwitchStarted(VisibleFrameSource.VISIBLE)
        buffer.onSourceSwitchCompleted(visibleGeneration, success = true)
        now += LatestVisibleFrameBuffer.SOURCE_QUARANTINE_MILLIS * 10
        val bytes = byteArrayOf(1, 2, 3, 4)

        repeat(100) {
            assertEquals(
                VisibleFrameOfferResult.REJECTED_BEFORE_COPY,
                buffer.offerVisibleFrame(
                    thermalGeneration,
                    VisibleFrameFormat.RGBA_8888,
                    bytes, 0, 4, 1, 1, now,
                ),
            )
        }
        assertEquals(0, buffer.snapshot().copiedFrames)
        assertEquals(
            VisibleFrameOfferResult.PUBLISHED,
            buffer.offerVisibleFrame(
                visibleGeneration,
                VisibleFrameFormat.RGBA_8888,
                bytes, 0, 4, 1, 1, now,
            ),
        )
    }

    @Test
    fun concurrentOfferTakeCloseAndMultipleProducersReleaseEveryFrameExactlyOnce() {
        val releaseCounts = Collections.synchronizedMap(mutableMapOf<Int, Int>())
        val buffer = LatestVisibleFrameBuffer()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val taker = pool.submit {
            start.await()
            repeat(100) { buffer.takeLatest()?.release() }
        }
        val closer = pool.submit {
            start.await()
            buffer.close()
        }
        val futures = (1..100).map { id ->
            pool.submit {
                start.await()
                buffer.offer(
                    frame(id, onRelease = {
                        synchronized(releaseCounts) {
                            releaseCounts[id] = (releaseCounts[id] ?: 0) + 1
                        }
                    }),
                )
            }
        }
        start.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        taker.get(5, TimeUnit.SECONDS)
        closer.get(5, TimeUnit.SECONDS)
        buffer.takeLatest()?.release()
        buffer.close()

        assertEquals((1..100).toSet(), releaseCounts.keys)
        assertTrue(releaseCounts.values.all { it == 1 })
        pool.shutdownNow()
    }

    private fun readyVisibleBuffer(
        nowMillis: () -> Long,
        hooks: LatestVisibleFrameBufferHooks = LatestVisibleFrameBufferHooks(),
    ): ReadyVisible {
        val buffer = LatestVisibleFrameBuffer(
            admissionNowMillis = nowMillis,
            sourceQuarantineMillis = 0,
            hooks = hooks,
        )
        val generation = buffer.onSourceSwitchStarted(VisibleFrameSource.VISIBLE)
        buffer.onSourceSwitchCompleted(generation, success = true)
        return ReadyVisible(buffer, generation)
    }

    private data class ReadyVisible(
        val buffer: LatestVisibleFrameBuffer,
        val generation: Long,
    )

    private fun assertCopyingWinnerIsDiscardedExactlyOnce(
        interrupt: (LatestVisibleFrameBuffer) -> Unit,
    ) {
        val copying = CountDownLatch(1)
        val continueCopy = CountDownLatch(1)
        val ready = readyVisibleBuffer(
            nowMillis = { 1_000L },
            hooks = LatestVisibleFrameBufferHooks(
                afterCopyPermissionAcquired = {
                    copying.countDown()
                    assertTrue(continueCopy.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val buffer = ready.buffer
        var displacedReleases = 0
        assertTrue(buffer.offer(frame(9, onRelease = { displacedReleases += 1 })))
        val baseline = buffer.snapshot()
        val executor = Executors.newSingleThreadExecutor()
        val offered = executor.submit<VisibleFrameOfferResult> {
            buffer.offerVisibleFrame(
                ready.generation,
                VisibleFrameFormat.RGBA_8888,
                byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, 1_000,
            )
        }
        assertTrue(copying.await(5, TimeUnit.SECONDS))
        interrupt(buffer)
        continueCopy.countDown()

        assertEquals(
            VisibleFrameOfferResult.COPIED_DISCARDED,
            offered.get(5, TimeUnit.SECONDS),
        )
        val snapshot = buffer.snapshot()
        assertEquals(baseline.copiedFrames + 1, snapshot.copiedFrames)
        assertEquals(baseline.acceptedFrames, snapshot.acceptedFrames)
        assertEquals(baseline.releasedFrames + 1, snapshot.releasedFrames)
        assertEquals(1, displacedReleases)
        assertNull(buffer.takeLatest())
        executor.shutdownNow()
    }

    private fun frame(value: Int, onRelease: () -> Unit = {}) =
        VisibleRgbaFrame(byteArrayOf(value.toByte(), 0, 0, 0), 1, 1, value.toLong(), onRelease)
}
