package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleBoxMapperTest {
    @Test
    fun mapsLetterboxedModelCoordinatesBackToNormalizedSourceCoordinates() {
        val box = VisibleBoxMapper.mapToSource(
            modelWidth = 960,
            modelHeight = 960,
            sourceWidth = 1920,
            sourceHeight = 1080,
            centerX = 480f,
            centerY = 480f,
            width = 480f,
            height = 270f,
        )!!

        assertEquals(0.25f, box.left, 0.0001f)
        assertEquals(0.25f, box.top, 0.0001f)
        assertEquals(0.75f, box.right, 0.0001f)
        assertEquals(0.75f, box.bottom, 0.0001f)
    }

    @Test
    fun rejectsBoxThatCollapsesOutsideSourceAfterClamping() {
        assertNull(
            VisibleBoxMapper.mapToSource(
                modelWidth = 960,
                modelHeight = 960,
                sourceWidth = 1920,
                sourceHeight = 1080,
                centerX = 480f,
                centerY = 50f,
                width = 10f,
                height = 10f,
            ),
        )
    }
}
