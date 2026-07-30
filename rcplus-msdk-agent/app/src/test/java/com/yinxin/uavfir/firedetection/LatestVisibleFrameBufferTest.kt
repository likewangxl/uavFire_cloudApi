package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val buffer = LatestVisibleFrameBuffer()
        val source = byteArrayOf(10, 20, 30, 40)

        assertFalse(
            buffer.offerVisibleFrame(
                VisibleFrameSource.THERMAL, VisibleFrameFormat.RGBA_8888,
                source, 0, source.size, 1, 1, 10,
            ),
        )
        assertFalse(
            buffer.offerVisibleFrame(
                VisibleFrameSource.VISIBLE, VisibleFrameFormat.OTHER,
                source, 0, source.size, 1, 1, 11,
            ),
        )
        assertFalse(
            buffer.offerVisibleFrame(
                VisibleFrameSource.VISIBLE, VisibleFrameFormat.RGBA_8888,
                source, 0, 3, 1, 1, 12,
            ),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)

        assertTrue(
            buffer.offerVisibleFrame(
                VisibleFrameSource.VISIBLE, VisibleFrameFormat.RGBA_8888,
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
        assertFalse(
            buffer.offerVisibleFrame(
                VisibleFrameSource.VISIBLE, VisibleFrameFormat.RGBA_8888,
                byteArrayOf(1, 2, 3, 4), 0, 4, 1, 1, 2,
            ),
        )
        assertEquals(0, buffer.snapshot().copiedFrames)
    }

    private fun frame(value: Int, onRelease: () -> Unit = {}) =
        VisibleRgbaFrame(byteArrayOf(value.toByte(), 0, 0, 0), 1, 1, value.toLong(), onRelease)
}
