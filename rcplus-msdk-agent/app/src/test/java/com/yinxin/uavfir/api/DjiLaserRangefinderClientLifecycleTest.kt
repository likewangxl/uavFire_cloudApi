package com.yinxin.uavfir.api

import com.yinxin.uavfir.firedetection.LaserOperationBinding
import com.yinxin.uavfir.firedetection.NormalizedRoi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DjiLaserRangefinderClientLifecycleTest {
    @Test
    fun synchronousListenFailureRollsBackAndNextOperationSucceeds() = runTest {
        val access = RecordingDjiLaserHardwareAccess(listenFailures = 1)
        val client = DjiLaserRangefinderClient(access, settleMs = 0, nowMonotonicMs = { 100 })

        assertTrue(runCatching { client.beginOperation(binding(1)) }.isFailure)
        val recovered = client.beginOperation(binding(2))
        client.endOperation(recovered)

        assertEquals(2, access.listenCalls)
        assertEquals(2, access.cancelCalls)
        assertEquals(1, access.enableCalls)
        assertEquals(1, access.disableCalls)
    }

    @Test
    fun unregisterFailureStillClearsOperationAndNextOperationSucceeds() = runTest {
        val access = RecordingDjiLaserHardwareAccess(cancelFailures = 1)
        val client = DjiLaserRangefinderClient(access, settleMs = 0, nowMonotonicMs = { 100 })

        val first = client.beginOperation(binding(1))
        assertTrue(runCatching { client.endOperation(first) }.isFailure)
        val recovered = client.beginOperation(binding(2))
        client.endOperation(recovered)

        assertEquals(2, access.listenCalls)
        assertEquals(2, access.cancelCalls)
        assertEquals(2, access.enableCalls)
        assertEquals(2, access.disableCalls)
    }

    @Test
    fun cancellationDuringEnableRollsBackAndNextOperationSucceeds() = runTest {
        val enableGate = CompletableDeferred<Unit>()
        val access = RecordingDjiLaserHardwareAccess(firstEnableGate = enableGate)
        val client = DjiLaserRangefinderClient(access, settleMs = 0, nowMonotonicMs = { 100 })
        val first = launch { client.beginOperation(binding(1)) }
        runCurrent()

        first.cancelAndJoin()
        val recovered = client.beginOperation(binding(2))
        client.endOperation(recovered)

        assertEquals(2, access.listenCalls)
        assertEquals(2, access.cancelCalls)
        assertEquals(2, access.enableCalls)
        assertEquals(2, access.disableCalls)
    }

    private fun binding(operation: Long) = LaserOperationBinding(
        sessionId = "session",
        eventId = "event-$operation",
        targetRoi = NormalizedRoi(.4f, .4f, .6f, .6f),
        sourceGeneration = 7,
        operationGeneration = operation,
        windowStartedAtMonotonicMs = 0,
        windowEndsAtMonotonicMs = Long.MAX_VALUE,
    )

    private class RecordingDjiLaserHardwareAccess(
        private var listenFailures: Int = 0,
        private var cancelFailures: Int = 0,
        private val firstEnableGate: CompletableDeferred<Unit>? = null,
    ) : DjiLaserHardwareAccess {
        var listenCalls = 0
        var cancelCalls = 0
        var enableCalls = 0
        var disableCalls = 0

        override fun listen(owner: Any, observer: (LaserRangefinderResult) -> Unit) {
            listenCalls += 1
            if (listenFailures-- > 0) error("listen-registration-failed")
        }

        override fun cancelListen(owner: Any) {
            cancelCalls += 1
            if (cancelFailures-- > 0) error("listener-unregister-failed")
        }

        override suspend fun enable() {
            enableCalls += 1
            if (enableCalls == 1) firstEnableGate?.await()
        }

        override suspend fun disable() {
            disableCalls += 1
        }

        override suspend fun current(): LaserRangefinderResult? = null
    }
}
