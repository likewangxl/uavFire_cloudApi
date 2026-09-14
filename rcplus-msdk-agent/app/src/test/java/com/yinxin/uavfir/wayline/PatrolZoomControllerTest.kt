package com.yinxin.uavfir.wayline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatrolZoomControllerTest {
    private class Camera : PatrolZoomPort {
        var height: Double? = 30.0
        var pitch: Double? = -45.0
        var ratio = 1.0
        var supported = PatrolZoomRange(true, listOf(1.0, 3.0, 7.0, 56.0))
        var visible = true
        var acknowledge = true
        val writes = mutableListOf<Double>()
        override suspend fun relativeHeightM() = height
        override suspend fun pitchDegrees() = pitch
        override suspend fun range() = supported
        override suspend fun currentRatio() = ratio
        override suspend fun prepareVisibleZoom() = visible
        override suspend fun setRatio(ratio: Double) {
            writes += ratio
            if (acknowledge) this.ratio = ratio
        }
    }

    @Test fun scalesFractionalHeightsAndClampsToLensRange() {
        val range = PatrolZoomRange(true, listOf(1.0, 10.0))
        for ((height, expected) in listOf(30.0 to 2.0, 35.0 to 2.3, 40.0 to 2.7,
            60.0 to 4.0, 5.0 to 1.0, 300.0 to 10.0)) {
            assertEquals(expected, range.target(height)!!, 0.001)
        }
        assertNull(range.target(Double.NaN))
        assertNull(range.target(Double.POSITIVE_INFINITY))
        assertNull(range.target(0.0))
        assertNull(range.target(-5.0))
        assertNull(PatrolZoomRange(true, emptyList()).target(30.0))
    }

    @Test fun discreteLensUsesSupportedGear() {
        val range = PatrolZoomRange(false, listOf(1.0, 2.0, 4.0, 8.0))
        assertEquals(2.0, range.target(40.0)!!, 0.001)
        assertEquals(4.0, range.target(60.0)!!, 0.001)
    }

    @Test fun appliesReadsBackAndAvoidsRepeatedCommands() = runTest {
        val camera = Camera().apply { height = 35.0 }
        val controller = PatrolZoomController(camera)
        assertTrue(controller.tick().startsWith("applied"))
        assertEquals("stable", controller.tick())
        camera.height = 36.0
        assertEquals("stable", controller.tick())
        camera.height = 40.0
        assertTrue(controller.tick().startsWith("applied"))
        assertEquals(listOf(2.3, 2.7), camera.writes)
    }

    @Test fun unavailableHeightWrongPitchOrThermalSourceDoesNotWrite() = runTest {
        val camera = Camera()
        val controller = PatrolZoomController(camera)
        camera.height = null
        assertEquals("height-unavailable", controller.tick())
        camera.height = 30.0
        camera.pitch = 0.0
        assertEquals("waiting-for-pitch-minus45", controller.tick())
        camera.pitch = -45.0
        camera.visible = false
        assertEquals("camera-owned-by-other-source", controller.tick())
        assertTrue(camera.writes.isEmpty())
    }

    @Test fun externalResetIsRepairedAtSameHeight() = runTest {
        val camera = Camera()
        val controller = PatrolZoomController(camera)
        controller.tick()
        camera.ratio = 1.0
        controller.tick()
        assertEquals(listOf(2.0, 2.0), camera.writes)
    }

    @Test fun readbackFailureIsNotReportedAsSuccessAndCanRetry() = runTest {
        val camera = Camera().apply { acknowledge = false }
        val controller = PatrolZoomController(camera)
        try {
            controller.tick()
            fail("Expected readback mismatch")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("zoom-readback-mismatch"))
        }
        camera.acknowledge = true
        assertTrue(controller.tick().startsWith("applied"))
    }

    @Test fun cancellationDuringReadbackDoesNotApplyAnotherCommand() = runTest {
        val camera = Camera().apply { acknowledge = false }
        val controller = PatrolZoomController(camera)
        val job = launch { controller.tick() }
        runCurrent()
        assertEquals(listOf(2.0), camera.writes)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(2.0), camera.writes)
        camera.acknowledge = true
        controller.reset()
        assertTrue(controller.tick().startsWith("applied"))
    }
}
