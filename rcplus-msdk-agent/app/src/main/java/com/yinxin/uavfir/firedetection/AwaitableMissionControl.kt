package com.yinxin.uavfir.firedetection

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
    IDLE,
    READY,
    EXECUTING,
    INTERRUPTED,
    RECOVERING,
    FINISHED,
    UNKNOWN,
}

data class MissionSnapshot(
    val identity: MissionIdentity?,
    val state: ObservedMissionState,
)

data class MissionCommandError(val reason: String)

fun interface MissionCancellation {
    fun cancel()
}

interface MissionControlPort {
    fun snapshot(): MissionSnapshot

    fun queryBreakpoint(
        identity: MissionIdentity,
        callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
    ): MissionCancellation

    fun pause(callback: (MissionCommandError?) -> Unit): MissionCancellation

    fun resume(
        breakpoint: MissionBreakpoint,
        callback: (MissionCommandError?) -> Unit,
    ): MissionCancellation

    fun observeState(listener: (ObservedMissionState) -> Unit): MissionCancellation
}

/**
 * Thin MSDK adapter. It snapshots mission identity together, translates the
 * executor's single registered DJI listener into cancellable local observers,
 * and resumes from the exact captured breakpoint rather than a mutable
 * "current" position.
 */
class WaypointMissionControlPort(
    private val executor: WaypointMissionExecutor,
) : MissionControlPort {
    override fun snapshot(): MissionSnapshot {
        val state = executor.currentMissionState().toObservedState()
        val activeIdentity = executor.activeMissionIdentity()
        val identity = if (state in NO_ACTIVE_STATES) {
            null
        } else if (activeIdentity != null) {
            MissionIdentity(activeIdentity.first, activeIdentity.second)
        } else {
            null
        }
        return MissionSnapshot(identity, state)
    }

    override fun queryBreakpoint(
        identity: MissionIdentity,
        callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
    ): MissionCancellation {
        val active = AtomicBoolean(true)
        executor.queryBreakpoint(identity.missionFileName) { info, error ->
            if (!active.compareAndSet(true, false)) return@queryBreakpoint
            val unchanged = snapshot().identity == identity
            when {
                !unchanged -> callback(null, MissionCommandError("mission-identity-changed"))
                error != null -> callback(null, MissionCommandError(error.description()))
                else -> callback(info?.toMissionBreakpointOrNull(), null)
            }
        }
        return MissionCancellation { active.set(false) }
    }

    override fun pause(callback: (MissionCommandError?) -> Unit): MissionCancellation {
        val active = AtomicBoolean(true)
        executor.pauseMission { error ->
            if (active.compareAndSet(true, false)) {
                callback(error?.let { MissionCommandError(it.description()) })
            }
        }
        return MissionCancellation { active.set(false) }
    }

    override fun resume(
        breakpoint: MissionBreakpoint,
        callback: (MissionCommandError?) -> Unit,
    ): MissionCancellation {
        val active = AtomicBoolean(true)
        executor.resumeMission(breakpoint.toDjiBreakpoint()) { error ->
            if (active.compareAndSet(true, false)) {
                callback(error?.let { MissionCommandError(it.description()) })
            }
        }
        return MissionCancellation { active.set(false) }
    }

    override fun observeState(listener: (ObservedMissionState) -> Unit): MissionCancellation {
        val remove = executor.observeMissionState { listener(it.toObservedState()) }
        return MissionCancellation(remove)
    }

    private fun WaypointMissionExecuteState?.toObservedState(): ObservedMissionState = when (this) {
        WaypointMissionExecuteState.IDLE -> ObservedMissionState.IDLE
        WaypointMissionExecuteState.READY -> ObservedMissionState.READY
        WaypointMissionExecuteState.EXECUTING -> ObservedMissionState.EXECUTING
        WaypointMissionExecuteState.INTERRUPTED -> ObservedMissionState.INTERRUPTED
        WaypointMissionExecuteState.RECOVERING -> ObservedMissionState.RECOVERING
        WaypointMissionExecuteState.FINISHED -> ObservedMissionState.FINISHED
        else -> ObservedMissionState.UNKNOWN
    }

    private fun BreakPointInfo.toMissionBreakpointOrNull(): MissionBreakpoint? {
        val waylineId = waylineID ?: return null
        val waypointId = waypointID ?: return null
        val progress = segmentProgress?.takeIf(Double::isFinite) ?: return null
        return MissionBreakpoint(
            waylineId = waylineId,
            waypointId = waypointId,
            segmentProgress = progress,
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitude = location?.altitude,
            recoverAction = recoverActionType?.name,
        )
    }

    private fun MissionBreakpoint.toDjiBreakpoint(): BreakPointInfo {
        val info = BreakPointInfo(waylineId, waypointId, segmentProgress)
        if (latitude != null && longitude != null && altitude != null) {
            info.location = LocationCoordinate3D(latitude, longitude, altitude)
        }
        recoverAction?.let { value ->
            info.recoverActionType = RecoverActionType.valueOf(value)
        }
        return info
    }

    companion object {
        private val NO_ACTIVE_STATES = setOf(
            ObservedMissionState.IDLE,
            ObservedMissionState.READY,
            ObservedMissionState.FINISHED,
        )
    }
}

