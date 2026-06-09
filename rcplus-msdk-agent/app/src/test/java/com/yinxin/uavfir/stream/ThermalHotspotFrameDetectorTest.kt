package com.yinxin.uavfir.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalHotspotFrameDetectorTest {
    private val detector = ThermalHotspotFrameDetector()

    @Test
    fun detectHotspot_returnsNormalizedRegionAroundCompactBrightArea() {
        val width = 100
        val height = 80
        val frame = rgbaFrame(width, height, background = 40)
        fillRect(frame, width, xRange = 62 until 69, yRange = 38 until 46, brightness = 245)

        val result = detector.detect(frame, offset = 0, length = frame.size, width = width, height = height)

        assertNotNull(result)
        result!!
        assertEquals(0.65, result.region.x + result.region.width / 2.0, 0.08)
        assertEquals(0.52, result.region.y + result.region.height / 2.0, 0.08)
        assertTrue(result.maxBrightness >= 240)
        assertTrue(result.detectCostMs >= 0)
    }

    @Test
    fun detectHotspot_expandsCompactHotspotToSmallMeasurementRegion() {
        val width = 200
        val height = 120
        val frame = rgbaFrame(width, height, background = 40)
        fillRect(frame, width, xRange = 120 until 124, yRange = 60 until 64, brightness = 250)

        val result = detector.detect(frame, offset = 0, length = frame.size, width = width, height = height)

        assertNotNull(result)
        result!!
        assertTrue("expected small padded ROI width, got ${result.region.width}", result.region.width in 0.06..0.08)
        assertTrue("expected small padded ROI height, got ${result.region.height}", result.region.height in 0.06..0.08)
    }

    @Test
    fun detectHotspots_returnsMultipleBrightComponentsOrderedByScore() {
        val width = 200
        val height = 120
        val frame = rgbaFrame(width, height, background = 40)
        fillRect(frame, width, xRange = 20 until 25, yRange = 90 until 95, brightness = 245)
        fillRect(frame, width, xRange = 130 until 136, yRange = 35 until 42, brightness = 250)

        val results = detector.detectHotspots(frame, offset = 0, length = frame.size, width = width, height = height)

        assertEquals(2, results.size)
        assertTrue(results[0].maxBrightness >= results[1].maxBrightness)
        assertEquals(0.665, results[0].region.x + results[0].region.width / 2.0, 0.08)
        assertEquals(0.785, results[1].region.y + results[1].region.height / 2.0, 0.10)
    }

    @Test
    fun detectHotspots_defaultsToTopThreeComponents() {
        val width = 240
        val height = 140
        val frame = rgbaFrame(width, height, background = 40)
        fillRect(frame, width, xRange = 20 until 25, yRange = 20 until 25, brightness = 250)
        fillRect(frame, width, xRange = 70 until 75, yRange = 40 until 45, brightness = 245)
        fillRect(frame, width, xRange = 130 until 135, yRange = 70 until 75, brightness = 240)
        fillRect(frame, width, xRange = 190 until 195, yRange = 100 until 105, brightness = 235)

        val results = detector.detectHotspots(frame, offset = 0, length = frame.size, width = width, height = height)

        assertEquals(3, results.size)
    }

    @Test
    fun detectHotspot_ignoresLargeBrightBackground() {
        val width = 100
        val height = 80
        val frame = rgbaFrame(width, height, background = 35)
        fillRect(frame, width, xRange = 20 until 85, yRange = 10 until 70, brightness = 235)

        val result = detector.detect(frame, offset = 0, length = frame.size, width = width, height = height)

        assertNull(result)
    }

    @Test
    fun detectHotspot_ignoresInvalidFrameBuffer() {
        val frame = rgbaFrame(width = 20, height = 20, background = 40)

        val result = detector.detect(frame, offset = 0, length = 8, width = 20, height = 20)

        assertNull(result)
    }

    private fun rgbaFrame(width: Int, height: Int, background: Int): ByteArray {
        val frame = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val offset = i * 4
            frame[offset] = background.toByte()
            frame[offset + 1] = background.toByte()
            frame[offset + 2] = background.toByte()
            frame[offset + 3] = 255.toByte()
        }
        return frame
    }

    private fun fillRect(
        frame: ByteArray,
        width: Int,
        xRange: IntRange,
        yRange: IntRange,
        brightness: Int,
    ) {
        for (y in yRange) {
            for (x in xRange) {
                val offset = (y * width + x) * 4
                frame[offset] = brightness.toByte()
                frame[offset + 1] = brightness.toByte()
                frame[offset + 2] = brightness.toByte()
            }
        }
    }
}
