package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.wayline.MissionExecutionSnapshot as DjiMissionSnapshot
import com.yinxin.uavfir.wayline.WaypointMissionExecutor
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.manager.aircraft.waypoint3.model.BreakPointInfo
import dji.v5.manager.aircraft.waypoint3.model.RecoverActionType
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class ObservedMissionState {
    IDLE, READY, EXECUTING, INTERRUPTED, RECOVERING, FINISHED, UNKNOWN,
}

data class MissionSnapshot(
    val mission: MissionExecutionKey?,
    val state: ObservedMissionState,
    val commandGeneration: Long,
)

data class MissionCommandError(val reason: String)

data class MissionCommandCallback(
    val mission: MissionExecutionKey,
    val commandGeneration: Long,
    val error: MissionCommandError?,
)

data class MissionCommandSubmission(
    val mission: MissionExecutionKey,
    val commandGeneration: Long,
    val cancellation: MissionCancellation,
)

fun interface MissionCancellation {
    fun cancel()
}

interface MissionControlPort {
    fun snapshot(): MissionSnapshot
    fun queryBreakpoint(
        mission: MissionExecutionKey,
        callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
    ): MissionCancellation
    fun pause(
        mission: MissionExecutionKey,
        onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
        callback: (MissionCommandCallback) -> Unit,
    ): MissionCommandSubmission
    fun resume(
        mission: MissionExecutionKey,
        breakpoint: MissionBreakpoint,
        onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
        callback: (MissionCommandCallback) -> Unit,
    ): MissionCommandSubmission
    /** Atomically subscribes and replays the current full snapshot. */
    fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation
}

class WaypointMissionControlPort(
    private val executor: WaypointMissionExecutor,
) : MissionControlPort {
    override fun snapshot(): MissionSnapshot = executor.currentMissionExecution().toMissionSnapshot()

    override fun queryBreakpoint(
        mission: MissionExecutionKey,
        callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
    ): MissionCancellation {
        val active = AtomicBoolean(true)
        executor.queryBreakpoint(mission.identity.missionFileName) { info, error ->
            if (!active.compareAndSet(true, false)) return@queryBreakpoint
            when {
                snapshot().mission != mission -> callback(null, MissionCommandError("mission-generation-changed"))
                error != null -> callback(null, MissionCommandError(error.description()))
                else -> callback(info?.toMissionBreakpointOrNull(), null)
            }
        }
        return MissionCancellation { active.set(false) }
    }

    override fun pause(
        mission: MissionExecutionKey,
        onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
        callback: (MissionCommandCallback) -> Unit,
    ): MissionCommandSubmission {
        val active = AtomicBoolean(true)
        val expected = executor.currentMissionExecution()
        if (expected.toMissionSnapshot().mission != mission) error("mission-generation-changed")
        val submitted = executor.pauseMission(
            expected = expected,
            onSubmissionBoundary = { evidence ->
                onSubmissionBoundary(
                    MissionCommandSubmission(
                        mission,
                        evidence.commandGeneration,
                        MissionCancellation { active.set(false) },
                    ),
                )
            },
            onComplete = { evidence, error ->
                if (active.compareAndSet(true, false)) {
                    callback(evidence.toCommandCallback(error?.description()))
                }
            },
        ) ?: error("mission-generation-changed")
        return MissionCommandSubmission(
            mission,
            submitted.snapshot.commandGeneration,
            MissionCancellation { active.set(false) },
        )
    }

    override fun resume(
        mission: MissionExecutionKey,
        breakpoint: MissionBreakpoint,
        onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
        callback: (MissionCommandCallback) -> Unit,
    ): MissionCommandSubmission {
        require(breakpoint.isValid) { "invalid-breakpoint" }
        val active = AtomicBoolean(true)
        val expected = executor.currentMissionExecution()
        if (expected.toMissionSnapshot().mission != mission) error("mission-generation-changed")
        val submitted = executor.resumeMission(
            expected = expected,
            breakpoint = breakpoint.toDjiBreakpoint(),
            onSubmissionBoundary = { evidence ->
                onSubmissionBoundary(
                    MissionCommandSubmission(
                        mission,
                        evidence.commandGeneration,
                        MissionCancellation { active.set(false) },
                    ),
                )
            },
            onComplete = { evidence, error ->
                if (active.compareAndSet(true, false)) {
                    callback(evidence.toCommandCallback(error?.description()))
                }
            },
        ) ?: error("mission-generation-changed")
        return MissionCommandSubmission(
            mission,
            submitted.snapshot.commandGeneration,
            MissionCancellation { active.set(false) },
        )
    }

    override fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation {
        val remove = executor.observeMissionExecution { listener(it.toMissionSnapshot()) }
        return MissionCancellation(remove)
    }

    private fun DjiMissionSnapshot.toMissionSnapshot(): MissionSnapshot {
        val state = state.toObservedState()
        val mission = identity?.let {
            MissionExecutionKey(
                MissionIdentity(it.missionId, it.missionFileName),
                missionGeneration,
            )
        }
        return MissionSnapshot(mission, state, commandGeneration)
    }

    private fun DjiMissionSnapshot.toCommandCallback(error: String?): MissionCommandCallback {
        val mission = toMissionSnapshot().mission ?: error("command-mission-missing")
        return MissionCommandCallback(mission, commandGeneration, error?.let(::MissionCommandError))
    }

    private fun WaypointMissionExecuteState?.toObservedState() = when (this) {
        WaypointMissionExecuteState.IDLE -> ObservedMissionState.IDLE
        WaypointMissionExecuteState.READY -> ObservedMissionState.READY
        WaypointMissionExecuteState.EXECUTING -> ObservedMissionState.EXECUTING
        WaypointMissionExecuteState.INTERRUPTED -> ObservedMissionState.INTERRUPTED
        WaypointMissionExecuteState.RECOVERING -> ObservedMissionState.RECOVERING
        WaypointMissionExecuteState.FINISHED -> ObservedMissionState.FINISHED
        else -> ObservedMissionState.UNKNOWN
    }

    private fun BreakPointInfo.toMissionBreakpointOrNull(): MissionBreakpoint? {
        val candidate = MissionBreakpoint(
            waylineId = waylineID ?: return null,
            waypointId = waypointID ?: return null,
            segmentProgress = segmentProgress ?: return null,
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude,
            recoverAction = recoverActionType?.name,
        )
        return candidate.takeIf { it.isValid }
    }

    private fun MissionBreakpoint.toDjiBreakpoint(): BreakPointInfo {
        check(isValid) { "invalid-breakpoint" }
        val info = BreakPointInfo(waylineId, waypointId, segmentProgress)
        if (latitude != null && longitude != null && altitude != null) {
            info.location = LocationCoordinate3D(latitude, longitude, altitude)
        }
        recoverAction?.let { info.recoverActionType = RecoverActionType.valueOf(it) }
        return info
    }
}

