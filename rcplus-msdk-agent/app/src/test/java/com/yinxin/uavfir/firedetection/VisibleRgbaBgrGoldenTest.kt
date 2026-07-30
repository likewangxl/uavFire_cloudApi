package com.yinxin.uavfir.firedetection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleRgbaBgrGoldenTest {
    private val manifest = VisibleFireModelManifestParser.parse(
        File("src/main/assets/fire-detection/model-manifest.json").readText(),
    )

    @Test
    fun androidRgbaTensorMatchesOpenCvBgrChannelMeaning() {
        // OpenCV BGR golden pixel [10, 80, 220] is MSDK RGBA [220, 80, 10, 255].
        val prepared = VisibleRgbaTensorPreprocessor(manifest).prepare(
            VisibleRgbaFrame(
                byteArrayOf(220.toByte(), 80, 10, 255.toByte()),
                1, 1, 1,
            ),
        )
        val center = 480 * 960 + 480
        val plane = 960 * 960

        assertEquals(220f / 255f, prepared.nchw.getFloat(center * 4), 0.000001f)
        assertEquals(80f / 255f, prepared.nchw.getFloat((plane + center) * 4), 0.000001f)
        assertEquals(10f / 255f, prepared.nchw.getFloat((plane * 2 + center) * 4), 0.000001f)
    }

    @Test
    fun fireColorDecisionMatchesPythonOpenCvBgrBaseline() {
        assertTrue(VisibleFireColor.isFireColoredRgba(220, 80, 10))
        assertTrue(VisibleFireColor.isFireColoredRgba(180, 180, 120))
        assertFalse(VisibleFireColor.isFireColoredRgba(179, 100, 10))
        assertFalse(VisibleFireColor.isFireColoredRgba(220, 79, 10))
        assertFalse(VisibleFireColor.isFireColoredRgba(220, 100, 121))
        assertFalse(VisibleFireColor.isFireColoredRgba(100, 120, 10))
    }
}
