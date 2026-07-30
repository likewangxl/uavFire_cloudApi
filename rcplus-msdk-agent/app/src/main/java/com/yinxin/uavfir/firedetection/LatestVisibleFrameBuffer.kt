package com.yinxin.uavfir.firedetection

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class VisibleFrameSource {
    VISIBLE,
    THERMAL,
    UNKNOWN,
}

enum class VisibleFrameFormat {
    RGBA_8888,
    OTHER,
}

fun interface VisibleFrameOffer {
    fun offerVisibleFrame(
        source: VisibleFrameSource,
        format: VisibleFrameFormat,
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        capturedAtMillis: Long,
    ): Boolean

    companion object {
        val NO_OP = VisibleFrameOffer { _, _, _, _, _, _, _, _ -> false }
    }
}

data class LatestVisibleFrameBufferMetrics(
    val acceptedFrames: Long,
    val rejectedFrames: Long,
    val copiedFrames: Long,
    val replacedFrames: Long,
    val consumedFrames: Long,
    val releasedFrames: Long,
)

/**
 * Lock-free capacity-one handoff between the MSDK callback and inference loop.
 *
 * A successful offer transfers ownership. Replacing, closing, or consuming and
 * later processing a frame has a single explicit release point.
 */
class LatestVisibleFrameBuffer : VisibleFrameOffer, AutoCloseable {
    private sealed interface Slot {
        data object Empty : Slot
        data object Closed : Slot
        data class Frame(val value: VisibleRgbaFrame) : Slot
    }

    private val slot = AtomicReference<Slot>(Slot.Empty)
    private val acceptedFrames = AtomicLong()
    private val rejectedFrames = AtomicLong()
    private val copiedFrames = AtomicLong()
    private val replacedFrames = AtomicLong()
    private val consumedFrames = AtomicLong()
    private val releasedFrames = AtomicLong()

    override fun offerVisibleFrame(
        source: VisibleFrameSource,
        format: VisibleFrameFormat,
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        capturedAtMillis: Long,
    ): Boolean {
        val expectedLength = width.toLong() * height.toLong() * RGBA_BYTES
        if (source != VisibleFrameSource.VISIBLE ||
            format != VisibleFrameFormat.RGBA_8888 ||
            width <= 0 ||
            height <= 0 ||
            capturedAtMillis < 0L ||
            expectedLength <= 0L ||
            expectedLength > Int.MAX_VALUE ||
            offset < 0 ||
            length < expectedLength ||
            offset.toLong() + expectedLength > frameData.size
        ) {
            rejectedFrames.incrementAndGet()
            return false
        }
        if (slot.get() === Slot.Closed) {
            rejectedFrames.incrementAndGet()
            return false
        }

        val pixels = frameData.copyOfRange(offset, offset + expectedLength.toInt())
        copiedFrames.incrementAndGet()
        return offer(
            VisibleRgbaFrame(
                pixels = pixels,
                width = width,
                height = height,
                capturedAtMillis = capturedAtMillis,
                onRelease = { releasedFrames.incrementAndGet() },
            ),
        )
    }

    fun offer(frame: VisibleRgbaFrame): Boolean {
        while (true) {
            val current = slot.get()
            if (current === Slot.Closed) {
                rejectedFrames.incrementAndGet()
                frame.release()
                return false
            }
            if (!slot.compareAndSet(current, Slot.Frame(frame))) continue
            acceptedFrames.incrementAndGet()
            if (current is Slot.Frame) {
                replacedFrames.incrementAndGet()
                current.value.release()
            }
            return true
        }
    }

    fun takeLatest(): VisibleRgbaFrame? {
        while (true) {
            val current = slot.get()
            if (current !is Slot.Frame) return null
            if (!slot.compareAndSet(current, Slot.Empty)) continue
            consumedFrames.incrementAndGet()
            return current.value
        }
    }

    fun snapshot() = LatestVisibleFrameBufferMetrics(
        acceptedFrames = acceptedFrames.get(),
        rejectedFrames = rejectedFrames.get(),
        copiedFrames = copiedFrames.get(),
        replacedFrames = replacedFrames.get(),
        consumedFrames = consumedFrames.get(),
        releasedFrames = releasedFrames.get(),
    )

    override fun close() {
        val previous = slot.getAndSet(Slot.Closed)
        if (previous is Slot.Frame) {
            previous.value.release()
        }
    }

    private companion object {
        const val RGBA_BYTES = 4L
    }
}
