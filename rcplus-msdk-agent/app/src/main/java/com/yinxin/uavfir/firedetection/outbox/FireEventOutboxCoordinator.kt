package com.yinxin.uavfir.firedetection.outbox

import android.util.Log
import com.google.gson.Gson
import com.yinxin.uavfir.api.backendGson
import com.yinxin.uavfir.firedetection.VisibleDetectionReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FireEventOutboxCoordinator(
    private val scope: CoroutineScope,
    private val store: FireEventOutboxStore,
    private val sender: FireEventSender,
    private val gson: Gson = backendGson(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 250L,
) : AutoCloseable {
    @Volatile
    private var worker: Job? = null

    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            runCatching { store.deleteDeliveredBefore(clock() - DELIVERED_RETENTION_MS) }
                .onFailure { Log.w(TAG, "outbox cleanup failed", it) }
            while (isActive) {
                val processed = runCatching { deliverDueOnce() }
                    .onFailure { Log.e(TAG, "outbox delivery loop failed", it) }
                    .getOrDefault(false)
                if (!processed) delay(pollIntervalMs)
            }
        }
    }

    suspend fun enqueue(report: VisibleDetectionReport) {
        val request = FireEventPayloadFactory.request(report)
        val eventId = requireNotNull(request.eventId)
        val now = clock()
        withContext(Dispatchers.IO) {
            store.enqueue(
                FireEventOutboxEntry(
                    eventId = eventId,
                    taskId = report.taskId,
                    payloadJson = gson.toJson(request),
                    nextAttemptAt = now,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun deliverDueOnce(): Boolean {
        val now = clock()
        val entry = withContext(Dispatchers.IO) { store.nextDue(now) } ?: return false
        val result = sender.send(entry)
        val completedAt = clock()
        withContext(Dispatchers.IO) {
            when (result) {
                is FireEventDeliveryResult.Delivered -> store.markDelivered(entry.eventId, completedAt)
                is FireEventDeliveryResult.Retryable -> {
                    val attempts = entry.attemptCount + 1
                    store.markRetry(
                        eventId = entry.eventId,
                        attemptCount = attempts,
                        nextAttemptAtMs = completedAt + FireEventRetryPolicy.delayMs(attempts),
                        error = result.error,
                        failedAtMs = completedAt,
                    )
                }
                is FireEventDeliveryResult.Rejected -> store.markRejected(
                    eventId = entry.eventId,
                    attemptCount = entry.attemptCount + 1,
                    error = result.error,
                    failedAtMs = completedAt,
                )
            }
        }
        return true
    }

    fun health(): FireEventOutboxHealth = runCatching { store.health() }
        .getOrElse { FireEventOutboxHealth(lastError = "outbox-health:${it.message}") }

    override fun close() {
        worker?.cancel()
        worker = null
        store.close()
    }

    companion object {
        private const val TAG = "FireEventOutbox"
        private const val DELIVERED_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}

object FireEventRetryPolicy {
    private val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 60_000)

    fun delayMs(attemptCount: Int): Long {
        val index = (attemptCount - 1).coerceIn(0, DELAYS_MS.lastIndex)
        return DELAYS_MS[index]
    }
}
