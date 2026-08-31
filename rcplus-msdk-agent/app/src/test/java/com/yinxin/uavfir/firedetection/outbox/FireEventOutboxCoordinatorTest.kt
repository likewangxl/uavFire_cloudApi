package com.yinxin.uavfir.firedetection.outbox

import com.yinxin.uavfir.firedetection.NormalizedRoi
import com.yinxin.uavfir.firedetection.VisibleDetection
import com.yinxin.uavfir.firedetection.VisibleDetectionReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FireEventOutboxCoordinatorTest {
    @Test
    fun stableEventId_isRepeatableAndChangesWithSourceEvent() {
        val report = report(sourceTs = 1_000L)

        val first = FireEventPayloadFactory.stableEventId(report)
        val second = FireEventPayloadFactory.stableEventId(report)

        assertEquals(first, second)
        assertTrue(first.startsWith("agent-"))
        assertTrue(first.length <= 64)
        assertNotEquals(first, FireEventPayloadFactory.stableEventId(report(sourceTs = 1_001L)))
    }

    @Test
    fun acceptedAndDuplicateReceipts_markEventsDelivered() = runTest {
        var now = 5_000L
        val store = InMemoryOutboxStore()
        val results = ArrayDeque<FireEventDeliveryResult>().apply {
            add(FireEventDeliveryResult.Delivered("accepted"))
            add(FireEventDeliveryResult.Delivered("duplicate"))
        }
        val coordinator = coordinator(store, results, nowProvider = { now })

        coordinator.enqueue(report(sourceTs = 10L))
        coordinator.enqueue(report(sourceTs = 20L))
        assertEquals(2, store.health().pendingCount)

        assertTrue(coordinator.deliverDueOnce())
        assertTrue(coordinator.deliverDueOnce())

        assertEquals(0, store.health().pendingCount)
        assertEquals(2, store.entries.values.count { it.state == FireEventOutboxEntry.STATE_DELIVERED })
    }

    @Test
    fun retryableFailure_usesSpecifiedBackoffAndReportsHealth() = runTest {
        var now = 10_000L
        val store = InMemoryOutboxStore()
        val results = ArrayDeque<FireEventDeliveryResult>().apply {
            repeat(7) { add(FireEventDeliveryResult.Retryable("network-down")) }
        }
        val coordinator = coordinator(store, results, nowProvider = { now })
        coordinator.enqueue(report(sourceTs = 30L))

        val expectedDelays = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L)
        expectedDelays.forEachIndexed { index, expectedDelay ->
            assertTrue(coordinator.deliverDueOnce())
            val entry = store.entries.values.single()
            assertEquals(index + 1, entry.attemptCount)
            assertEquals(now + expectedDelay, entry.nextAttemptAt)
            assertEquals("network-down", store.health().lastError)
            assertFalse(coordinator.deliverDueOnce())
            now = entry.nextAttemptAt
        }
        assertEquals(1, store.health().pendingCount)
        assertEquals(10_000L, store.health().oldestPendingAt)
    }

    @Test
    fun pendingEvent_canBeDeliveredByCoordinatorCreatedAfterRestart() = runTest {
        var now = 20_000L
        val store = InMemoryOutboxStore()
        val firstProcess = coordinator(
            store,
            ArrayDeque<FireEventDeliveryResult>().apply {
                add(FireEventDeliveryResult.Retryable("offline"))
            },
            nowProvider = { now },
        )
        firstProcess.enqueue(report(sourceTs = 40L))
        assertTrue(firstProcess.deliverDueOnce())
        now += 1_000L

        val restartedProcess = coordinator(
            store,
            ArrayDeque<FireEventDeliveryResult>().apply {
                add(FireEventDeliveryResult.Delivered("duplicate"))
            },
            nowProvider = { now },
        )
        assertTrue(restartedProcess.deliverDueOnce())

        assertEquals(0, store.health().pendingCount)
        assertEquals(FireEventOutboxEntry.STATE_DELIVERED, store.entries.values.single().state)
    }

    @Test
    fun rejectedReceipt_staysVisibleButIsNotRetried() = runTest {
        val store = InMemoryOutboxStore()
        val coordinator = coordinator(
            store,
            ArrayDeque<FireEventDeliveryResult>().apply {
                add(FireEventDeliveryResult.Rejected("invalid-event"))
            },
            nowProvider = { 30_000L },
        )
        coordinator.enqueue(report(sourceTs = 50L))

        assertTrue(coordinator.deliverDueOnce())

        assertFalse(coordinator.deliverDueOnce())
        assertEquals(1, store.health().pendingCount)
        assertEquals("invalid-event", store.health().lastError)
        assertEquals(FireEventOutboxEntry.STATE_FAILED, store.entries.values.single().state)
    }

    private fun coordinator(
        store: InMemoryOutboxStore,
        results: ArrayDeque<FireEventDeliveryResult>,
        nowProvider: () -> Long,
    ) = FireEventOutboxCoordinator(
        scope = thisScope,
        store = store,
        sender = FireEventSender { results.removeFirst() },
        clock = nowProvider,
    )

    private fun report(sourceTs: Long) = VisibleDetectionReport(
        taskId = "fire-DRONE-1",
        droneSn = "DRONE-1",
        sourceTs = sourceTs,
        detection = VisibleDetection(
            classId = 0,
            label = "fire",
            confidence = 0.82,
            roi = NormalizedRoi(0.1, 0.2, 0.3, 0.4),
        ),
        inferenceMs = 25,
        modelVersion = "best-test",
        modelSha256 = "abc123",
    )

    private class InMemoryOutboxStore : FireEventOutboxStore {
        val entries = linkedMapOf<String, FireEventOutboxEntry>()
        private var latestError: String? = null

        override fun enqueue(entry: FireEventOutboxEntry): Boolean {
            if (entries.containsKey(entry.eventId)) return false
            entries[entry.eventId] = entry
            return true
        }

        override fun nextDue(nowMs: Long): FireEventOutboxEntry? = entries.values
            .filter { it.state == FireEventOutboxEntry.STATE_PENDING && it.nextAttemptAt <= nowMs }
            .minWithOrNull(compareBy<FireEventOutboxEntry> { it.nextAttemptAt }.thenBy { it.createdAt })

        override fun markDelivered(eventId: String, deliveredAtMs: Long) {
            entries[eventId]?.let { entries[eventId] = it.copy(state = FireEventOutboxEntry.STATE_DELIVERED) }
        }

        override fun markRetry(
            eventId: String,
            attemptCount: Int,
            nextAttemptAtMs: Long,
            error: String,
            failedAtMs: Long,
        ) {
            latestError = error
            entries[eventId]?.let {
                entries[eventId] = it.copy(
                    state = FireEventOutboxEntry.STATE_PENDING,
                    attemptCount = attemptCount,
                    nextAttemptAt = nextAttemptAtMs,
                    lastError = error,
                )
            }
        }

        override fun markRejected(eventId: String, attemptCount: Int, error: String, failedAtMs: Long) {
            latestError = error
            entries[eventId]?.let {
                entries[eventId] = it.copy(
                    state = FireEventOutboxEntry.STATE_FAILED,
                    attemptCount = attemptCount,
                    lastError = error,
                )
            }
        }

        override fun health(): FireEventOutboxHealth {
            val pending = entries.values.filter { it.state != FireEventOutboxEntry.STATE_DELIVERED }
            return FireEventOutboxHealth(
                pendingCount = pending.size,
                oldestPendingAt = pending.minOfOrNull { it.createdAt },
                lastError = latestError,
            )
        }

        override fun deleteDeliveredBefore(cutoffMs: Long) = Unit
        override fun close() = Unit
    }

    private companion object {
        // Tests call deliverDueOnce directly; this scope is never started.
        val thisScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    }
}
