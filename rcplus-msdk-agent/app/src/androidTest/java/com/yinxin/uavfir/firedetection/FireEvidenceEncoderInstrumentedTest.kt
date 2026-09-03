package com.yinxin.uavfir.firedetection

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FireEvidenceEncoderInstrumentedTest {
    @Test
    fun encodedEvidenceBurnsDetectionBoxIntoJpeg() {
        val width = 200
        val height = 120
        val rgba = ByteArray(width * height * 4) { 0xff.toByte() }
        val evidence = FireEvidenceEncoder.encodeRgba(
            rgba = rgba,
            width = width,
            height = height,
            detections = listOf(
                VisibleDetection(
                    classId = 0,
                    label = "fire",
                    confidence = 0.82,
                    roi = NormalizedRoi(x = 0.20, y = 0.25, width = 0.50, height = 0.50),
                ),
            ),
        )

        val decoded = BitmapFactory.decodeByteArray(evidence.jpeg, 0, evidence.jpeg.size)
        var redPixels = 0
        for (y in 25..95) {
            for (x in 35..45) {
                val pixel = decoded.getPixel(x, y)
                val red = pixel shr 16 and 0xff
                val green = pixel shr 8 and 0xff
                val blue = pixel and 0xff
                if (red >= 150 && green <= 140 && blue <= 140) redPixels += 1
            }
        }
        decoded.recycle()

        assertTrue("expected a red fire annotation border in encoded JPEG", redPixels >= 20)
    }
}
