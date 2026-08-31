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
        assertEquals("森林灭火 M300-01", adapter.loadAircraftName())
        assertEquals("MATRICE 4T", adapter.loadAircraftModel())
        assertEquals(DjiFlightLimit(heightLimitMeters = 120), adapter.loadFlightLimit())
        assertEquals(DjiDeviceIdentity("RC-001", "AIRCRAFT-001"), adapter.loadDeviceIdentity())
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
        private val identity: DjiDeviceIdentity? = DjiDeviceIdentity("RC-001", "AIRCRAFT-001"),
    ) : MsdkKeyValueClient {
        override fun isAircraftConnected(): Boolean = aircraftConnected

        override fun loadCapability(): CameraCapability = CameraCapability(
            visibleSupported = visibleSupported,
            thermalSupported = thermalSupported,
        )

        override fun loadAircraftName(): String? = "森林灭火 M300-01"

        override fun loadAircraftModel(): String? = "MATRICE 4T"

        override fun loadFlightLimit(): DjiFlightLimit = DjiFlightLimit(heightLimitMeters = 120)

        override suspend fun loadDeviceIdentity(): DjiDeviceIdentity? = identity
    }
}
