package com.yinxin.uavfir.firedetection.store

import android.os.SystemClock
import com.yinxin.uavfir.firedetection.DetectionKind
import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.GeoMethod
import com.yinxin.uavfir.firedetection.InitialPersistenceRequest
import com.yinxin.uavfir.firedetection.LocationStatus
import com.yinxin.uavfir.firedetection.TerminalPersistenceRequest
import java.io.File
import java.security.MessageDigest

object FireStoreContract {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_DATABASE_NAME = "agent-fire-store.db"

    object Session {
        const val TABLE = "fire_session"
    }

    object Evidence {
        const val TABLE = "fire_evidence"
    }

    object Outbox {
        const val TABLE = "report_outbox"
    }
}

interface StoreClock {
    fun elapsedRealtimeMillis(): Long
    fun wallTimeMillis(): Long
    fun monotonicEpochId(): String
}

class AndroidStoreClock(
    private val epochId: String = stableBootEpoch(),
) : StoreClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun wallTimeMillis(): Long = System.currentTimeMillis()
    override fun monotonicEpochId(): String = epochId

    private companion object {
        fun stableBootEpoch(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
                .takeIf { it.isNotBlank() }
                ?: error("empty boot ID")
        }.getOrElse {
            val bootWallMinute = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L
            "boot-wall-minute-$bootWallMinute"
        }
    }
}

internal class MutableStoreClock(
    var elapsedMillis: Long,
    var wallMillis: Long,
    var epochId: String = "test-boot",
) : StoreClock {
    override fun elapsedRealtimeMillis(): Long = elapsedMillis
    override fun wallTimeMillis(): Long = wallMillis
    override fun monotonicEpochId(): String = epochId
}

data class FireEvidenceReference(
    val path: String,
    val sha256: String,
    val mediaType: String,
    val capturedAtWallMillis: Long,
    val byteSize: Long,
    val metadataJson: String,
) {
    init {
        require(path.startsWith("/") && path.isNotBlank()) { "Evidence path must be absolute" }
        require(isSha256(sha256)) { "Evidence SHA-256 is invalid" }
        require(mediaType.isNotBlank()) { "Evidence media type is required" }
        require(capturedAtWallMillis >= 0) { "Evidence capture timestamp is invalid" }
        require(byteSize >= 0) { "Evidence size is invalid" }
        require(metadataJson.isNotBlank()) { "Evidence metadata is required" }
    }
}

data class InitialConfirmationRecord(
    val request: InitialPersistenceRequest,
    val eventTimestampWallMillis: Long,
    val modelVersion: String,
    val modelHash: String,
    val inputSize: Int,
    val runtime: String,
    val payload: String,
    val payloadSha256: String,
    val evidence: List<FireEvidenceReference>,
) {
    init {
        require(eventTimestampWallMillis >= 0)
        require(modelVersion.isNotBlank())
        require(isSha256(modelHash))
        require(inputSize > 0)
        require(runtime.isNotBlank())
        validatePayload(payload, payloadSha256)
    }
}

data class TerminalResultRecord(
    val request: TerminalPersistenceRequest,
    val sequence: Long,
    val eventTimestampWallMillis: Long,
    val payload: String,
    val payloadSha256: String,
    val evidence: List<FireEvidenceReference>,
) {
    init {
        require(request.isValidTerminalMapping) { "Invalid terminal mapping" }
        require(sequence > 1) { "Terminal sequence must follow the initial report" }
        require(eventTimestampWallMillis >= 0)
        validatePayload(payload, payloadSha256)
    }
}

data class StagePersistenceRecord(
    val sessionId: String,
    val eventId: String,
    val sequence: Long,
    val eventTimestampWallMillis: Long,
    val state: FireSessionState,
    val payload: String,
    val payloadSha256: String,
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank())
        require(sequence > 1)
        require(eventTimestampWallMillis >= 0)
        validatePayload(payload, payloadSha256)
    }
}

sealed interface DurableWriteResult {
    data object Written : DurableWriteResult
    data object ExactDuplicate : DurableWriteResult
    data class Conflict(val reason: String) : DurableWriteResult
    data class Rejected(val reason: String) : DurableWriteResult
}

data class DurableFireSession(
    val sessionId: String,
    val eventId: String,
    val state: FireSessionState,
    val detectionKind: DetectionKind,
    val locationStatus: LocationStatus,
    val geoMethod: GeoMethod?,
    val createdAtWallMillis: Long,
    val updatedAtWallMillis: Long,
)

enum class OutboxStatus {
    PENDING,
    IN_FLIGHT,
    ACKED,
    QUARANTINED,
}

data class OutboxRow(
    val eventId: String,
    val sessionId: String,
    val sequence: Long,
    val eventTimestampWallMillis: Long,
    val payload: String,
    val payloadSha256: String,
    val status: OutboxStatus,
    val attemptCount: Int,
    val nextAttemptElapsedMillis: Long,
    val leaseUntilElapsedMillis: Long?,
    val lastError: String?,
)

data class OutboxLease(
    val row: OutboxRow,
    val token: String,
)

data class FireStoreStartup(
    val activeSessions: List<DurableFireSession>,
    val pendingOutbox: List<OutboxRow>,
)

interface FireOutboxDispatchStore {
    fun leaseNext(leaseMillis: Long): OutboxLease?
    fun markAcknowledged(lease: OutboxLease): Boolean
    fun scheduleRetry(lease: OutboxLease, reason: String): Boolean
    fun quarantine(lease: OutboxLease, reason: String): Boolean
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun validatePayload(payload: String, expectedHash: String) {
    require(payload.isNotBlank()) { "Outbox payload is required" }
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_OUTBOX_PAYLOAD_BYTES) {
        "Outbox payload is too large"
    }
    require(!payload.contains("data:image", ignoreCase = true)) {
        "Image bytes must not be stored in the Outbox"
    }
    require(!SENSITIVE_OR_BINARY_JSON_KEY.containsMatchIn(payload)) {
        "Outbox payload contains a forbidden sensitive or binary field"
    }
    require(isSha256(expectedHash)) { "Payload SHA-256 is invalid" }
    require(sha256(payload) == expectedHash.lowercase()) { "Payload SHA-256 does not match payload" }
}

private fun isSha256(value: String): Boolean = value.matches(Regex("^[0-9a-fA-F]{64}$"))

private const val MAX_OUTBOX_PAYLOAD_BYTES = 256 * 1024
private val SENSITIVE_OR_BINARY_JSON_KEY = Regex(
    """"(?:password|authorization|accessToken|refreshToken|phone|email|imageBytes|jpegBytes|base64)"\s*:""",
    RegexOption.IGNORE_CASE,
)
