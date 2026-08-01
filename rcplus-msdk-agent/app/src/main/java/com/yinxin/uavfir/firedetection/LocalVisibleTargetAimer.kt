package com.yinxin.uavfir.firedetection

import kotlinx.coroutines.CancellationException
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

data class LocalTargetAimRequest(
    val sessionId: String,
    val eventId: String,
    val kind: DetectionKind,
    val priorRoi: NormalizedRoi,
) {
    init {
        require(sessionId.isNotBlank())
        require(eventId.isNotBlank())
    }
}

fun interface LocalVisibleTargetAimerPort {
    suspend fun align(request: LocalTargetAimRequest): LocalTargetAimResult
}

data class TargetActionReceipt(
    val completedAtMonotonicMs: Long,
    val sourceGeneration: Long,
    val reticleX: Double,
    val reticleY: Double,
) {
    init {
        require(completedAtMonotonicMs >= 0L)
        require(sourceGeneration > 0L)
        require(reticleX.isFinite() && reticleX in 0.0..1.0)
        require(reticleY.isFinite() && reticleY in 0.0..1.0)
    }
}

fun interface LocalTargetAlignmentAction {
    /**
     * Completes one tap-zoom/alignment operation. The receipt is captured only
     * after the hardware callback completes and binds the action to the active
     * visible-source generation.
     */
    suspend fun alignAt(x: Double, y: Double): TargetActionReceipt
}

data class LocalDetectionAwaitRequest(
    val sessionId: String,
    val eventId: String,
    val kind: DetectionKind,
    val sourceGeneration: Long,
    val capturedStrictlyAfterMonotonicMs: Long,
    val afterPublicationSequence: Long,
) {
    init {
        require(sessionId.isNotBlank())
        require(eventId.isNotBlank())
        require(sourceGeneration > 0)
        require(capturedStrictlyAfterMonotonicMs >= 0)
        require(afterPublicationSequence >= 0)
    }
}

data class LocalVisibleDetectionObservation(
    val sessionId: String,
    val eventId: String,
    val sourceGeneration: Long,
    val capturedAtMonotonicMs: Long,
    val startedAtMonotonicMs: Long,
    val completedAtMonotonicMs: Long,
    val publicationSequence: Long,
    val outcome: VisibleInferenceOutcome,
    val healthy: Boolean,
    val detections: List<VisibleDetection>,
) {
    val structurallyValid: Boolean
        get() = sessionId.isNotBlank() && eventId.isNotBlank() &&
            sourceGeneration > 0 && publicationSequence > 0 &&
            capturedAtMonotonicMs >= 0 &&
            capturedAtMonotonicMs <= startedAtMonotonicMs &&
            startedAtMonotonicMs <= completedAtMonotonicMs
}

enum class LocalDetectionAwaitFailure { JOURNAL_OVERFLOW, TIMEOUT }

sealed interface LocalDetectionAwaitResult {
    data class Observed(val observation: LocalVisibleDetectionObservation) : LocalDetectionAwaitResult
    data class Failed(val reason: LocalDetectionAwaitFailure) : LocalDetectionAwaitResult
}

/**
 * Subscription port for Task 4's sole production inference result stream.
 *
 * Implementations must not run a detector or consume the latest-frame buffer.
 * Cancellation of [await] must unregister any listener owned by the adapter.
 */
interface LocalVisibleDetectionSource {
    fun cursor(): Long
    suspend fun await(request: LocalDetectionAwaitRequest): LocalDetectionAwaitResult
}

enum class VisibleInferenceOutcome { SUCCESS, FAILURE }

class VisibleInferencePublication(
    val sourceGeneration: Long,
    val capturedAtMonotonicMs: Long,
    val startedAtMonotonicMs: Long,
    val completedAtMonotonicMs: Long,
    val outcome: VisibleInferenceOutcome,
    val health: VisibleInferenceStatus,
    detections: List<VisibleDetection>,
    val failure: String?,
) {
    val detections: List<VisibleDetection> =
        Collections.unmodifiableList(ArrayList(detections))

    init {
        require(sourceGeneration > 0)
        require(capturedAtMonotonicMs >= 0)
        require(capturedAtMonotonicMs <= startedAtMonotonicMs)
        require(startedAtMonotonicMs <= completedAtMonotonicMs)
        require(
            (outcome == VisibleInferenceOutcome.SUCCESS &&
                health == VisibleInferenceStatus.HEALTHY && failure == null) ||
                (outcome == VisibleInferenceOutcome.FAILURE &&
                    health == VisibleInferenceStatus.DEGRADED && !failure.isNullOrBlank()),
        )
    }
}

