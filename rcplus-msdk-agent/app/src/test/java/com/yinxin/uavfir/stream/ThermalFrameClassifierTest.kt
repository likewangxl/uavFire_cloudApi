package com.yinxin.uavfir.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalFrameClassifierTest {
    @Test
    fun looksLikeThermalFrame_acceptsHighContrastMonochromeFrame() {
        val frame = rgbaFrame(width = 16, height = 12, background = Triple(40, 40, 40))
        fillRect(frame, width = 16, xRange = 6 until 10, yRange = 4 until 8, color = Triple(240, 240, 240))

        assertTrue(ThermalFrameClassifier.looksLikeThermalFrame(frame, 0, frame.size, width = 16, height = 12))
    }

    @Test
    fun looksLikeThermalFrame_rejectsColorVisibleFrame() {
        val frame = rgbaFrame(width = 16, height = 12, background = Triple(40, 120, 220))
        fillRect(frame, width = 16, xRange = 6 until 10, yRange = 4 until 8, color = Triple(250, 180, 80))

        assertFalse(ThermalFrameClassifier.looksLikeThermalFrame(frame, 0, frame.size, width = 16, height = 12))
    }

    private fun rgbaFrame(width: Int, height: Int, background: Triple<Int, Int, Int>): ByteArray {
        val frame = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val offset = i * 4
            frame[offset] = background.first.toByte()
            frame[offset + 1] = background.second.toByte()
            frame[offset + 2] = background.third.toByte()
            frame[offset + 3] = 255.toByte()
        }
        return frame
    }

    private fun fillRect(
        frame: ByteArray,
        width: Int,
        xRange: IntRange,
        yRange: IntRange,
        color: Triple<Int, Int, Int>,
    ) {
        for (y in yRange) {
            for (x in xRange) {
                val offset = (y * width + x) * 4
                frame[offset] = color.first.toByte()
                frame[offset + 1] = color.second.toByte()
                frame[offset + 2] = color.third.toByte()
            }
        }
    }
}
