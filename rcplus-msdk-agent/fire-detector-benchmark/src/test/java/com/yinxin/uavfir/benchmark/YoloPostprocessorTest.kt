package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloPostprocessorTest {
    @Test
    fun process_appliesPerClassConfidenceThresholdAndClassAwareNmsAt960() {
        val output = FloatArray(6 * 18900)
        putCandidate(output, index = 0, cx = 480f, cy = 480f, width = 480f, height = 480f, fire = 0.90f, smoke = 0.10f)
        putCandidate(output, index = 1, cx = 482f, cy = 482f, width = 480f, height = 480f, fire = 0.80f, smoke = 0.10f)
        putCandidate(output, index = 2, cx = 480f, cy = 480f, width = 480f, height = 480f, fire = 0.10f, smoke = 0.85f)
        putCandidate(output, index = 3, cx = 80f, cy = 80f, width = 80f, height = 80f, fire = 0.24f, smoke = 0.10f)
        val preprocessor = RgbaTensorPreprocessor(testManifest())
        val input = preprocessor.prepare(RgbaFrame(ByteArray(960 * 960 * 4), 960, 960, 1L))

        val detections = YoloPostprocessor(testManifest(), preprocessor).process(output, input)

        assertEquals(2, detections.size)
        assertEquals(listOf(0, 1), detections.map(Detection::classIndex))
        assertEquals(listOf(0.90f, 0.85f), detections.map(Detection::confidence))
    }

    private fun putCandidate(
        output: FloatArray,
        index: Int,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        fire: Float,
        smoke: Float,
    ) {
        output[index] = cx
        output[18900 + index] = cy
        output[2 * 18900 + index] = width
        output[3 * 18900 + index] = height
        output[4 * 18900 + index] = fire
        output[5 * 18900 + index] = smoke
    }
}
