package com.yinxin.uavfir.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiSdkGatewayImplTest {
    @Test
    fun gateway_readsValuesFromRuntimeAdapter() = runTest {
        val capability = CameraCapability(
            visibleSupported = true,
            thermalSupported = true,
        )
        val gateway = DjiSdkGatewayImpl(
            runtimeAdapter = FakeDjiRuntimeAdapter(
                initializeResult = true,
                connected = true,
                capability = capability,
            ),
        )

        assertTrue(gateway.initialize())
        assertTrue(gateway.isAircraftConnected())
        assertEquals(capability, gateway.loadCapability())
    }

    @Test
    fun gateway_preservesNegativeValuesFromRuntimeAdapter() = runTest {
        val gateway = DjiSdkGatewayImpl(
            runtimeAdapter = FakeDjiRuntimeAdapter(
                initializeResult = false,
                connected = false,
                capability = CameraCapability(
                    visibleSupported = false,
                    thermalSupported = false,
                ),
            ),
        )

        assertFalse(gateway.initialize())
        assertFalse(gateway.isAircraftConnected())
        assertFalse(gateway.loadCapability().thermalSupported)
    }

    private class FakeDjiRuntimeAdapter(
        private val initializeResult: Boolean,
        private val connected: Boolean,
        private val capability: CameraCapability,
    ) : DjiRuntimeAdapter {
        override suspend fun initialize(): Boolean = initializeResult

        override suspend fun isAircraftConnected(): Boolean = connected

        override suspend fun loadCapability(): CameraCapability = capability
    }
}
