package com.yinxin.uavfir.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCapabilityResolverTest {
    @Test
    fun supportsFourPayloadModelsAtAllThreePositions() {
        listOf("H20", "H20T", "H30", "H30T").forEach { model ->
            (0..2).forEach { position ->
                val result = PayloadCapabilityResolver.resolve(
                    aircraftModelKey = "M300",
                    controllerModelKey = "RC_PLUS",
                    payloads = listOf(payload(model, position)),
                    m300FireClosedLoopEnabled = true,
                )
                assertEquals(position, result.selectedPayloadPositionIndex)
                assertTrue("$model@$position should be ready", result.fireClosedLoopReady)
            }
        }
    }

    @Test
    fun multipleCompatiblePayloadsRequireOperatorSelection() {
        val payloads = listOf(payload("H20T", 0), payload("H30T", 1))
        val blocked = PayloadCapabilityResolver.resolve("M300", "RC_PLUS", payloads, m300FireClosedLoopEnabled = true)
        assertFalse(blocked.fireClosedLoopReady)
        assertTrue(blocked.blockingReasons.contains("multiple-compatible-payloads-require-operator-selection"))

        val selected = PayloadCapabilityResolver.resolve("M300", "RC_PLUS", payloads, 1, true)
        assertEquals(1, selected.selectedPayloadPositionIndex)
        assertTrue(selected.fireClosedLoopReady)
    }

    @Test
    fun featureFlagAndPayloadLossFailClosed() {
        val disabled = PayloadCapabilityResolver.resolve("M300", "RC_PLUS", listOf(payload("H20", 2)))
        assertFalse(disabled.fireClosedLoopReady)
        assertFalse(PayloadCapabilityResolver.resolve("M300", "RC_PLUS", emptyList(), 2, true).fireClosedLoopReady)
    }

    @Test
    fun m300RequiresConfirmedRcPlus() {
        val result = PayloadCapabilityResolver.resolve(
            "M300",
            null,
            listOf(payload("H20T", 0)),
            m300FireClosedLoopEnabled = true,
        )
        assertFalse(result.fireClosedLoopReady)
        assertTrue(result.blockingReasons.contains("m300-requires-rc-plus"))
    }

    private fun payload(model: String, position: Int) = PayloadCapability(
        payloadModelKey = model,
        payloadPositionIndex = position,
        visibleSupported = true,
        thermalSupported = model.endsWith("T"),
        laserSupported = true,
        tapZoomSupported = true,
    )
}
