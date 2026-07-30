package com.yinxin.uavfir.firedetection.store

import android.os.SystemClock
import com.yinxin.uavfir.firedetection.DetectionKind
import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.FireSessionEffect
import com.yinxin.uavfir.firedetection.GeoMethod
import com.yinxin.uavfir.firedetection.InitialPersistenceRequest
import com.yinxin.uavfir.firedetection.LocationStatus
import com.yinxin.uavfir.firedetection.TerminalPersistenceRequest
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object FireStoreContract {
    const val SCHEMA_VERSION = 2
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

fun interface BootIdentitySource {
    fun stableBootId(): String?
}

class AndroidStoreClock(
    bootIdentitySource: BootIdentitySource = PROC_BOOT_IDENTITY,
    fallbackEpochFactory: () -> String = { UUID.randomUUID().toString() },
) : StoreClock {
    private val epochId = bootIdentitySource.stableBootId()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "untrusted-process-${fallbackEpochFactory()}"

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun wallTimeMillis(): Long = System.currentTimeMillis()
    override fun monotonicEpochId(): String = epochId

    private companion object {
        val PROC_BOOT_IDENTITY = BootIdentitySource {
            runCatching {
                File("/proc/sys/kernel/random/boot_id").readText().trim()
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
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
    val widthPixels: Int,
    val heightPixels: Int,
    val rotationDegrees: Int = 0,
) {
    init {
        require(path.startsWith("/") && path.isNotBlank()) { "Evidence path must be absolute" }
        require(isSha256(sha256)) { "Evidence SHA-256 is invalid" }
        require(mediaType.isNotBlank()) { "Evidence media type is required" }
        require(capturedAtWallMillis >= 0) { "Evidence capture timestamp is invalid" }
        require(byteSize >= 0) { "Evidence size is invalid" }
        require(widthPixels > 0 && heightPixels > 0) { "Evidence dimensions are invalid" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Evidence rotation is invalid" }
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
    val evidence: List<FireEvidenceReference>,
) {
    init {
        require(eventTimestampWallMillis >= 0)
        require(modelVersion.isNotBlank())
        require(isSha256(modelHash))
        require(inputSize > 0)
        require(runtime.isNotBlank())
        validatePayloadSize(payload)
    }
}

data class TerminalResultRecord(
    val effect: FireSessionEffect.PersistTerminalResult,
    val sequence: Long,
    val eventTimestampWallMillis: Long,
    val payload: String,
    val evidence: List<FireEvidenceReference>,
) {
    val request: TerminalPersistenceRequest
        get() = effect.request

    init {
        require(request.isValidTerminalMapping) { "Invalid terminal mapping" }
        require(sequence > 1) { "Terminal sequence must follow the initial report" }
        require(eventTimestampWallMillis >= 0)
        validatePayloadSize(payload)
    }
}

data class StagePersistenceRecord(
    val sessionId: String,
    val eventId: String,
    val sequence: Long,
    val eventTimestampWallMillis: Long,
    val state: FireSessionState,
    val payload: String,
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank())
        require(sequence > 1)
        require(eventTimestampWallMillis >= 0)
        validatePayloadSize(payload)
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
    val state: FireSessionState,
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

private fun validatePayloadSize(payload: String) {
    require(payload.isNotBlank()) { "Outbox payload is required" }
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_OUTBOX_PAYLOAD_BYTES) {
        "Outbox payload is too large"
    }
}

private fun isSha256(value: String): Boolean = value.matches(Regex("^[0-9a-fA-F]{64}$"))

private const val MAX_OUTBOX_PAYLOAD_BYTES = 256 * 1024
