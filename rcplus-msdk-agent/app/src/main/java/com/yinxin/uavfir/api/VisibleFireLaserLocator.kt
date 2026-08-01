package com.yinxin.uavfir.api

import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.firedetection.AircraftOsdSnapshotProvider
import com.yinxin.uavfir.firedetection.AircraftOsdSnapshot
import com.yinxin.uavfir.firedetection.BoundLaserSample
import com.yinxin.uavfir.firedetection.BoundLaserObservationClient
import com.yinxin.uavfir.firedetection.FireLocalizationFailure
import com.yinxin.uavfir.firedetection.FireLocalizationRequest
import com.yinxin.uavfir.firedetection.FireLocalizationResult
import com.yinxin.uavfir.firedetection.LaserOperationBinding
import com.yinxin.uavfir.firedetection.LaserHardwareAwaitResult
import com.yinxin.uavfir.firedetection.LaserHardwareOperationToken
import com.yinxin.uavfir.firedetection.LaserSampleValidator
import com.yinxin.uavfir.firedetection.LaserValidationResult
import com.yinxin.uavfir.firedetection.LocalTargetAimRequest
import com.yinxin.uavfir.firedetection.LocalTargetAimFailure
import com.yinxin.uavfir.firedetection.LocalTargetAimResult
import com.yinxin.uavfir.firedetection.LocalVisibleTargetAimerPort
import com.yinxin.uavfir.firedetection.LocalTargetAlignmentAction
import com.yinxin.uavfir.firedetection.FireControlSessionKey
import com.yinxin.uavfir.firedetection.MissionHoldToken
import com.yinxin.uavfir.firedetection.StableHoverEvidence
import com.yinxin.uavfir.firedetection.TargetActionReceipt
import com.yinxin.uavfir.firedetection.VisibleSourceGenerationGuard
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.TapZoomMode
import dji.sdk.keyvalue.value.camera.ZoomTargetPointInfo
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class VerifiedHoldAdoptionFailure {
    INVALID_IDENTITY,
    CONTROL_SESSION_MISMATCH,
    MISSION_MISMATCH,
    PAUSE_GENERATION_MISMATCH,
    INVALID_HOVER_EVIDENCE,
    OWNER_BUSY,
}

sealed interface VerifiedHoldAdoptionResult {
    data object Adopted : VerifiedHoldAdoptionResult
    data class Rejected(val reason: VerifiedHoldAdoptionFailure) : VerifiedHoldAdoptionResult
}

data class VelocitySample(
    val horizontalMps: Double,
    val verticalMps: Double,
)

fun interface AircraftVelocityProvider {
    fun current(): VelocitySample?
}

interface VisibleFireTime {
    fun nowMs(): Long
    suspend fun delayMs(durationMs: Long)
}

fun interface VisibleTargetAimer {
    suspend fun align(taskId: String, visibleRoi: Map<String, Double>): Boolean
}

object UnsupportedVisibleTargetAimer : VisibleTargetAimer {
    override suspend fun align(taskId: String, visibleRoi: Map<String, Double>): Boolean = false
}

fun interface TapZoomClient {
    suspend fun tap(x: Double, y: Double)
}

class DjiTapZoomClient(
    private val keyManager: KeyManager = KeyManager.getInstance(),
) : TapZoomClient {
    override suspend fun tap(x: Double, y: Double) {
        val key: DJIKey.ActionKey<ZoomTargetPointInfo, EmptyMsg> =
            KeyTools.createCameraKey(
                DJICameraKey.KeyTapZoomAtTarget,
                ComponentIndexType.LEFT_OR_MAIN,
                CameraLensType.CAMERA_LENS_ZOOM,
            )
        val target = ZoomTargetPointInfo(
            x.coerceIn(0.0, 1.0),
            y.coerceIn(0.0, 1.0),
            true,
            TapZoomMode.GIMBAL_FOLLOW,
        )
        suspendCancellableCoroutine<Unit> { continuation ->
            keyManager.performAction(
                key,
                target,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        continuation.takeIf { it.isActive }?.resume(Unit)
                    }

                    override fun onFailure(error: IDJIError) {
                        continuation.takeIf { it.isActive }
                            ?.resumeWithException(IllegalStateException(error.description()))
                    }
                },
            )
        }
    }
}

