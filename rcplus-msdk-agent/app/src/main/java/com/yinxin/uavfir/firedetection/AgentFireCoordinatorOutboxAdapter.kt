package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.firedetection.store.DispatchResult
import com.yinxin.uavfir.firedetection.store.FireOutboxDispatcher
import com.yinxin.uavfir.firedetection.store.OutboxStatus
import com.yinxin.uavfir.firedetection.store.SqliteFireSessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A non-blocking wake-up pump over Task 6's durable ordered dispatcher. */
class StoreBackedCoordinatorOutboxPort(
    private val store: SqliteFireSessionStore,
    private val dispatcher: FireOutboxDispatcher,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 250L,
) : CoordinatorOutboxPort {
    init { require(pollMillis > 0) }

    private val pumping = AtomicBoolean()

    override fun trigger(eventId: String, sequence: Long) {
        require(eventId.isNotBlank() && sequence > 0)
        startPump()
    }

    override suspend fun awaitTerminalAck(eventId: String, sequence: Long): TerminalAckResult {
        while (true) {
            val row = store.loadOutbox(eventId).firstOrNull { it.sequence == sequence }
                ?: return TerminalAckResult.Rejected
            when (row.status) {
                OutboxStatus.ACKED -> return TerminalAckResult.Acknowledged(eventId, sequence)
                OutboxStatus.QUARANTINED -> return TerminalAckResult.Rejected
                OutboxStatus.PENDING, OutboxStatus.IN_FLIGHT -> delay(pollMillis)
            }
        }
    }

    override fun restartPendingDelivery() = startPump()

    private fun startPump() {
        if (!pumping.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (isActive) {
                    when (dispatcher.dispatchOnce()) {
                        DispatchResult.Acknowledged -> Unit
                        is DispatchResult.Quarantined -> Unit
                        DispatchResult.Idle,
                        DispatchResult.LeaseLost,
                        is DispatchResult.RetryScheduled,
                        -> {
                            if (store.loadPendingOutbox().none {
                                    it.status == OutboxStatus.PENDING ||
                                        it.status == OutboxStatus.IN_FLIGHT
                                }
                            ) {
                                return@launch
                            }
                            delay(pollMillis)
                        }
                    }
                }
            } finally {
                pumping.set(false)
                // Close the lost-wakeup race with a row committed while the
                // previous pump was leaving its final iteration.
                if (store.loadPendingOutbox().any { it.status == OutboxStatus.PENDING }) startPump()
            }
        }
    }
}
