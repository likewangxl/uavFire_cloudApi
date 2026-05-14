package com.yinxin.uavfir.stream

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class DjiMsdkStreamBinderSourceTest {
    @Test
    fun focusVisible_resetsThermalDisplayModeToVisualOnly() {
        val source = String(Files.readAllBytes(
            Paths.get("src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt"),
        ))
        val focusVisibleBody = source.substringAfter("override suspend fun focusVisible")
            .substringBefore("override suspend fun focusThermal")

        assertTrue(
            "focusVisible must reset Pilot2 side-by-side thermal PIP back to visual-only mode",
            focusVisibleBody.contains("ThermalDisplayMode.VISUAL_ONLY"),
        )
        assertTrue(
            "focusVisible must switch to a visible stream source before disabling thermal PIP",
            focusVisibleBody.indexOf("preferredVisibleSource()") <
                focusVisibleBody.indexOf("resetThermalDisplayModeToVisualOnly()"),
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
}