data class MissionHoldToken(
    val mission: MissionExecutionKey,
    val breakpoint: MissionBreakpoint,
)

sealed interface MissionHoldResult {
    data class WaylinePaused(val token: MissionHoldToken) : MissionHoldResult
    data object HoveringNoWayline : MissionHoldResult
    data class ManualHold(val reason: FlightSafetyReason) : MissionHoldResult
}

sealed interface MissionResumeResult {
    data object Resumed : MissionResumeResult
    data class ManualHold(val reason: FlightSafetyReason) : MissionResumeResult
}

private enum class ResumeLifecycle {
    NOT_ISSUED, SUBMITTED, SUCCEEDED, FAILED, OUTCOME_UNKNOWN,
}

class AwaitableMissionControl(
    private val port: MissionControlPort,
    private val hover: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val safetyGate: FlightSafetyGate,
    private val safetyProvider: ResumeSafetyEvidenceProvider,
    private val monotonicNow: () -> Long,
) : AutoCloseable {
    private val lock = Any()
    private var pauseOperation: SharedOperation<MissionHoldResult>? = null
    private var resumeOperation: SharedOperation<MissionResumeResult>? = null
    private var heldToken: MissionHoldToken? = null
    private var resumedToken: MissionHoldToken? = null
    private val resumeLifecycle = mutableMapOf<MissionHoldToken, ResumeLifecycle>()
    private var manualTakeover = false
    private var closed = false

    suspend fun pause(): MissionHoldResult {
        val captured = port.snapshot()
        val operation = synchronized(lock) {
            if (closed) return MissionHoldResult.ManualHold(FlightSafetyReason.CONTROL_CLOSED)
            if (manualTakeover) return MissionHoldResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER)
            heldToken?.takeIf {
                captured.mission == it.mission && captured.state == ObservedMissionState.INTERRUPTED
            }?.let { return MissionHoldResult.WaylinePaused(it) }
            val key = captured.mission
            pauseOperation?.let {
                if (it.key != key) return MissionHoldResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
                it.waiters++
                it
            } ?: newPauseOperation(captured)
        }
        return awaitShared(operation, MissionHoldResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER))
    }

    suspend fun resume(
        token: MissionHoldToken,
        controlSession: FireControlSessionKey,
    ): MissionResumeResult {
        val lifecycle = synchronized(lock) { resumeLifecycle[token] }
        if (lifecycle == ResumeLifecycle.OUTCOME_UNKNOWN) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN)
        }
        if (lifecycle == ResumeLifecycle.FAILED) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_COMMAND_FAILED)
        }
        val operation = synchronized(lock) {
            if (closed) return MissionResumeResult.ManualHold(FlightSafetyReason.CONTROL_CLOSED)
            if (manualTakeover) return MissionResumeResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER)
            resumedToken?.takeIf {
                it == token && port.snapshot().let { s ->
                    s.mission == token.mission && s.state == ObservedMissionState.EXECUTING
                }
            }?.let { return MissionResumeResult.Resumed }
            val operationKey = ResumeOperationKey(token, controlSession)
            resumeOperation?.let {
                if (it.key != operationKey) {
                    return MissionResumeResult.ManualHold(FlightSafetyReason.COMPETING_FIRE_SESSION)
                }
                it.waiters++
                it
            } ?: newResumeOperation(token, controlSession)
        }
        return awaitShared(operation, MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN))
    }

    fun onManualControlTakeover() {
        val jobs = synchronized(lock) {
            manualTakeover = true
            listOfNotNull(pauseOperation?.deferred, resumeOperation?.deferred)
        }
        jobs.forEach { it.cancel(CancellationException("manual-control-takeover")) }
    }

    override fun close() {
        val jobs = synchronized(lock) {
            if (closed) return
            closed = true
            listOfNotNull(pauseOperation?.deferred, resumeOperation?.deferred)
        }
        jobs.forEach { it.cancel(CancellationException("mission-control-closed")) }
    }

    private fun newPauseOperation(captured: MissionSnapshot): SharedOperation<MissionHoldResult> {
        val deferred = scope.async(start = CoroutineStart.LAZY) { performPause(captured) }
        val op = SharedOperation(deferred, 1, captured.mission)
        pauseOperation = op
        deferred.invokeOnCompletion {
            synchronized(lock) {
                if (pauseOperation === op) pauseOperation = null
                (deferred.completedOrNull() as? MissionHoldResult.WaylinePaused)?.let {
                    heldToken = it.token
                    resumedToken = null
                }
            }
        }
        deferred.start()
        return op
    }

    private fun newResumeOperation(
        token: MissionHoldToken,
        controlSession: FireControlSessionKey,
    ): SharedOperation<MissionResumeResult> {
        val deferred = scope.async(start = CoroutineStart.LAZY) { performResume(token, controlSession) }
        val op = SharedOperation(deferred, 1, ResumeOperationKey(token, controlSession))
        resumeOperation = op
        resumeLifecycle.putIfAbsent(token, ResumeLifecycle.NOT_ISSUED)
        deferred.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (resumeOperation === op) resumeOperation = null
                if (cause is CancellationException && resumeLifecycle[token] == ResumeLifecycle.SUBMITTED) {
                    resumeLifecycle[token] = ResumeLifecycle.OUTCOME_UNKNOWN
                }
                when (val result = deferred.completedOrNull()) {
                    MissionResumeResult.Resumed -> {
                        resumeLifecycle[token] = ResumeLifecycle.SUCCEEDED
                        resumedToken = token
                        heldToken = null
                    }
                    is MissionResumeResult.ManualHold -> {
                        if (resumeLifecycle[token] == ResumeLifecycle.SUBMITTED) {
                            resumeLifecycle[token] =
                                if (result.reason == FlightSafetyReason.RESUME_OUTCOME_UNKNOWN) {
                                    ResumeLifecycle.OUTCOME_UNKNOWN
                                } else {
                                    ResumeLifecycle.FAILED
                                }
                        }
                    }
                    null -> Unit
                }
            }
        }
        deferred.start()
        return op
    }

    private suspend fun performPause(captured: MissionSnapshot): MissionHoldResult {
        val mission = captured.mission
        if (mission == null) {
            return if (captured.state in NO_ACTIVE_STATES) {
                runCatching { hover() }.fold(
                    { MissionHoldResult.HoveringNoWayline },
                    { MissionHoldResult.ManualHold(FlightSafetyReason.HOVER_COMMAND_FAILED) },
                )
            } else MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        }
        if (captured.state !in setOf(ObservedMissionState.EXECUTING, ObservedMissionState.INTERRUPTED)) {
            return MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        }
        val breakpoint = awaitBreakpoint(mission)
            ?: return MissionHoldResult.ManualHold(FlightSafetyReason.MISSING_BREAKPOINT)
        if (!breakpoint.isValid) return MissionHoldResult.ManualHold(FlightSafetyReason.INVALID_BREAKPOINT)
        val beforeCommand = port.snapshot()
        if (beforeCommand.mission != mission) {
            return MissionHoldResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
        }
        val token = MissionHoldToken(mission, breakpoint)
        if (beforeCommand.state == ObservedMissionState.INTERRUPTED) return MissionHoldResult.WaylinePaused(token)
        val outcome = awaitCommandAndState(
            mission,
            ObservedMissionState.INTERRUPTED,
            setOf(ObservedMissionState.EXECUTING, ObservedMissionState.INTERRUPTED),
        ) { callback, boundary -> port.pause(mission, boundary, callback) }
        return when {
            outcome.error == null &&
                exactState(mission, ObservedMissionState.INTERRUPTED, outcome.commandGeneration) ->
                MissionHoldResult.WaylinePaused(token)
            outcome.error?.reason == "mission-generation-changed" ->
                MissionHoldResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
            else -> MissionHoldResult.ManualHold(FlightSafetyReason.PAUSE_COMMAND_FAILED)
        }
    }

    private suspend fun performResume(
        token: MissionHoldToken,
        controlSession: FireControlSessionKey,
    ): MissionResumeResult {
        val initial = port.snapshot()
        if (initial.mission != token.mission) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
        }
        if (initial.state != ObservedMissionState.INTERRUPTED) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        }
        val breakpoint = awaitBreakpoint(token.mission)
            ?: return MissionResumeResult.ManualHold(FlightSafetyReason.MISSING_BREAKPOINT)
        if (!breakpoint.isValid) return MissionResumeResult.ManualHold(FlightSafetyReason.INVALID_BREAKPOINT)
        if (breakpoint != token.breakpoint) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.BREAKPOINT_MISMATCH)
        }
        val immediatelyBeforeSubmission = port.snapshot()
        if (immediatelyBeforeSubmission.mission != token.mission ||
            immediatelyBeforeSubmission.state != ObservedMissionState.INTERRUPTED
        ) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
        }
        val evidence = safetyProvider.current(controlSession)
        val safety = safetyGate.evaluateResume(
            controlSession,
            token.mission,
            token.breakpoint,
            evidence,
            monotonicNow(),
        )
        if (safety is ResumeSafetyDecision.ManualHold) {
            return MissionResumeResult.ManualHold(safety.reason)
        }
        val outcome = awaitCommandAndState(
            token.mission,
            ObservedMissionState.EXECUTING,
            setOf(ObservedMissionState.INTERRUPTED, ObservedMissionState.RECOVERING, ObservedMissionState.EXECUTING),
            onSubmitted = {
                synchronized(lock) { resumeLifecycle[token] = ResumeLifecycle.SUBMITTED }
            },
        ) { callback, boundary -> port.resume(token.mission, token.breakpoint, boundary, callback) }
        return when {
            outcome.error == null &&
                exactState(token.mission, ObservedMissionState.EXECUTING, outcome.commandGeneration) ->
                MissionResumeResult.Resumed
            outcome.error?.reason == "mission-generation-changed" ->
                MissionResumeResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
            outcome.error?.reason == "submission-outcome-unknown" ->
                MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_OUTCOME_UNKNOWN)
            else -> MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_COMMAND_FAILED)
        }
    }

    private fun exactState(
        mission: MissionExecutionKey,
        state: ObservedMissionState,
        commandGeneration: Long?,
    ): Boolean = port.snapshot().let {
        commandGeneration != null &&
            it.mission == mission &&
            it.state == state &&
            it.commandGeneration == commandGeneration
    }

    private suspend fun awaitBreakpoint(mission: MissionExecutionKey): MissionBreakpoint? =
        suspendCancellableCoroutine { continuation ->
            val handle = AtomicReference<MissionCancellation>()
            continuation.invokeOnCancellation { handle.get()?.cancel() }
            val registered = port.queryBreakpoint(mission) { breakpoint, error ->
                if (continuation.isActive) continuation.resume(if (error == null) breakpoint else null)
            }
            handle.set(registered)
            if (!continuation.isActive) registered.cancel()
        }

    private suspend fun awaitCommandAndState(
        mission: MissionExecutionKey,
        target: ObservedMissionState,
        permitted: Set<ObservedMissionState>,
        onSubmitted: (MissionCommandSubmission) -> Unit = {},
        issue: (
            callback: (MissionCommandCallback) -> Unit,
            onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
        ) -> MissionCommandSubmission,
    ): MissionCommandOutcome = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean()
        val callbackEvidence = AtomicReference<MissionCommandCallback>()
        val stateEvidence = AtomicReference<MissionSnapshot>()
        val observation = AtomicReference<MissionCancellation>()
        val submission = AtomicReference<MissionCommandSubmission>()
        val submissionRecorded = AtomicBoolean()

        fun cleanup() {
            observation.get()?.cancel()
            submission.get()?.cancellation?.cancel()
        }
        fun finish(error: MissionCommandError?, commandGeneration: Long? = submission.get()?.commandGeneration) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                if (continuation.isActive) continuation.resume(MissionCommandOutcome(error, commandGeneration))
            }
        }
        fun maybeFinish() {
            val submitted = submission.get() ?: return
            val callback = callbackEvidence.get() ?: return
            val state = stateEvidence.get() ?: return
            if (callback.mission != mission || submitted.mission != mission || state.mission != mission) {
                finish(MissionCommandError("mission-generation-changed"))
            } else if (callback.commandGeneration != submitted.commandGeneration ||
                state.commandGeneration != submitted.commandGeneration
            ) {
                finish(MissionCommandError("command-generation-changed"))
            } else if (callback.error != null) {
                finish(callback.error)
            } else {
                finish(null)
            }
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) cleanup()
        }
        val observed = port.observeSnapshots { snapshot ->
            when {
                snapshot.mission != mission -> finish(MissionCommandError("mission-generation-changed"))
                snapshot.state == target -> {
                    stateEvidence.set(snapshot)
                    maybeFinish()
                }
                snapshot.state !in permitted -> finish(MissionCommandError("unexpected-mission-state"))
            }
        }
        observation.set(observed)
        if (finished.get()) observed.cancel()
        if (finished.get()) return@suspendCancellableCoroutine

        fun recordSubmission(value: MissionCommandSubmission) {
            submission.compareAndSet(null, value)
            if (submissionRecorded.compareAndSet(false, true)) onSubmitted(value)
            maybeFinish()
        }
        val submitted = runCatching {
            issue(
                { callback ->
                    callbackEvidence.set(callback)
                    maybeFinish()
                },
                ::recordSubmission,
            )
        }.getOrElse {
            finish(
                MissionCommandError(
                    if (submissionRecorded.get()) "submission-outcome-unknown" else "submission-failed",
                ),
            )
            return@suspendCancellableCoroutine
        }
        recordSubmission(submitted)
        if (finished.get()) submitted.cancellation.cancel()
    }

    private suspend fun <T> awaitShared(op: SharedOperation<T>, cancellationResult: T): T {
        try {
            return op.deferred.await()
        } catch (_: CancellationException) {
            return cancellationResult
        } finally {
            synchronized(lock) {
                op.waiters--
                if (op.waiters == 0 && !op.deferred.isCompleted) {
                    op.deferred.cancel(CancellationException("all-callers-cancelled"))
                }
            }
        }
    }

    private data class SharedOperation<T>(val deferred: Deferred<T>, var waiters: Int, val key: Any?)
    private data class ResumeOperationKey(
        val token: MissionHoldToken,
        val controlSession: FireControlSessionKey,
    )
    private data class MissionCommandOutcome(
        val error: MissionCommandError?,
        val commandGeneration: Long?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> Deferred<T>.completedOrNull(): T? =
        if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null

    companion object {
        private val NO_ACTIVE_STATES = setOf(
            ObservedMissionState.IDLE,
            ObservedMissionState.READY,
            ObservedMissionState.FINISHED,
        )
    }
}
