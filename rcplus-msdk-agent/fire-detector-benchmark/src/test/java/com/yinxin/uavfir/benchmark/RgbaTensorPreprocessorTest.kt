package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RgbaTensorPreprocessorTest {
    @Test
    fun prepare_writesRgbValuesToNhwcAndNchwWithoutBitmapConversion() {
        val frame = RgbaFrame(
            pixels = byteArrayOf(255.toByte(), 128.toByte(), 64, 255.toByte()),
            width = 1,
            height = 1,
            capturedAtMs = 1L,
        )

        val preprocessor = RgbaTensorPreprocessor(testManifest())
        val prepared = preprocessor.prepare(frame)

        assertEquals(1f, prepared.nhwc.getFloat(0), 0.0001f)
        assertEquals(128f / 255f, prepared.nhwc.getFloat(Float.SIZE_BYTES), 0.0001f)
        assertEquals(64f / 255f, prepared.nhwc.getFloat(Float.SIZE_BYTES * 2), 0.0001f)
        assertEquals(1f, prepared.nchw.getFloat(0), 0.0001f)
        assertEquals(128f / 255f, prepared.nchw.getFloat(640 * 640 * Float.SIZE_BYTES), 0.0001f)
        assertEquals(64f / 255f, prepared.nchw.getFloat(2 * 640 * 640 * Float.SIZE_BYTES), 0.0001f)
    }

    @Test
    fun mapToSource_removesLetterboxPaddingAndNormalizesCoordinates() {
        val preprocessor = RgbaTensorPreprocessor(testManifest())
        val prepared = preprocessor.prepare(
            RgbaFrame(ByteArray(640 * 320 * 4), width = 640, height = 320, capturedAtMs = 1L),
        )

        val detection = preprocessor.mapToSource(prepared, cx = 320f, cy = 320f, width = 320f, height = 160f)

        assertNotNull(detection)
        assertEquals(0.25f, detection!!.left, 0.0001f)
        assertEquals(0.25f, detection.top, 0.0001f)
        assertEquals(0.75f, detection.right, 0.0001f)
        assertEquals(0.75f, detection.bottom, 0.0001f)
    }

    @Test
    fun prepare_reusesTheSameDirectBuffersAcrossFrames() {
        val preprocessor = RgbaTensorPreprocessor(testManifest())
        val frame = RgbaFrame(ByteArray(640 * 640 * 4), 640, 640, 1L)

        val first = preprocessor.prepare(frame)
        val second = preprocessor.prepare(frame)

        assertEquals(System.identityHashCode(first.nhwc), System.identityHashCode(second.nhwc))
        assertEquals(System.identityHashCode(first.nchw), System.identityHashCode(second.nchw))
    }
}
