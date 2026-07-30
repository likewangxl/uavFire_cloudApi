package com.yinxin.uavfir.firedetection

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleRgbaBgrGoldenTest {
    private val manifest = VisibleFireModelManifestParser.parse(
        File("src/main/assets/fire-detection/model-manifest.json").readText(),
    )
    private val fixture = JsonParser.parseReader(
        File("../../test-fixtures/visible-fire-color-bgr.json").reader(),
    ).asJsonObject

    @Test
    fun androidRgbaTensorMatchesOpenCvBgrChannelMeaning() {
        val bgr = fixture.getAsJsonArray("tensorPixelBgr").map { it.asInt }
        val prepared = VisibleRgbaTensorPreprocessor(manifest).prepare(
            VisibleRgbaFrame(
                byteArrayOf(bgr[2].toByte(), bgr[1].toByte(), bgr[0].toByte(), 255.toByte()),
                1, 1, 1,
            ),
        )
        val center = 480 * 960 + 480
        val plane = 960 * 960

        assertEquals(bgr[2] / 255f, prepared.nchw.getFloat(center * 4), 0.000001f)
        assertEquals(bgr[1] / 255f, prepared.nchw.getFloat((plane + center) * 4), 0.000001f)
        assertEquals(bgr[0] / 255f, prepared.nchw.getFloat((plane * 2 + center) * 4), 0.000001f)
    }

    @Test
    fun fireColorDecisionMatchesPythonOpenCvBgrBaseline() {
        val thresholds = fixture.getAsJsonObject("thresholds")
        fixture.getAsJsonArray("cases").forEach { element ->
            val case = element.asJsonObject
            val bgr = case.getAsJsonArray("bgr").map { it.asInt }
            val actual = bgr[2] >= thresholds.get("redMin").asInt &&
                bgr[1] >= thresholds.get("greenMin").asInt &&
                bgr[0] <= thresholds.get("blueMax").asInt &&
                bgr[2] >= bgr[1]
            assertEquals(case.get("name").asString, case.get("expected").asBoolean, actual)
        }
    }
}
