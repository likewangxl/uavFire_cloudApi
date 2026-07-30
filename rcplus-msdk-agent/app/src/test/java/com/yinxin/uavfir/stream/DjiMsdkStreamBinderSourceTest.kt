package com.yinxin.uavfir.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class DjiMsdkStreamBinderSourceTest {
    @Test
    fun focusVisible_switchesVisibleSourceWithoutWritingThermalDisplayMode() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt"),
        ))
        val focusVisibleBody = source.substringAfter("override suspend fun focusVisible")
            .substringBefore("override suspend fun focusThermal")

        assertTrue(
            "focusVisible must switch to a visible stream source",
            focusVisibleBody.contains("preferredVisibleSource()"),
        )
        assertTrue(
            "focusVisible should not write ThermalDisplayMode after switching away from infrared; MSDK rejects that key on the visible stream source",
            !focusVisibleBody.contains("resetThermalDisplayModeToVisualOnly()") &&
                !focusVisibleBody.contains("ThermalDisplayMode.VISUAL_ONLY"),
        )
    }

    @Test
    fun preferredVisibleSource_acceptsEveryExplicitRgbVisibleCategory() {
        val allowed = listOf(
            "DEFAULT_CAMERA",
            "WIDE_CAMERA",
            "ZOOM_CAMERA",
            "VISION_CAMERA",
            "RGB_CAMERA",
        )

        allowed.forEach { source ->
            assertEquals(source, selectPreferredVisibleSourceName(listOf(source)))
        }
    }

    @Test
    fun preferredVisibleSource_rejectsEveryNonRgbVisibleCategory() {
        val forbidden = listOf(
            "INFRARED_CAMERA",
            "NDVI_CAMERA",
            "MS_G_CAMERA",
            "MS_R_CAMERA",
            "MS_RE_CAMERA",
            "MS_NIR_CAMERA",
            "POINT_CLOUD_CAMERA",
            "UNKNOWN",
        )

        forbidden.forEach { source ->
            assertNull(source, selectPreferredVisibleSourceName(listOf(source)))
        }
        assertNull(selectPreferredVisibleSourceName(forbidden))
    }

    @Test
    fun preferredVisibleSource_skipsForbiddenSourcesAndPrioritizesZoom() {
        assertEquals(
            "WIDE_CAMERA",
            selectPreferredVisibleSourceName(
                listOf(
                    "NDVI_CAMERA",
                    "WIDE_CAMERA",
                ),
            ),
        )
        assertEquals(
            "ZOOM_CAMERA",
            selectPreferredVisibleSourceName(
                listOf(
                    "WIDE_CAMERA",
                    "ZOOM_CAMERA",
                    "RGB_CAMERA",
                ),
            ),
        )
    }

    @Test
    fun focusThermal_usesThermalOnlyInsteadOfSideBySidePip() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt"),
        ))
        val focusThermalBody = source.substringAfter("override suspend fun focusThermal")
            .substringBefore("override suspend fun unbindAll")

        assertTrue(
            "focusThermal should switch the liveview to pure thermal mode",
            focusThermalBody.contains("ThermalDisplayMode.THERMAL_ONLY"),
        )
        assertTrue(
            "focusThermal should not force Pilot2 into side-by-side PIP",
            !focusThermalBody.contains("ThermalPIPPosition.SIDE_BY_SIDE"),
        )
        assertTrue(
            "focusThermal should not fail early when the source range is temporarily stale after restarting livestream",
            !focusThermalBody.contains("ensureThermalSupported()"),
        )
        assertTrue(
            "focusThermal must treat thermal display mode writes as best-effort so rejected MSDK keys do not fail the infrared switch command",
            focusThermalBody.contains("runCatching") &&
                focusThermalBody.contains("focusThermal failed to set thermal-only display mode"),
        )
    }

    @Test
    fun bindThermal_reportsM4tSingleComponentLimitationInsteadOfMsdkWideLimitation() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt"),
        ))
        val bindThermalBody = source.substringAfter("override suspend fun bindThermal")
            .substringBefore("override suspend fun focusVisible")

        assertTrue(
            "bindThermal should explain the tested M4T single-gimbal/single-component limitation",
            bindThermalBody.contains("m4t-single-gimbal-only-exposes-single-component-index"),
        )
        assertTrue(
            "bindThermal should not claim the whole MSDK v5 camera stream manager lacks simultaneous stream support",
            !bindThermalBody.contains("msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding"),
        )
    }

    @Test
    fun sourceSwitchCommandsOwnGenerationAndRequireStableKeyBeforeCompleting() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt"),
        ))
        val visible = source.substringAfter("override suspend fun focusVisible")
            .substringBefore("private suspend fun resetVisibleZoomToWide")
        val thermal = source.substringAfter("override suspend fun focusThermal")
            .substringBefore("override suspend fun captureVisibleSnapshot")

        listOf(visible, thermal).forEach { body ->
            assertTrue(body.contains("thermalFrameProbe.beginSourceSwitch"))
            assertTrue(body.contains("awaitStableSource"))
            assertTrue(body.contains("thermalFrameProbe.requiresVisibleSourceBinding()"))
            assertTrue(body.contains("thermalFrameProbe.completeSourceSwitch(generation, success = true)"))
            assertTrue(
                body.indexOf("beginSourceSwitch") < body.indexOf("setValue(") &&
                    body.indexOf("awaitStableSource") < body.indexOf("completeSourceSwitch"),
            )
        }
    }
}