data class MissionHoldToken(
    val identity: MissionIdentity,
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

/**
 * Converts DJI's callback/state split into coalesced suspend operations.
 *
 * This class intentionally owns no timeout. The fire coordinator owns the
 * operation deadline so cancellation can flow here and unregister observation
 * listeners. A DJI callback cannot be physically removed once submitted, so
 * the port cancellation handle must at least revoke local delivery.
 */
class AwaitableMissionControl(
    private val port: MissionControlPort,
    private val hover: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val safetyGate: FlightSafetyGate = FlightSafetyGate(),
) : AutoCloseable {
    private val lock = Any()
    private var pauseOperation: SharedOperation<MissionHoldResult>? = null
    private var resumeOperation: SharedOperation<MissionResumeResult>? = null
    private var heldToken: MissionHoldToken? = null
    private var resumedToken: MissionHoldToken? = null
    private var failedResume: Pair<MissionHoldToken, MissionResumeResult.ManualHold>? = null
    private val issuedResumeTokens = mutableSetOf<MissionHoldToken>()
    private var manualTakeover = false
    private var closed = false

    suspend fun pause(): MissionHoldResult {
        val operation = synchronized(lock) {
            if (closed) return MissionHoldResult.ManualHold(FlightSafetyReason.CONTROL_CLOSED)
            if (manualTakeover) {
                return MissionHoldResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER)
            }
            heldToken?.takeIf {
                port.snapshot() == MissionSnapshot(it.identity, ObservedMissionState.INTERRUPTED)
            }?.let { return MissionHoldResult.WaylinePaused(it) }
            pauseOperation?.also { it.waiters++ } ?: newPauseOperation()
        }
        return awaitShared(operation) {
            if (manualTakeover) {
                MissionHoldResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER)
            } else {
                throw it
            }
        }
    }

    suspend fun resume(
        token: MissionHoldToken,
        context: ResumeSafetyContext,
    ): MissionResumeResult {
        val immediateSafety = safetyGate.evaluateResume(context)
        if (immediateSafety is ResumeSafetyDecision.ManualHold) {
            return MissionResumeResult.ManualHold(immediateSafety.reason)
        }
        val operation = synchronized(lock) {
            if (closed) return MissionResumeResult.ManualHold(FlightSafetyReason.CONTROL_CLOSED)
            if (manualTakeover) {
                return MissionResumeResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER)
            }
            failedResume?.takeIf { it.first == token }?.let { return it.second }
            resumedToken?.takeIf {
                it == token &&
                    port.snapshot() == MissionSnapshot(token.identity, ObservedMissionState.EXECUTING)
            }?.let { return MissionResumeResult.Resumed }
            resumeOperation?.let {
                if (it.key != token) {
                    return MissionResumeResult.ManualHold(FlightSafetyReason.COMPETING_FIRE_SESSION)
                }
                it.waiters++
                it
            } ?: newResumeOperation(token, context)
        }
        return awaitShared(operation) {
            if (manualTakeover) {
                MissionResumeResult.ManualHold(FlightSafetyReason.MANUAL_CONTROL_TAKEOVER)
            } else {
                throw it
            }
        }
    }

    fun onManualControlTakeover() {
        val operations = synchronized(lock) {
            manualTakeover = true
            listOfNotNull(pauseOperation?.deferred, resumeOperation?.deferred)
        }
        operations.forEach { it.cancel(CancellationException("manual-control-takeover")) }
    }

    override fun close() {
        val operations = synchronized(lock) {
            if (closed) return
            closed = true
            listOfNotNull(pauseOperation?.deferred, resumeOperation?.deferred)
        }
        operations.forEach { it.cancel(CancellationException("mission-control-closed")) }
    }

    private fun newPauseOperation(): SharedOperation<MissionHoldResult> {
        val deferred = scope.async(start = CoroutineStart.LAZY) { performPause() }
        val operation = SharedOperation(deferred, waiters = 1, key = null)
        pauseOperation = operation
        deferred.invokeOnCompletion {
            synchronized(lock) {
                if (pauseOperation === operation) pauseOperation = null
                (deferred.getCompletedOrNull() as? MissionHoldResult.WaylinePaused)?.let {
                    heldToken = it.token
                    resumedToken = null
                }
            }
        }
        deferred.start()
        return operation
    }

    private fun newResumeOperation(
        token: MissionHoldToken,
        context: ResumeSafetyContext,
    ): SharedOperation<MissionResumeResult> {
        val deferred = scope.async(start = CoroutineStart.LAZY) { performResume(token, context) }
        val operation = SharedOperation(deferred, waiters = 1, key = token)
        resumeOperation = operation
        deferred.invokeOnCompletion {
            synchronized(lock) {
                if (resumeOperation === operation) resumeOperation = null
                when (val result = deferred.getCompletedOrNull()) {
                    MissionResumeResult.Resumed -> {
                        resumedToken = token
                        heldToken = null
                    }
                    is MissionResumeResult.ManualHold -> {
                        if (token in issuedResumeTokens) {
                            // A submitted DJI resume is one-shot. Any terminal
                            // callback/state failure is retained for this exact
                            // token so repeated calls cannot submit it again.
                            failedResume = token to result
                        }
                    }
                    null -> Unit
                }
            }
        }
        deferred.start()
        return operation
    }

    private suspend fun performPause(): MissionHoldResult {
        val snapshot = port.snapshot()
        val identity = snapshot.identity
        if (identity == null) {
            return if (snapshot.state in PROVABLY_NO_ACTIVE_WAYLINE) {
                runCatching { hover() }.fold(
                    onSuccess = { MissionHoldResult.HoveringNoWayline },
                    onFailure = {
                        MissionHoldResult.ManualHold(FlightSafetyReason.HOVER_COMMAND_FAILED)
                    },
                )
            } else {
                MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
            }
        }
        if (snapshot.state !in setOf(ObservedMissionState.EXECUTING, ObservedMissionState.INTERRUPTED)) {
            return MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        }
        val breakpoint = awaitBreakpoint(identity)
            ?: return MissionHoldResult.ManualHold(FlightSafetyReason.MISSING_BREAKPOINT)
        val token = MissionHoldToken(identity, breakpoint)
        if (snapshot.state == ObservedMissionState.INTERRUPTED) {
            return MissionHoldResult.WaylinePaused(token)
        }
        val error = awaitCommandAndState(
            targetState = ObservedMissionState.INTERRUPTED,
            permittedStates = setOf(ObservedMissionState.EXECUTING, ObservedMissionState.INTERRUPTED),
            issue = port::pause,
        )
        return if (error == null) {
            MissionHoldResult.WaylinePaused(token)
        } else if (error.isUnexpectedState()) {
            MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        } else {
            MissionHoldResult.ManualHold(FlightSafetyReason.PAUSE_COMMAND_FAILED)
        }
    }

    private suspend fun performResume(
        token: MissionHoldToken,
        context: ResumeSafetyContext,
    ): MissionResumeResult {
        val snapshot = port.snapshot()
        if (snapshot.identity != token.identity) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH)
        }
        if (snapshot.state != ObservedMissionState.INTERRUPTED) {
            return MissionResumeResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        }
        val observedBreakpoint = awaitBreakpoint(token.identity)
            ?: return MissionResumeResult.ManualHold(FlightSafetyReason.MISSING_BREAKPOINT)
        val currentSafety = safetyGate.evaluateResume(
            context.copy(
                expectedMission = token.identity,
                observedMission = snapshot.identity,
                expectedBreakpoint = token.breakpoint,
                observedBreakpoint = observedBreakpoint,
                missionStateKnown = snapshot.state != ObservedMissionState.UNKNOWN,
            ),
        )
        if (currentSafety is ResumeSafetyDecision.ManualHold) {
            return MissionResumeResult.ManualHold(currentSafety.reason)
        }
        synchronized(lock) {
            issuedResumeTokens += token
        }
        val error = awaitCommandAndState(
            targetState = ObservedMissionState.EXECUTING,
            permittedStates = setOf(
                ObservedMissionState.INTERRUPTED,
                ObservedMissionState.RECOVERING,
                ObservedMissionState.EXECUTING,
            ),
        ) { callback -> port.resume(token.breakpoint, callback) }
        return if (error == null) {
            MissionResumeResult.Resumed
        } else if (error.isUnexpectedState()) {
            MissionResumeResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE)
        } else {
            MissionResumeResult.ManualHold(FlightSafetyReason.RESUME_COMMAND_FAILED)
        }
    }

    private suspend fun awaitBreakpoint(identity: MissionIdentity): MissionBreakpoint? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = AtomicReference<MissionCancellation>()
            continuation.invokeOnCancellation { cancellation.get()?.cancel() }
            val handle = port.queryBreakpoint(identity) { breakpoint, error ->
                if (continuation.isActive) {
                    continuation.resume(if (error == null) breakpoint else null)
                }
            }
            cancellation.set(handle)
            if (!continuation.isActive) handle.cancel()
        }

    private suspend fun awaitCommandAndState(
        targetState: ObservedMissionState,
        permittedStates: Set<ObservedMissionState>,
        issue: ((MissionCommandError?) -> Unit) -> MissionCancellation,
    ): MissionCommandError? = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val callbackSucceeded = AtomicBoolean(false)
        val stateObserved = AtomicBoolean(port.snapshot().state == targetState)
        val listenerHandle = AtomicReference<MissionCancellation>()
        val commandHandle = AtomicReference<MissionCancellation>()

        fun cleanup() {
            listenerHandle.get()?.cancel()
            commandHandle.get()?.cancel()
        }

        fun finish(error: MissionCommandError?) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                if (continuation.isActive) continuation.resume(error)
            }
        }

        fun maybeFinish() {
            if (callbackSucceeded.get() && stateObserved.get()) finish(null)
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) cleanup()
        }
        val listener = port.observeState { state ->
            if (state == targetState) {
                stateObserved.set(true)
                maybeFinish()
            } else if (state !in permittedStates) {
                finish(MissionCommandError("unexpected-mission-state:${state.name}"))
            }
        }
        listenerHandle.set(listener)
        if (finished.get()) listener.cancel()

        val command = runCatching {
            issue { error ->
                if (error != null) {
                    finish(error)
                } else {
                    callbackSucceeded.set(true)
                    maybeFinish()
                }
            }
        }.getOrElse {
            finish(MissionCommandError("command-exception:${it::class.java.simpleName}"))
            MissionCancellation {}
        }
        commandHandle.set(command)
        if (finished.get()) command.cancel()
        maybeFinish()
    }

    private suspend fun <T> awaitShared(
        operation: SharedOperation<T>,
        cancellationResult: (CancellationException) -> T,
    ): T {
        try {
            return operation.deferred.await()
        } catch (cancelled: CancellationException) {
            return cancellationResult(cancelled)
        } finally {
            synchronized(lock) {
                operation.waiters--
                if (operation.waiters == 0 && !operation.deferred.isCompleted) {
                    operation.deferred.cancel(CancellationException("all-callers-cancelled"))
                }
            }
        }
    }

    private data class SharedOperation<T>(
        val deferred: Deferred<T>,
        var waiters: Int,
        val key: Any?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> Deferred<T>.getCompletedOrNull(): T? =
        if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null

    private fun MissionCommandError.isUnexpectedState(): Boolean =
        reason.startsWith("unexpected-mission-state:")

    companion object {
        private val PROVABLY_NO_ACTIVE_WAYLINE = setOf(
            ObservedMissionState.IDLE,
            ObservedMissionState.READY,
            ObservedMissionState.FINISHED,
        )
    }
}
