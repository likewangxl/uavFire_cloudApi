package com.yinxin.uavfir.firedetection.store

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FireOutboxDispatcherTest {
    @Test
    fun dispatcherMapsAckDuplicateConflictAndTransientWithoutLosingLease() = runTest {
        val store = FakeDispatchStore()
        val transport = QueueTransport(
            SendOutcome.Acknowledged,
            SendOutcome.ExactDuplicate,
            SendOutcome.PayloadConflict("illegal transition"),
            SendOutcome.TransientFailure("HTTP_503"),
        )
        val dispatcher = FireOutboxDispatcher(store, transport, leaseMillis = 1_000)

        store.available = lease(1)
        assertEquals(DispatchResult.Acknowledged, dispatcher.dispatchOnce())
        store.available = lease(2)
        assertEquals(DispatchResult.Acknowledged, dispatcher.dispatchOnce())
        store.available = lease(3)
        assertTrue(dispatcher.dispatchOnce() is DispatchResult.Quarantined)
        store.available = lease(4)
        assertTrue(dispatcher.dispatchOnce() is DispatchResult.RetryScheduled)

        assertEquals(listOf(1L, 2L), store.acked)
        assertEquals(listOf(3L), store.quarantined)
        assertEquals(listOf(4L), store.retried)
    }

    @Test
    fun cancellationLeavesInFlightLeaseForBoundedExpiryRecovery() = runTest {
        val store = FakeDispatchStore().also { it.available = lease(1) }
        val dispatcher = FireOutboxDispatcher(
            store,
            transport = object : FireReportTransport {
                override suspend fun send(row: OutboxRow): SendOutcome = throw CancellationException("stop")
            },
            leaseMillis = 1_000,
        )

        try {
            dispatcher.dispatchOnce()
        } catch (_: CancellationException) {
            // expected
        }
        assertTrue(store.acked.isEmpty())
        assertTrue(store.retried.isEmpty())
        assertTrue(store.quarantined.isEmpty())
        assertTrue(store.leased)
    }

    @Test
    fun thrownNetworkIoIsTransientButDoesNotCatchCancellation() = runTest {
        val store = FakeDispatchStore().also { it.available = lease(1) }
        val dispatcher = FireOutboxDispatcher(
            store,
            transport = FireReportTransport { throw IOException("connection reset") },
        )

        assertEquals(DispatchResult.RetryScheduled("connection reset"), dispatcher.dispatchOnce())
        assertEquals(listOf(1L), store.retried)
    }

    private fun lease(sequence: Long) = OutboxLease(
        row = OutboxRow(
            eventId = "event",
            sessionId = "session",
            sequence = sequence,
            eventTimestampWallMillis = 1,
            state = com.yinxin.uavfir.firedetection.FireSessionState.VISUAL_CONFIRMED,
            payload = "{}",
            payloadSha256 = "a".repeat(64),
            status = OutboxStatus.IN_FLIGHT,
            attemptCount = 0,
            nextAttemptElapsedMillis = 0,
            leaseUntilElapsedMillis = 1_000,
            lastError = null,
        ),
        token = "token-$sequence",
    )

    private class QueueTransport(vararg outcomes: SendOutcome) : FireReportTransport {
        private val outcomes = ArrayDeque(outcomes.toList())
        override suspend fun send(row: OutboxRow): SendOutcome = outcomes.removeFirst()
    }

    private class FakeDispatchStore : FireOutboxDispatchStore {
        var available: OutboxLease? = null
        var leased = false
        val acked = mutableListOf<Long>()
        val retried = mutableListOf<Long>()
        val quarantined = mutableListOf<Long>()

        override fun leaseNext(leaseMillis: Long): OutboxLease? =
            available.also {
                available = null
                leased = it != null
            }

        override fun markAcknowledged(lease: OutboxLease): Boolean =
            acked.add(lease.row.sequence)

        override fun scheduleRetry(lease: OutboxLease, reason: String): Boolean =
            retried.add(lease.row.sequence)

        override fun quarantine(lease: OutboxLease, reason: String): Boolean =
            quarantined.add(lease.row.sequence)
    }
}
