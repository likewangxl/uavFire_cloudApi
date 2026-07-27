package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineNormalizerTest {
    @Test
    fun normalize_usesActualNonSquareSourceDimensions() {
        val detection = BaselineNormalizer.normalize(
            xyxy = floatArrayOf(95.8f, 76.4f, 479f, 382f),
            sourceWidth = 958,
            sourceHeight = 764,
            confidence = 0.9f,
        )

        assertEquals(0.1f, detection.left, 0.0001f)
        assertEquals(0.1f, detection.top, 0.0001f)
        assertEquals(0.5f, detection.right, 0.0001f)
        assertEquals(0.5f, detection.bottom, 0.0001f)
    }
}
