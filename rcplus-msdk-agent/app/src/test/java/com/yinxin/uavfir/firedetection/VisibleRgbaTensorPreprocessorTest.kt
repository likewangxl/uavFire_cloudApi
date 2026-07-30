package com.yinxin.uavfir.firedetection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleRgbaTensorPreprocessorTest {
    @Test
    fun smallNegativeLetterboxCoordinateUsesPaddingInsteadOfFirstSourcePixel() {
        val manifest = VisibleFireModelManifestParser.parse(
            File("src/main/assets/fire-detection/model-manifest.json").readText(),
        )
        val preprocessor = VisibleRgbaTensorPreprocessor(manifest)
        val prepared = preprocessor.prepare(
            VisibleRgbaFrame(
                pixels = byteArrayOf(
                    255.toByte(), 0, 0, 255.toByte(),
                    255.toByte(), 0, 0, 255.toByte(),
                ),
                width = 2,
                height = 1,
                capturedAtMillis = 1L,
            ),
        )

        val redAtLastPaddingRow = prepared.nchw.getFloat((239 * 960) * Float.SIZE_BYTES)
        assertEquals(114f / 255f, redAtLastPaddingRow, 0.000001f)
    }
}
