package com.yinxin.uavfir.firedetection.store

import java.io.IOException

sealed interface SendOutcome {
    data object Acknowledged : SendOutcome
    data object ExactDuplicate : SendOutcome
    data class PayloadConflict(val reason: String) : SendOutcome
    data class TransientFailure(val reason: String) : SendOutcome
}

fun interface FireReportTransport {
    suspend fun send(row: OutboxRow): SendOutcome
}

sealed interface DispatchResult {
    data object Idle : DispatchResult
    data object Acknowledged : DispatchResult
    data class RetryScheduled(val reason: String) : DispatchResult
    data class Quarantined(val reason: String) : DispatchResult
    data object LeaseLost : DispatchResult
}

class FireOutboxDispatcher(
    private val store: FireOutboxDispatchStore,
    private val transport: FireReportTransport,
    private val leaseMillis: Long = 30_000,
) {
    init {
        require(leaseMillis > 0)
    }

    suspend fun dispatchOnce(): DispatchResult {
        val lease = store.leaseNext(leaseMillis) ?: return DispatchResult.Idle
        val outcome = try {
            transport.send(lease.row)
        } catch (error: IOException) {
            val reason = error.message ?: error.javaClass.simpleName
            return if (store.scheduleRetry(lease, reason)) {
                DispatchResult.RetryScheduled(reason)
            } else {
                DispatchResult.LeaseLost
            }
        }
        return when (outcome) {
            SendOutcome.Acknowledged,
            SendOutcome.ExactDuplicate,
            -> if (store.markAcknowledged(lease)) DispatchResult.Acknowledged else DispatchResult.LeaseLost
            is SendOutcome.PayloadConflict ->
                if (store.quarantine(lease, outcome.reason)) {
                    DispatchResult.Quarantined(outcome.reason)
                } else {
                    DispatchResult.LeaseLost
                }
            is SendOutcome.TransientFailure ->
                if (store.scheduleRetry(lease, outcome.reason)) {
                    DispatchResult.RetryScheduled(outcome.reason)
                } else {
                    DispatchResult.LeaseLost
                }
        }
    }
}