data class PublishedVisibleInferenceResult(
    val sequence: Long,
    val publication: VisibleInferencePublication,
) {
    init { require(sequence > 0) }
}

fun interface VisibleInferenceResultPublisher {
    fun publish(result: VisibleInferencePublication)

    companion object {
        val NO_OP = VisibleInferenceResultPublisher { }
    }
}

/**
 * Read-only fan-out of the sole [VisibleInferenceLoop]. It carries no frame
 * ownership and cannot invoke the detector or consume the frame buffer.
 */
class VisibleInferenceResultJournal(
    private val capacity: Int = RESULT_JOURNAL_CAPACITY,
) :
    VisibleInferenceResultPublisher,
    LocalVisibleDetectionSource {
    init { require(capacity > 0) }

    private data class State(
        val lastSequence: Long = 0,
        val entries: List<PublishedVisibleInferenceResult> = emptyList(),
    )

    private val monitor = Any()
    private val state = MutableStateFlow(State())

    override fun cursor(): Long = state.value.lastSequence

    override fun publish(result: VisibleInferencePublication) {
        synchronized(monitor) {
            val current = state.value
            val sequence = current.lastSequence + 1
            val retained = (current.entries + PublishedVisibleInferenceResult(sequence, result))
                .takeLast(capacity)
            state.value = State(sequence, retained)
        }
    }

    override suspend fun await(request: LocalDetectionAwaitRequest): LocalDetectionAwaitResult {
        evaluate(state.value, request)?.let { return it }
        return withTimeoutOrNull(DEFAULT_AWAIT_TIMEOUT_MS) {
            state.first { evaluate(it, request) != null }
                .let { evaluate(it, request)!! }
        } ?: LocalDetectionAwaitResult.Failed(LocalDetectionAwaitFailure.TIMEOUT)
    }

    private fun evaluate(state: State, request: LocalDetectionAwaitRequest): LocalDetectionAwaitResult? {
        val oldest = state.entries.firstOrNull()?.sequence
        if (oldest != null && request.afterPublicationSequence < oldest - 1) {
            return LocalDetectionAwaitResult.Failed(LocalDetectionAwaitFailure.JOURNAL_OVERFLOW)
        }
        val published = state.entries.firstOrNull { it.sequence > request.afterPublicationSequence }
            ?: return null
        val value = published.publication
        return LocalDetectionAwaitResult.Observed(LocalVisibleDetectionObservation(
            sessionId = request.sessionId,
            eventId = request.eventId,
            sourceGeneration = value.sourceGeneration,
            capturedAtMonotonicMs = value.capturedAtMonotonicMs,
            startedAtMonotonicMs = value.startedAtMonotonicMs,
            completedAtMonotonicMs = value.completedAtMonotonicMs,
            publicationSequence = published.sequence,
            outcome = value.outcome,
            healthy = value.health == VisibleInferenceStatus.HEALTHY,
            detections = value.detections,
        ))
    }

    companion object {
        const val RESULT_JOURNAL_CAPACITY = 32
        const val DEFAULT_AWAIT_TIMEOUT_MS = 2_000L
    }
}

enum class LocalTargetAimFailure {
    ACTION_FAILED,
    DETECTION_TIMEOUT,
    TARGET_NOT_REACQUIRED,
    RETICLE_OUTSIDE_ROI,
}

sealed interface LocalTargetAimResult {
    data class Aligned(
        val kind: DetectionKind,
        val roi: NormalizedRoi,
        val sourceGeneration: Long,
        val detectionCapturedAtMonotonicMs: Long,
        val cycles: Int,
    ) : LocalTargetAimResult

    data class Failed(val reason: LocalTargetAimFailure) : LocalTargetAimResult
}