class DjiLocalTargetAlignmentAction(
    private val tapZoomClient: TapZoomClient,
    private val currentVisibleGeneration: () -> Long?,
    private val time: VisibleFireTime = SystemVisibleFireTime,
    private val settleMs: Long = 500L,
) : LocalTargetAlignmentAction {
    init {
        require(settleMs >= 0L)
    }

    override suspend fun alignAt(x: Double, y: Double): TargetActionReceipt {
        val generationBefore = currentVisibleGeneration()
            ?: error("visible-source-generation-unavailable")
        tapZoomClient.tap(x, y)
        time.delayMs(settleMs)
        val generationAfter = currentVisibleGeneration()
            ?: error("visible-source-generation-unavailable")
        check(generationAfter == generationBefore) {
            "visible-source-generation-changed-during-alignment"
        }
        return TargetActionReceipt(
            completedAtMonotonicMs = time.nowMs(),
            sourceGeneration = generationAfter,
            // The MSDK zoom-lens laser reticle is the normalized display
            // center after tap-zoom/gimbal-follow completes.
            reticleX = 0.5,
            reticleY = 0.5,
        )
    }
}

object SystemVisibleFireTime : VisibleFireTime {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    override suspend fun delayMs(durationMs: Long) {
        delay(durationMs)
    }
}

class DjiAircraftVelocityProvider : AircraftVelocityProvider {
    override fun current(): VelocitySample? {
        val velocity = runCatching {
            FlightControllerKey.KeyAircraftVelocity.create()
                .get(Velocity3D(Double.NaN, Double.NaN, Double.NaN))
        }.getOrNull() ?: return null
        if (!velocity.x.isFinite() || !velocity.y.isFinite() || !velocity.z.isFinite()) {
            return null
        }
        return VelocitySample(
            horizontalMps = hypot(velocity.x, velocity.y),
            verticalMps = abs(velocity.z),
        )
    }
}

class DjiAircraftOsdTracker(
    private val keyManager: KeyManager = KeyManager.getInstance(),
    private val nowMonotonicMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) : AircraftOsdSnapshotProvider, AutoCloseable {
    private val owner = Any()
    private val sequence = AtomicLong()
    private val latest = AtomicReference<AircraftOsdSnapshot?>()
    private val locationKey = FlightControllerKey.KeyAircraftLocation3D.create()

    init {
        keyManager.listen(
            locationKey,
            owner,
            false,
            object : CommonCallbacks.KeyListener<LocationCoordinate3D> {
                override fun onValueChange(
                    oldValue: LocationCoordinate3D?,
                    newValue: LocationCoordinate3D?,
                ) {
                    val value = newValue ?: return
                    latest.set(
                        AircraftOsdSnapshot(
                            latitude = value.latitude,
                            longitude = value.longitude,
                            altitude = value.altitude,
                            observationSequence = sequence.incrementAndGet(),
                            capturedAtMonotonicMs = nowMonotonicMs(),
                        ),
                    )
                }
            },
        )
    }

    override fun current(): AircraftOsdSnapshot? = latest.get()

    override fun close() {
        keyManager.cancelListen(locationKey, owner)
        latest.set(null)
    }
}

