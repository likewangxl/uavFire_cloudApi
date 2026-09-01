package com.yinxin.uavfir.firedetection.outbox

data class FireEventOutboxEntry(
    val eventId: String,
    val taskId: String,
    val payloadJson: String,
    val evidenceJpeg: ByteArray?,
    val evidenceSha256: String,
    val evidenceCapturedAt: Long,
    val evidenceUrl: String? = null,
    val state: String = STATE_PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long,
    val createdAt: Long,
    val lastError: String? = null,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_DELIVERED = "DELIVERED"
        const val STATE_FAILED = "FAILED"
    }
}

data class FireEventOutboxHealth(
    val pendingCount: Int = 0,
    val oldestPendingAt: Long? = null,
    val lastError: String? = null,
) {
    companion object {
        val EMPTY = FireEventOutboxHealth()
    }
}

interface FireEventOutboxStore : AutoCloseable {
    fun enqueue(entry: FireEventOutboxEntry): Boolean
    fun nextDue(nowMs: Long): FireEventOutboxEntry?
    fun markDelivered(eventId: String, deliveredAtMs: Long)
    fun markEvidenceUploaded(eventId: String, evidenceUrl: String, uploadedAtMs: Long)
    fun markRetry(eventId: String, attemptCount: Int, nextAttemptAtMs: Long, error: String, failedAtMs: Long)
    fun markRejected(eventId: String, attemptCount: Int, error: String, failedAtMs: Long)
    fun health(): FireEventOutboxHealth
    fun deleteDeliveredBefore(cutoffMs: Long)
}

sealed class FireEventDeliveryResult {
    data class EvidenceUploaded(val visibleImageUrl: String) : FireEventDeliveryResult()
    data class Delivered(val status: String) : FireEventDeliveryResult()
    data class Retryable(val error: String) : FireEventDeliveryResult()
    data class Rejected(val error: String) : FireEventDeliveryResult()
}

interface FireEventAuthProvider {
    suspend fun token(droneSn: String): String
    fun invalidate(droneSn: String)
}

fun interface FireEventSender {
    suspend fun send(entry: FireEventOutboxEntry): FireEventDeliveryResult
}