class LocalVisibleTargetAimer(
    private val alignmentAction: LocalTargetAlignmentAction,
    private val detectionSource: LocalVisibleDetectionSource,
    private val minimumConfidence: Float = DEFAULT_MINIMUM_CONFIDENCE,
    private val observationTimeoutMs: Long = DEFAULT_OBSERVATION_TIMEOUT_MS,
    private val maxCycles: Int = MAX_ALIGNMENT_CYCLES,
    private val maxObservationsPerCycle: Int = MAX_OBSERVATIONS_PER_CYCLE,
) : LocalVisibleTargetAimerPort {
    init {
        require(minimumConfidence in 0f..1f)
        require(observationTimeoutMs > 0)
        require(maxCycles in 1..MAX_ALIGNMENT_CYCLES)
        require(maxObservationsPerCycle > 0)
    }

    override suspend fun align(request: LocalTargetAimRequest): LocalTargetAimResult {
        var prior = request.priorRoi
        var sawReticleMiss = false
        repeat(maxCycles) { cycle ->
            val cursorBeforeAction = detectionSource.cursor()
            val receipt = try {
                alignmentAction.alignAt(prior.centerX, prior.centerY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return LocalTargetAimResult.Failed(LocalTargetAimFailure.ACTION_FAILED)
            }
            val reacquired = awaitEligible(request, prior, receipt, cursorBeforeAction)
            if (reacquired is EligibleOutcome.Failed) {
                if (reacquired.reason == LocalTargetAimFailure.DETECTION_TIMEOUT) {
                    return LocalTargetAimResult.Failed(reacquired.reason)
                }
                return@repeat
            }
            val newest = (reacquired as EligibleOutcome.Found).detection
            if (newest.roi.contains(receipt.reticleX, receipt.reticleY)) {
                return LocalTargetAimResult.Aligned(
                    kind = request.kind,
                    roi = newest.roi,
                    sourceGeneration = receipt.sourceGeneration,
                    detectionCapturedAtMonotonicMs = newest.capturedAt,
                    cycles = cycle + 1,
                )
            }
            sawReticleMiss = true
            prior = newest.roi
        }
        return LocalTargetAimResult.Failed(
            if (sawReticleMiss) LocalTargetAimFailure.RETICLE_OUTSIDE_ROI
            else LocalTargetAimFailure.TARGET_NOT_REACQUIRED,
        )
    }

    private suspend fun awaitEligible(
        request: LocalTargetAimRequest,
        prior: NormalizedRoi,
        receipt: TargetActionReceipt,
        cursorBeforeAction: Long,
    ): EligibleOutcome {
        var lastSeen = receipt.completedAtMonotonicMs
        var cursor = cursorBeforeAction
        repeat(maxObservationsPerCycle) {
            val awaitRequest = LocalDetectionAwaitRequest(
                sessionId = request.sessionId,
                eventId = request.eventId,
                kind = request.kind,
                sourceGeneration = receipt.sourceGeneration,
                capturedStrictlyAfterMonotonicMs = lastSeen,
                afterPublicationSequence = cursor,
            )
            val awaited = withTimeoutOrNull(observationTimeoutMs) {
                detectionSource.await(awaitRequest)
            } ?: return EligibleOutcome.Failed(LocalTargetAimFailure.DETECTION_TIMEOUT)
            if (awaited is LocalDetectionAwaitResult.Failed) {
                return EligibleOutcome.Failed(
                    if (awaited.reason == LocalDetectionAwaitFailure.TIMEOUT)
                        LocalTargetAimFailure.DETECTION_TIMEOUT
                    else LocalTargetAimFailure.TARGET_NOT_REACQUIRED,
                )
            }
            val observed = (awaited as LocalDetectionAwaitResult.Observed).observation
            cursor = observed.publicationSequence
            if (observed.structurallyValid &&
                observed.sessionId == request.sessionId &&
                observed.eventId == request.eventId &&
                observed.sourceGeneration == receipt.sourceGeneration &&
                observed.capturedAtMonotonicMs > lastSeen
            ) {
                lastSeen = observed.capturedAtMonotonicMs
            }
            if (!observed.structurallyValid ||
                observed.sessionId != request.sessionId ||
                observed.eventId != request.eventId ||
                observed.sourceGeneration != receipt.sourceGeneration ||
                observed.capturedAtMonotonicMs <= receipt.completedAtMonotonicMs ||
                !observed.healthy
            ) {
                return@repeat
            }
            val selected = observed.detections
                .asSequence()
                .filter { it.confidence >= minimumConfidence && it.matches(request.kind) }
                .minByOrNull {
                    hypot(
                        NormalizedRoi.from(it).centerX - prior.centerX,
                        NormalizedRoi.from(it).centerY - prior.centerY,
                    )
                } ?: return@repeat
            return EligibleOutcome.Found(EligibleDetection(
                roi = NormalizedRoi.from(selected),
                capturedAt = observed.capturedAtMonotonicMs,
            ))
        }
        return EligibleOutcome.Failed(LocalTargetAimFailure.TARGET_NOT_REACQUIRED)
    }

    private sealed interface EligibleOutcome {
        data class Found(val detection: EligibleDetection) : EligibleOutcome
        data class Failed(val reason: LocalTargetAimFailure) : EligibleOutcome
    }

    private data class EligibleDetection(
        val roi: NormalizedRoi,
        val capturedAt: Long,
    )

    companion object {
        const val MAX_ALIGNMENT_CYCLES = 3
        const val MAX_OBSERVATIONS_PER_CYCLE = 8
        const val DEFAULT_OBSERVATION_TIMEOUT_MS = 2_000L
        const val DEFAULT_MINIMUM_CONFIDENCE = .5f
    }
}

data class AircraftOsdSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val observationSequence: Long,
    val capturedAtMonotonicMs: Long,
) {
    val valid: Boolean
        get() = latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            altitude.isFinite() && altitude in -1_000.0..20_000.0 &&
            observationSequence > 0 && capturedAtMonotonicMs >= 0L

    fun isFreshAt(failureAtMonotonicMs: Long, maxAgeMs: Long): Boolean =
        valid && maxAgeMs >= 0 && capturedAtMonotonicMs <= failureAtMonotonicMs &&
            failureAtMonotonicMs - capturedAtMonotonicMs <= maxAgeMs
}