class VisibleFireLaserLocator(
    private val missionHold: MissionHoldControl,
    private val flightControl: FlightControlActionClient,
    private val velocityProvider: AircraftVelocityProvider = DjiAircraftVelocityProvider(),
    private val time: VisibleFireTime = SystemVisibleFireTime,
    private val targetAimer: VisibleTargetAimer = UnsupportedVisibleTargetAimer,
    private val localTargetAimer: LocalVisibleTargetAimerPort? = null,
    private val aircraftOsdProvider: AircraftOsdSnapshotProvider =
        AircraftOsdSnapshotProvider { null },
    private val laserSampleValidator: LaserSampleValidator = LaserSampleValidator(),
    private val laserRangefinder: LaserRangefinderClient = NoopLaserRangefinderClient,
    private val laserObservationClient: BoundLaserObservationClient? =
        laserRangefinder as? BoundLaserObservationClient,
    private val sourceGenerationGuard: VisibleSourceGenerationGuard =
        VisibleSourceGenerationGuard { null },
) {
    private data class VerifiedHoldBinding(
        val controlSession: FireControlSessionKey,
        val sessionId: String,
        val eventId: String,
        val coordinatorGeneration: Long,
        val missionToken: MissionHoldToken,
        val stableHoverEvidence: StableHoverEvidence,
    )

    private sealed interface Ownership {
        val generation: Long
        val eventId: String
        data class LocalHolding(
            val sessionId: String,
            override val eventId: String,
            override val generation: Long,
        ) : Ownership
        data class LocalHeld(
            val sessionId: String,
            override val eventId: String,
            override val generation: Long,
            val verifiedBinding: VerifiedHoldBinding? = null,
        ) : Ownership
        data class LocalOperating(
            val sessionId: String,
            override val eventId: String,
            override val generation: Long,
            val verifiedBinding: VerifiedHoldBinding? = null,
        ) : Ownership
        data class LegacyHolding(override val eventId: String, override val generation: Long) : Ownership
        data class LegacyHeld(override val eventId: String, override val generation: Long) : Ownership
        data class LegacyOperating(override val eventId: String, override val generation: Long) : Ownership
    }

    private val ownershipGeneration = AtomicLong()
    private val controlMutex = Mutex()
    @Volatile
    private var ownership: Ownership? = null

    /** Read-only arming snapshot; exact ownership mutations remain mutex-bound. */
    fun hasActiveOwnership(): Boolean = ownership != null

    suspend fun hold(eventId: String): DualStreamSessionManager.CommandExecutionResult =
        controlMutex.withLock { holdLocked(sessionId = null, eventId = eventId) }

    suspend fun holdLocal(
        sessionId: String,
        eventId: String,
    ): DualStreamSessionManager.CommandExecutionResult = controlMutex.withLock {
        if (sessionId.isBlank()) return@withLock failureFor(eventId, "session-id-required")
        holdLocked(sessionId, eventId)
    }

    /**
     * Adopts Task 7's already-observed pause and stable-hover proofs. This is
     * the coordinator-only path: it never submits another pause or hover.
     */
    suspend fun adoptVerifiedHold(
        missionToken: MissionHoldToken,
        stableHoverEvidence: StableHoverEvidence,
        controlSession: FireControlSessionKey,
        sessionId: String,
        eventId: String,
        generation: Long,
    ): VerifiedHoldAdoptionResult = controlMutex.withLock {
        if (sessionId.isBlank() || eventId.isBlank() || generation <= 0 ||
            !missionToken.breakpoint.isValid
        ) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.INVALID_IDENTITY,
            )
        }
        if (controlSession.sessionId != sessionId || controlSession.generation != generation) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.CONTROL_SESSION_MISMATCH,
            )
        }
        val hoverBinding = stableHoverEvidence.binding
        if (hoverBinding.controlSession != controlSession) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.CONTROL_SESSION_MISMATCH,
            )
        }
        if (hoverBinding.mission != missionToken.mission) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.MISSION_MISMATCH,
            )
        }
        if (hoverBinding.pausedCommandGeneration != missionToken.pausedCommandGeneration) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.PAUSE_GENERATION_MISMATCH,
            )
        }
        if (stableHoverEvidence.hoverEpoch < 0 ||
            stableHoverEvidence.issuedAtMonotonicMs < 0 || stableHoverEvidence.nonce <= 0
        ) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.INVALID_HOVER_EVIDENCE,
            )
        }
        val binding = VerifiedHoldBinding(
            controlSession,
            sessionId,
            eventId,
            generation,
            missionToken,
            stableHoverEvidence,
        )
        val current = ownership
        if (current is Ownership.LocalHeld && current.verifiedBinding == binding) {
            return@withLock VerifiedHoldAdoptionResult.Adopted
        }
        if (current != null) {
            return@withLock VerifiedHoldAdoptionResult.Rejected(
                VerifiedHoldAdoptionFailure.OWNER_BUSY,
            )
        }
        ownership = Ownership.LocalHeld(
            sessionId = sessionId,
            eventId = eventId,
            generation = ownershipGeneration.incrementAndGet(),
            verifiedBinding = binding,
        )
        VerifiedHoldAdoptionResult.Adopted
    }

    /**
     * Disables laser hardware and clears only the exact adopted coordinator
     * owner. A stale cleanup request cannot disturb another fire session.
     * Returns false for an identity mismatch; hardware cleanup failures throw
     * after exact ownership has still been cleared.
     */
    suspend fun forceSafeCleanup(
        controlSession: FireControlSessionKey,
        sessionId: String,
        eventId: String,
        generation: Long,
    ): Boolean = controlMutex.withLock {
        val current = ownership
        if (current != null && !current.matchesAdoptedOwner(
                controlSession,
                sessionId,
                eventId,
                generation,
            )
        ) return@withLock false

        var cleanupFailure: Throwable? = null
        try {
            withContext(NonCancellable) {
                try {
                    laserRangefinder.disable()
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                }
            }
        } finally {
            if (current != null) compareAndClear(current)
        }
        cleanupFailure?.let { failure ->
            when (failure) {
                is CancellationException -> throw failure
                is Error -> throw failure
                else -> throw IllegalStateException("laser-force-safe-cleanup-failed", failure)
            }
        }
        true
    }

    /**
     * Startup-recovery escape hatch for a fresh process that has no durable
     * in-memory ownership token to present. It always attempts to disable the
     * laser and clears local (never legacy command-route) ownership. Recovery
     * must stop when this method surfaces a hardware cleanup failure.
     */
    suspend fun forceSafeStartupCleanup() = controlMutex.withLock {
        val localOwner = ownership?.takeIf { it.isLocalOwner() }
        var cleanupFailure: Throwable? = null
        try {
            withContext(NonCancellable) {
                try {
                    laserRangefinder.disable()
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                }
            }
        } finally {
            localOwner?.let(::compareAndClear)
        }
        cleanupFailure?.let { failure ->
            when (failure) {
                is CancellationException -> throw failure
                is Error -> throw failure
                else -> throw IllegalStateException("laser-startup-cleanup-failed", failure)
            }
        }
    }

    private suspend fun holdLocked(
        sessionId: String?,
        eventId: String,
    ): DualStreamSessionManager.CommandExecutionResult {
        if (eventId.isBlank()) return failureFor(eventId, "event-id-required")
        if (ownership != null) return failureFor(eventId, "localization-owner-busy")
        val generation = ownershipGeneration.incrementAndGet()
        val holding: Ownership = if (sessionId == null) {
            Ownership.LegacyHolding(eventId, generation)
        } else {
            Ownership.LocalHolding(sessionId, eventId, generation)
        }
        ownership = holding
        try {
            val routePaused = try {
                missionHold.holdForConfirmation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!routePaused) {
                try {
                    flightControl.hover()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return failureFor(eventId, "hover-command-failed")
                }
            }
            val startedAt = time.nowMs()
            var stableSince: Long? = null
            while (time.nowMs() - startedAt <= HOVER_TIMEOUT_MS) {
                val sample = velocityProvider.current()
                val stable = sample != null &&
                    sample.horizontalMps <= MAX_HORIZONTAL_SPEED_MPS &&
                    abs(sample.verticalMps) <= MAX_VERTICAL_SPEED_MPS
                val now = time.nowMs()
                if (stable) {
                    if (stableSince == null) {
                        stableSince = now
                    }
                    if (now - stableSince >= REQUIRED_STABLE_MS) {
                        ownership = if (holding is Ownership.LocalHolding) {
                            Ownership.LocalHeld(holding.sessionId, eventId, generation)
                        } else {
                            Ownership.LegacyHeld(eventId, generation)
                        }
                        return DualStreamSessionManager.CommandExecutionResult(
                            status = "applied",
                            message = "HOVER_STABLE",
                            eventId = eventId,
                        )
                    }
                } else {
                    stableSince = null
                }
                time.delayMs(VELOCITY_POLL_MS)
            }
            return failureFor(eventId, "hover-stability-timeout")
        } finally {
            // A successful hold has already transitioned to *Held. Every
            // exception/cancellation/timeout clears only this exact transient
            // token so a later hold can recover without stealing another owner.
            compareAndClear(holding)
        }
    }

    suspend fun measure(
        eventId: String,
        taskId: String,
        visibleRoi: Map<String, Double>,
    ): DualStreamSessionManager.CommandExecutionResult = controlMutex.withLock {
        val held = ownership as? Ownership.LegacyHeld
        if (held == null || held.eventId != eventId) return@withLock failureFor(
            eventId,
            if (ownership is Ownership.LocalHolding || ownership is Ownership.LocalHeld ||
                ownership is Ownership.LocalOperating) "legacy-route-blocked-by-local-owner"
            else "event-session-mismatch",
        )
        val operating = Ownership.LegacyOperating(eventId, held.generation)
        ownership = operating
        try {
            if (!isValidRoi(visibleRoi) || !targetAimer.align(taskId, visibleRoi)) {
                return failureFor(eventId, "target-not-aligned")
            }
            val samples = mutableListOf<LaserRangefinderResult>()
            repeat(LASER_SAMPLE_COUNT) { index ->
                val sample = runCatching { laserRangefinder.measure() }.getOrNull()
                if (sample != null &&
                    sample.state.equals("NORMAL", ignoreCase = true) &&
                    sample.latitude?.isFinite() == true &&
                    sample.longitude?.isFinite() == true
                ) {
                    samples += sample
                }
                if (index < LASER_SAMPLE_COUNT - 1) {
                    time.delayMs(LASER_SAMPLE_INTERVAL_MS)
                }
            }
            if (samples.size < LASER_SAMPLE_COUNT || samples.hasScatterBeyond(LASER_SCATTER_LIMIT_M)) {
                return failureFor(eventId, "laser-fix-unavailable")
            }
            return DualStreamSessionManager.CommandExecutionResult(
                status = "applied",
                message = "LASER_LOCATED",
                eventId = eventId,
                fireLat = samples.mapNotNull { it.latitude }.median(),
                fireLng = samples.mapNotNull { it.longitude }.median(),
                fireAlt = samples.mapNotNull { it.altitude }.medianOrNull(),
                geoMethod = "LASER_RANGEFINDER",
                geoQuality = "PRECISE",
                geoErrorRadiusM = LASER_ERROR_RADIUS_M,
                sourceTs = System.currentTimeMillis(),
            )
        } finally {
            runCatching { laserRangefinder.disable() }
            compareAndClear(operating)
        }
    }

    suspend fun localize(
        request: FireLocalizationRequest,
        onLaserMeasurementBoundary: suspend () -> Boolean = { true },
    ): FireLocalizationResult = controlMutex.withLock {
        val held = ownership as? Ownership.LocalHeld
        if (held == null || held.sessionId != request.sessionId || held.eventId != request.eventId) {
            return@withLock degradedOrManualHold(
                request,
                FireLocalizationFailure.EVENT_SESSION_MISMATCH,
            )
        }
        val operating = Ownership.LocalOperating(
            held.sessionId,
            held.eventId,
            held.generation,
            held.verifiedBinding,
        )
        ownership = operating
        var hardwareToken: LaserHardwareOperationToken? = null
        try {
            localizeOwned(request, operating, onLaserMeasurementBoundary) {
                hardwareToken = it
            }
        } finally {
            hardwareToken?.let { token ->
                withContext(NonCancellable) {
                    runCatching { laserObservationClient?.endOperation(token) }
                }
            } ?: withContext(NonCancellable) {
                runCatching { laserRangefinder.disable() }
            }
            compareAndClear(operating)
        }
    }

    private suspend fun localizeOwned(
        request: FireLocalizationRequest,
        operating: Ownership.LocalOperating,
        onLaserMeasurementBoundary: suspend () -> Boolean,
        onHardwareToken: (LaserHardwareOperationToken) -> Unit,
    ): FireLocalizationResult {
        val aimer = localTargetAimer ?: return degradedOrManualHold(
            request,
            FireLocalizationFailure.TARGET_NOT_ALIGNED,
        )
        val aimed = try {
            aimer.align(
                LocalTargetAimRequest(
                    sessionId = request.sessionId,
                    eventId = request.eventId,
                    kind = request.kind,
                    priorRoi = request.initialRoi,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return degradedOrManualHold(request, FireLocalizationFailure.TARGET_NOT_ALIGNED)
        }
        if (aimed !is LocalTargetAimResult.Aligned || aimed.kind != request.kind) {
            val failure = if (aimed is LocalTargetAimResult.Failed &&
                aimed.reason == LocalTargetAimFailure.DETECTION_TIMEOUT
            ) FireLocalizationFailure.TARGET_DETECTION_TIMEOUT
            else FireLocalizationFailure.TARGET_NOT_ALIGNED
            return degradedOrManualHold(request, failure)
        }
        if (!sameVisibleGeneration(aimed.sourceGeneration)) {
            return degradedOrManualHold(request, FireLocalizationFailure.SOURCE_GENERATION_CHANGED)
        }
        val boundaryAccepted = try {
            onLaserMeasurementBoundary()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!boundaryAccepted) {
            return FireLocalizationResult.ManualHold(
                request.kind,
                FireLocalizationFailure.LASER_ENABLE_FAILED,
            )
        }
        if (!sameVisibleGeneration(aimed.sourceGeneration)) {
            return degradedOrManualHold(request, FireLocalizationFailure.SOURCE_GENERATION_CHANGED)
        }
        val observationClient = laserObservationClient ?: return degradedOrManualHold(
            request,
            FireLocalizationFailure.LASER_ENABLE_FAILED,
        )
        val windowStarted = time.nowMs()
        val binding = LaserOperationBinding(
            sessionId = request.sessionId,
            eventId = request.eventId,
            targetRoi = aimed.roi,
            sourceGeneration = aimed.sourceGeneration,
            operationGeneration = operating.generation,
            windowStartedAtMonotonicMs = windowStarted,
            windowEndsAtMonotonicMs = windowStarted + LASER_OPERATION_WINDOW_MS,
        )
        val accepted = mutableListOf<BoundLaserSample>()
        val token = try {
            observationClient.beginOperation(binding)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return degradedOrManualHold(request, FireLocalizationFailure.LASER_ENABLE_FAILED)
        }
        onHardwareToken(token)
        if (!sameVisibleGeneration(aimed.sourceGeneration)) {
            return degradedOrManualHold(request, FireLocalizationFailure.SOURCE_GENERATION_CHANGED)
        }
        var observationCursor = token.observationCursorAtEnable
        repeat(MAX_LASER_ATTEMPTS) {
            if (accepted.size == LASER_SAMPLE_COUNT) return@repeat
            val awaited = try {
                observationClient.awaitNext(token, observationCursor, LASER_OBSERVATION_TIMEOUT_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LaserHardwareAwaitResult.Timeout
            }
            when (awaited) {
                LaserHardwareAwaitResult.Overflow -> return degradedOrManualHold(
                    request,
                    FireLocalizationFailure.LASER_CALLBACK_OVERFLOW,
                )
                LaserHardwareAwaitResult.Timeout -> Unit
                is LaserHardwareAwaitResult.Observed -> {
                    val sample = awaited.sample
                    val isNewObservation = sample.observationSequence > observationCursor
                    observationCursor = maxOf(observationCursor, sample.observationSequence)
                    if (!sameVisibleGeneration(aimed.sourceGeneration)) {
                        return degradedOrManualHold(
                            request,
                            FireLocalizationFailure.SOURCE_GENERATION_CHANGED,
                        )
                    }
                    val intervalEligible = accepted.lastOrNull()?.let { previous ->
                        sample.sampledAtMonotonicMs - previous.sampledAtMonotonicMs >=
                            LASER_SAMPLE_INTERVAL_MS
                    } ?: true
                    if (isNewObservation && intervalEligible &&
                        laserSampleValidator.isAcceptableCandidate(binding, sample)
                    ) accepted += sample
                }
            }
        }
        if (accepted.size != LASER_SAMPLE_COUNT) {
            return degradedOrManualHold(
                request,
                FireLocalizationFailure.LASER_SAMPLES_UNAVAILABLE,
            )
        }
        return when (val validated = laserSampleValidator.validate(binding, accepted)) {
            is LaserValidationResult.Valid -> {
                if (!sameVisibleGeneration(aimed.sourceGeneration)) {
                    degradedOrManualHold(request, FireLocalizationFailure.SOURCE_GENERATION_CHANGED)
                } else {
                    FireLocalizationResult.Precise(
                        kind = request.kind,
                        fireLatitude = validated.latitude,
                        fireLongitude = validated.longitude,
                        fireAltitude = validated.altitude,
                        rangeM = validated.rangeM,
                        errorRadiusM = validated.errorRadiusM,
                        rawSamples = validated.rawSamples,
                        targetRoi = aimed.roi,
                        sourceGeneration = aimed.sourceGeneration,
                    )
                }
            }
            is LaserValidationResult.Invalid -> degradedOrManualHold(
                request,
                FireLocalizationFailure.LASER_SAMPLES_INVALID,
            )
        }
    }

    private fun degradedOrManualHold(
        request: FireLocalizationRequest,
        failure: FireLocalizationFailure,
    ): FireLocalizationResult {
        val failureAt = time.nowMs()
        val osd = runCatching { aircraftOsdProvider.current() }.getOrNull()
        return if (osd != null && osd.valid &&
            osd.isFreshAt(failureAt, MAX_OSD_AGE_AT_FAILURE_MS)
        ) {
            FireLocalizationResult.DegradedOsd(request.kind, failure, osd)
        } else {
            FireLocalizationResult.ManualHold(
                request.kind,
                FireLocalizationFailure.AIRCRAFT_OSD_UNAVAILABLE,
            )
        }
    }

    private fun sameVisibleGeneration(expected: Long): Boolean =
        sourceGenerationGuard.currentVisibleGeneration() == expected

    private fun compareAndClear(expected: Ownership) {
        if (ownership == expected) ownership = null
    }

    private fun Ownership.matchesAdoptedOwner(
        controlSession: FireControlSessionKey,
        sessionId: String,
        eventId: String,
        generation: Long,
    ): Boolean {
        val binding = when (this) {
            is Ownership.LocalHeld -> verifiedBinding
            is Ownership.LocalOperating -> verifiedBinding
            else -> null
        } ?: return false
        return binding.controlSession == controlSession &&
            binding.sessionId == sessionId && binding.eventId == eventId &&
            binding.coordinatorGeneration == generation
    }

    private fun Ownership.isLocalOwner(): Boolean = when (this) {
        is Ownership.LocalHolding,
        is Ownership.LocalHeld,
        is Ownership.LocalOperating -> true
        else -> false
    }

    private fun failureFor(eventId: String, reason: String) =
        DualStreamSessionManager.CommandExecutionResult(
            status = "failed",
            message = "LASER_FAILED:$reason",
            eventId = eventId,
            sourceTs = System.currentTimeMillis(),
        )

    private fun isValidRoi(roi: Map<String, Double>): Boolean {
        return roi.isNormalizedRoi()
    }

    companion object {
        const val MAX_HORIZONTAL_SPEED_MPS = 0.3
        const val MAX_VERTICAL_SPEED_MPS = 0.2
        const val REQUIRED_STABLE_MS = 1_000L
        const val HOVER_TIMEOUT_MS = 8_000L
        const val VELOCITY_POLL_MS = 200L
        const val LASER_SAMPLE_COUNT = 3
        const val LASER_SAMPLE_INTERVAL_MS = 300L
        const val LASER_SCATTER_LIMIT_M = 15.0
        const val LASER_ERROR_RADIUS_M = 5.0
        const val MAX_LASER_ATTEMPTS = 9
        const val LASER_OPERATION_WINDOW_MS = 20_000L
        const val LASER_OBSERVATION_TIMEOUT_MS = 1_000L
        const val MAX_OSD_AGE_AT_FAILURE_MS = 2_000L
    }
}

private fun Map<String, Double>.isNormalizedRoi(): Boolean {
    val x = this["x"] ?: return false
    val y = this["y"] ?: return false
    val width = this["width"] ?: return false
    val height = this["height"] ?: return false
    return x in 0.0..1.0 && y in 0.0..1.0 &&
        width > 0.0 && height > 0.0 &&
        x + width <= 1.000001 && y + height <= 1.000001
}

private fun Map<String, Double>.containsPoint(x: Double, y: Double): Boolean {
    if (!isNormalizedRoi()) {
        return false
    }
    val left = getValue("x")
    val top = getValue("y")
    return x >= left && x <= left + getValue("width") &&
        y >= top && y <= top + getValue("height")
}

private fun List<LaserRangefinderResult>.hasScatterBeyond(limitM: Double): Boolean {
    for (first in indices) {
        for (second in first + 1 until size) {
            if (laserDistanceM(this[first], this[second]) > limitM) {
                return true
            }
        }
    }
    return false
}

private fun laserDistanceM(a: LaserRangefinderResult, b: LaserRangefinderResult): Double {
    val latitudeA = a.latitude ?: return Double.POSITIVE_INFINITY
    val longitudeA = a.longitude ?: return Double.POSITIVE_INFINITY
    val latitudeB = b.latitude ?: return Double.POSITIVE_INFINITY
    val longitudeB = b.longitude ?: return Double.POSITIVE_INFINITY
    val metersPerLng = 111_320.0 * cos(Math.toRadians((latitudeA + latitudeB) / 2.0))
    return hypot(
        (longitudeA - longitudeB) * metersPerLng,
        (latitudeA - latitudeB) * 111_320.0,
    )
}

private fun List<Double>.median(): Double {
    val sorted = sorted()
    return sorted[sorted.size / 2]
}

private fun List<Double>.medianOrNull(): Double? =
    takeIf { it.isNotEmpty() }?.median()
