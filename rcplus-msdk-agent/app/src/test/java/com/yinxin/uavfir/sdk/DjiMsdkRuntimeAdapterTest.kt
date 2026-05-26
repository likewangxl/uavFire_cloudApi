package com.yinxin.uavfir.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiMsdkRuntimeAdapterTest {
    @Test
    fun initialize_returnsTrueWhenRegistrationSucceeds() = runTest {
        val adapter = DjiMsdkRuntimeAdapter(
            sdkClient = FakeMsdkSdkClient(
                initSucceeds = true,
                registerSucceeds = true,
            ),
            keyValueClient = FakeMsdkKeyValueClient(),
        )

        val initialized = adapter.initialize()

        assertTrue(initialized)
    }

    @Test
    fun initialize_returnsFalseWhenInitFails() = runTest {
        val adapter = DjiMsdkRuntimeAdapter(
            sdkClient = FakeMsdkSdkClient(
                initSucceeds = false,
                registerSucceeds = false,
            ),
            keyValueClient = FakeMsdkKeyValueClient(),
        )

        val initialized = adapter.initialize()

        assertFalse(initialized)
    }

    @Test
    fun readsConnectionAndCapabilityFromKeyValueClient() = runTest {
        val adapter = DjiMsdkRuntimeAdapter(
            sdkClient = FakeMsdkSdkClient(
                initSucceeds = true,
                registerSucceeds = true,
            ),
            keyValueClient = FakeMsdkKeyValueClient(
                aircraftConnected = true,
                visibleSupported = true,
                thermalSupported = true,
            ),
        )

        adapter.initialize()

        assertTrue(adapter.isAircraftConnected())
        assertEquals(
            CameraCapability(
                visibleSupported = true,
                thermalSupported = true,
            ),
            adapter.loadCapability(),
        )
        assertEquals("MATRICE 4T", adapter.loadAircraftModel())
        assertEquals(DjiFlightLimit(heightLimitMeters = 120), adapter.loadFlightLimit())
    }

    private class FakeMsdkSdkClient(
        private val initSucceeds: Boolean,
        private val registerSucceeds: Boolean,
    ) : MsdkSdkClient {
        override suspend fun initialize(): Boolean = initSucceeds

        override suspend fun registerApp(): Boolean = registerSucceeds
    }

    private class FakeMsdkKeyValueClient(
        private val aircraftConnected: Boolean = false,
        private val visibleSupported: Boolean = false,
        private val thermalSupported: Boolean = false,
    ) : MsdkKeyValueClient {
        override fun isAircraftConnected(): Boolean = aircraftConnected

        override fun loadCapability(): CameraCapability = CameraCapability(
            visibleSupported = visibleSupported,
            thermalSupported = thermalSupported,
        )

        override fun loadAircraftModel(): String? = "MATRICE 4T"

        override fun loadFlightLimit(): DjiFlightLimit = DjiFlightLimit(heightLimitMeters = 120)
    }
}
