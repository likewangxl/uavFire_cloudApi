package com.yinxin.uavfir.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRepositoryTest {
    @Test
    fun capabilityRepository_returnsVisibleAndThermalFlags() = runTest {
        val repo = CapabilityRepository(FakeDjiSdkGateway(visible = true, thermal = true))

        val capability = repo.load()

        assertTrue(capability.visibleSupported)
        assertTrue(capability.thermalSupported)
    }

    private class FakeDjiSdkGateway(
        private val visible: Boolean,
        private val thermal: Boolean,
    ) : DjiSdkGateway {
        override suspend fun initialize(): Boolean = true

        override suspend fun isAircraftConnected(): Boolean = true

        override suspend fun loadCapability(): CameraCapability = CameraCapability(
            visibleSupported = visible,
            thermalSupported = thermal,
        )

        override suspend fun loadAircraftModel(): String? = null

        override suspend fun loadFlightLimit(): DjiFlightLimit = DjiFlightLimit()
    }
}