fun interface AircraftOsdSnapshotProvider {
    fun current(): AircraftOsdSnapshot?
}

data class FireLocalizationRequest(
    val sessionId: String,
    val eventId: String,
    val taskId: String,
    val kind: DetectionKind,
    val initialRoi: NormalizedRoi,
) {
    init {
        require(sessionId.isNotBlank() && eventId.isNotBlank() && taskId.isNotBlank())
    }
}

enum class FireLocalizationFailure {
    EVENT_SESSION_MISMATCH,
    TARGET_NOT_ALIGNED,
    TARGET_DETECTION_TIMEOUT,
    SOURCE_GENERATION_CHANGED,
    LASER_ENABLE_FAILED,
    LASER_CALLBACK_OVERFLOW,
    LASER_SAMPLES_UNAVAILABLE,
    LASER_SAMPLES_INVALID,
    AIRCRAFT_OSD_UNAVAILABLE,
}

sealed interface FireLocalizationResult {
    val kind: DetectionKind
    val reason: FireLocalizationFailure?
    val locationStatus: String
    val geoMethod: String?
    val fireLatitude: Double?
    val fireLongitude: Double?
    val fireAltitude: Double?

    data class Precise(
        override val kind: DetectionKind,
        override val fireLatitude: Double,
        override val fireLongitude: Double,
        override val fireAltitude: Double,
        val rangeM: Double,
        val errorRadiusM: Double,
        val rawSamples: List<BoundLaserSample>,
        val targetRoi: NormalizedRoi,
        val sourceGeneration: Long,
    ) : FireLocalizationResult {
        override val reason: FireLocalizationFailure? = null
        override val locationStatus: String = "PRECISE"
        override val geoMethod: String = "LASER_RANGEFINDER"
    }

    data class DegradedOsd(
        override val kind: DetectionKind,
        override val reason: FireLocalizationFailure,
        val aircraftOsd: AircraftOsdSnapshot,
    ) : FireLocalizationResult {
        override val locationStatus: String = "DEGRADED_OSD"
        override val geoMethod: String = "AIRCRAFT_OBSERVATION"
        override val fireLatitude: Double? = null
        override val fireLongitude: Double? = null
        override val fireAltitude: Double? = null
    }

    data class ManualHold(
        override val kind: DetectionKind,
        override val reason: FireLocalizationFailure,
    ) : FireLocalizationResult {
        override val locationStatus: String = "MANUAL_HOLD"
        override val geoMethod: String? = null
        override val fireLatitude: Double? = null
        override val fireLongitude: Double? = null
        override val fireAltitude: Double? = null
    }
}

private fun VisibleDetection.matches(kind: DetectionKind): Boolean =
    className.equals(kind.name, ignoreCase = true)

internal fun NormalizedRoi.contains(x: Double, y: Double): Boolean =
    x >= left && x <= right && y >= top && y <= bottom
