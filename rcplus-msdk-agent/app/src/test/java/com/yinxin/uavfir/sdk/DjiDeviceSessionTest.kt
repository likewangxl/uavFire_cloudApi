package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.session.AgentConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DjiDeviceSessionTest {
    @Test
    fun initialize_returnsCapabilityReadyWhenSdkInitializedAndAircraftConnected() = runTest {
        val capability = CameraCapability(
            visibleSupported = true,
            thermalSupported = true,
        )
        val session = DjiDeviceSession(
            djiSdkGateway = FakeDjiSdkGateway(
                initializeResult = true,
                connected = true,
                capability = capability,
            ),
        )

        val state = session.initialize()

        assertEquals(AgentConnectionState.CAPABILITY_READY, state.connectionState)
        assertEquals(capability, state.capability)
    }

    @Test
    fun initialize_returnsSdkReadyWhenSdkInitializedButAircraftNotConnected() = runTest {
        val session = DjiDeviceSession(
            djiSdkGateway = FakeDjiSdkGateway(
                initializeResult = true,
                connected = false,
            ),
        )

        val state = session.initialize()

        assertEquals(AgentConnectionState.SDK_READY, state.connectionState)
        assertNull(state.capability)
    }

    @Test
    fun initialize_returnsErrorWhenSdkInitializationFails() = runTest {
        val session = DjiDeviceSession(
            djiSdkGateway = FakeDjiSdkGateway(
                initializeResult = false,
                connected = false,
            ),
        )

        val state = session.initialize()

        assertEquals(AgentConnectionState.ERROR, state.connectionState)
        assertNull(state.capability)
    }

    @Test
    fun initialize_serializesConcurrentCallsAgainstSharedGateway() = runTest {
        val gateway = BlockingDjiSdkGateway()
        val session = DjiDeviceSession(djiSdkGateway = gateway)

        val first = async { session.initialize() }
        gateway.awaitInitializeEntered()
        val second = async { session.initialize() }
        gateway.releaseNextInitialize()
        gateway.awaitSecondInitializeEntered()
        gateway.releaseNextInitialize()

        val firstState = first.await()
        val secondState = second.await()

        assertEquals(AgentConnectionState.CAPABILITY_READY, firstState.connectionState)
        assertEquals(AgentConnectionState.CAPABILITY_READY, secondState.connectionState)
        assertEquals(1, gateway.maxConcurrentInitializeCalls)
        assertTrue(gateway.initializeCalls >= 2)
    }

    private class FakeDjiSdkGateway(
        private val initializeResult: Boolean,
        private val connected: Boolean,
        private val capability: CameraCapability = CameraCapability(
            visibleSupported = false,
            thermalSupported = false,
        ),
    ) : DjiSdkGateway {
        override suspend fun initialize(): Boolean = initializeResult

        override suspend fun isAircraftConnected(): Boolean = connected

        override suspend fun loadCapability(): CameraCapability = capability
    }

    private class BlockingDjiSdkGateway : DjiSdkGateway {
        private val capability = CameraCapability(
            visibleSupported = true,
            thermalSupported = true,
        )
        private val initializeEntered = CompletableDeferred<Unit>()
        private val secondInitializeEntered = CompletableDeferred<Unit>()
        private var currentInitializeCalls = 0
        var maxConcurrentInitializeCalls = 0
            private set
        var initializeCalls = 0
            private set
        private var releaseSignal = CompletableDeferred<Unit>()

        override suspend fun initialize(): Boolean {
            initializeCalls += 1
            currentInitializeCalls += 1
            if (!initializeEntered.isCompleted) {
                initializeEntered.complete(Unit)
            }
            if (initializeCalls >= 2 && !secondInitializeEntered.isCompleted) {
                secondInitializeEntered.complete(Unit)
            }
            if (currentInitializeCalls > maxConcurrentInitializeCalls) {
                maxConcurrentInitializeCalls = currentInitializeCalls
            }
            releaseSignal.await()
            currentInitializeCalls -= 1
            releaseSignal = CompletableDeferred()
            return true
        }

        override suspend fun isAircraftConnected(): Boolean = true

        override suspend fun loadCapability(): CameraCapability = capability

        suspend fun awaitInitializeEntered() {
            initializeEntered.await()
        }

        suspend fun awaitSecondInitializeEntered() {
            secondInitializeEntered.await()
        }

        fun releaseNextInitialize() {
            releaseSignal.complete(Unit)
        }
    }
}
