package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloPostprocessorTest {
    @Test
    fun process_appliesConfidenceThresholdAndNms() {
        val output = FloatArray(5 * 8400)
        putCandidate(output, index = 0, cx = 320f, cy = 320f, width = 320f, height = 320f, confidence = 0.90f)
        putCandidate(output, index = 1, cx = 322f, cy = 322f, width = 320f, height = 320f, confidence = 0.80f)
        putCandidate(output, index = 2, cx = 80f, cy = 80f, width = 80f, height = 80f, confidence = 0.24f)
        val preprocessor = RgbaTensorPreprocessor(testManifest())
        val input = preprocessor.prepare(RgbaFrame(ByteArray(640 * 640 * 4), 640, 640, 1L))

        val detections = YoloPostprocessor(testManifest(), preprocessor).process(output, input)

        assertEquals(1, detections.size)
        assertEquals(0.90f, detections.single().confidence, 0.0001f)
    }

    private fun putCandidate(
        output: FloatArray,
        index: Int,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        confidence: Float,
    ) {
        output[index] = cx
        output[8400 + index] = cy
        output[2 * 8400 + index] = width
        output[3 * 8400 + index] = height
        output[4 * 8400 + index] = confidence
    }
}
