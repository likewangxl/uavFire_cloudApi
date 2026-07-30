package com.yinxin.uavfir.wayline

import android.util.Log
import com.yinxin.uavfir.api.GimbalActionClient
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.BreakPointInfo
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

internal class SerializedSnapshotObserver<T>(
    private val callback: (T) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<T>()
    private var draining = false
    private var active = true

    fun enqueue(value: T) {
        synchronized(lock) {
            if (active) pending.addLast(value)
        }
    }

    fun drain() {
        synchronized(lock) {
            if (!active || draining) return
            draining = true
        }
        while (true) {
            val next = synchronized(lock) {
                if (!active || pending.isEmpty()) {
                    draining = false
                    return
                }
                pending.removeFirst()
            }
            var failure: Throwable? = null
            try {
                callback(next)
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                if (failure != null) {
                    synchronized(lock) {
                        active = false
                        pending.clear()
                        draining = false
                    }
                }
            }
            when (failure) {
                is Error -> throw failure
                is Exception -> return
                null -> Unit
                else -> throw failure
            }
        }
    }

    fun deactivate() {
        synchronized(lock) {
            active = false
            pending.clear()
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active }
}

data class WaypointMissionIdentity(
    val missionId: String,
    val missionFileName: String,
)

data class MissionExecutionSnapshot(
    val identity: WaypointMissionIdentity?,
    val state: WaypointMissionExecuteState?,
    val missionGeneration: Long,
    val commandGeneration: Long,
)

data class MissionCommandSubmission(
    val snapshot: MissionExecutionSnapshot,
)

/**
 * Wraps DJI MSDK v5 [WaypointMissionManager]. State changes and progress are
 * delivered via the supplied [listener] so a publisher can forward them to the
 * backend over MQTT.
 *
 * Single active mission at a time (MSDK constraint). [activeMissionId] tracks
 * the id we last started so progress callbacks can be tagged.
 */
class WaypointMissionExecutor(
    private val listener: Listener,
    private val diagnostics: WaypointMissionDiagnostics = DjiWaypointMissionDiagnostics(),
    private val gimbalActionClient: GimbalActionClient? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    interface Listener {
        fun onState(missionId: String?, msdk: WaypointMissionExecuteState, previous: WaypointMissionExecuteState?)
        fun onProgress(missionId: String?, info: WaylineExecutingInfo)
        fun onStartAccepted(missionId: String?)
        fun onError(missionId: String?, stage: String, error: IDJIError)
    }

    private val missionLock = Any()
    private val executionSnapshot = AtomicReference(MissionExecutionSnapshot(null, null, 0, 0))
    private val missionObservers = linkedSetOf<SerializedSnapshotObserver<MissionExecutionSnapshot>>()

    fun activeMissionId(): String? = executionSnapshot.get().identity?.missionId
    fun activeMissionFileName(): String? = executionSnapshot.get().identity?.missionFileName
    fun currentMissionExecution(): MissionExecutionSnapshot = executionSnapshot.get()

    private val stateListener = WaypointMissionExecuteStateListener { newState ->
        val (transition, observersToDrain) = synchronized(missionLock) {
            val current = executionSnapshot.get()
            val terminal = newState in NO_ACTIVE_STATES
            val next = if (terminal && current.identity != null) {
                current.copy(
                    identity = null,
                    state = newState,
                    missionGeneration = current.missionGeneration + 1,
                )
            } else {
                current.copy(state = newState)
            }
            executionSnapshot.set(next)
            missionObservers.forEach { it.enqueue(next) }
            (current.state to current.identity?.missionId) to missionObservers.toList()
        }
        drainObservers(observersToDrain)
        listener.onState(transition.second, newState, transition.first)
    }

    private val progressListener = object : WaylineExecutingInfoListener {
        override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo) {
            listener.onProgress(activeMissionId(), info)
        }

        override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError) {
            Log.w(
                TAG,
                "wayline interrupted missionId=${activeMissionId()} reason=$error",
            )
        }
    }

    fun attach() {
        val manager = WaypointMissionManager.getInstance()
        manager.init()
        manager.addWaypointMissionExecuteStateListener(stateListener)
        manager.addWaylineExecutingInfoListener(progressListener)
        Log.i(TAG, "attach: wayline listeners registered")
    }

    /**
     * Re-register listeners after the aircraft (and MSDK product) is connected.
     * Listeners added at app start — before SDK registration / aircraft link —
     * never receive state/progress callbacks, so we detach and re-init/add once
     * the device identity is activated. Idempotent.
     */
    fun reattach() {
        detach()
        attach()
        Log.i(TAG, "reattach: wayline listeners re-registered after aircraft connect")
    }

    fun detach() {
        WaypointMissionManager.getInstance().removeWaypointMissionExecuteStateListener(stateListener)
        WaypointMissionManager.getInstance().removeWaylineExecutingInfoListener(progressListener)
    }

    fun pushKmz(missionId: String, kmzPath: String, onComplete: (Boolean, IDJIError?) -> Unit) {
        // 每次下发前强制重注册监听器：身份激活时的 reattach 只在 SN 变化时触发，若中途
        // 飞机重连/换电/重启，监听器会失效却不再注册 → MSDK 不回调 → 后端永远卡“执行中”。
        // 在上机推送 KMZ 前 reattach，保证当次任务的 state/progress 回调一定是活的。
        reattach()
        Log.i(TAG, "pushKmz missionId=$missionId path=$kmzPath")
        Log.i(
            TAG,
            WaypointMissionDiagnosticFormatter.formatValidationResult(
                missionId,
                kmzPath,
                diagnostics.validationErrors(kmzPath),
            ),
        )
        WaypointMissionManager.getInstance().pushKMZFileToAircraft(
            kmzPath,
            object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) {
                    Log.d(TAG, "pushKmz progress=$progress missionId=$missionId")
                }

                override fun onSuccess() {
                    onComplete(true, null)
                }

                override fun onFailure(error: IDJIError) {
                    listener.onError(missionId, "pushKmz", error)
                    onComplete(false, error)
                }
            },
        )
    }

    fun startMission(missionId: String, missionFileName: String, waylineIds: List<Int>?) {
        val observersToDrain = synchronized(missionLock) {
            val current = executionSnapshot.get()
            val next = current.copy(
                identity = WaypointMissionIdentity(missionId, missionFileName),
                state = null,
                missionGeneration = current.missionGeneration + 1,
            )
            executionSnapshot.set(next)
            missionObservers.forEach { it.enqueue(next) }
            missionObservers.toList()
        }
        drainObservers(observersToDrain)
        Log.i(
            TAG,
            WaypointMissionDiagnosticFormatter.formatAvailableWaylineIds(
                missionId,
                missionFileName,
                diagnostics.availableWaylineIds(missionFileName),
            ),
        )
        val callback = simpleCallback(missionId, "startMission")
        if (waylineIds.isNullOrEmpty()) {
            WaypointMissionManager.getInstance().startMission(missionFileName, callback)
        } else {
            WaypointMissionManager.getInstance().startMission(missionFileName, waylineIds, callback)
        }
    }

    fun pauseMission() = submitLegacyCommand(
        stage = "pauseMission",
    ) { callback ->
        WaypointMissionManager.getInstance().pauseMission(callback)
    }

    fun pauseMission(onComplete: (IDJIError?) -> Unit) = submitLegacyCommand(
        stage = "pauseMission",
        onComplete = onComplete,
    ) { callback ->
        WaypointMissionManager.getInstance().pauseMission(callback)
    }

    fun pauseMission(
        expected: MissionExecutionSnapshot,
        onSubmissionBoundary: (MissionExecutionSnapshot) -> Unit,
        onComplete: (MissionExecutionSnapshot, IDJIError?) -> Unit,
    ): MissionCommandSubmission? = submitCommand(
        expected,
        "pauseMission",
        onSubmissionBoundary,
        onComplete,
    ) { callback ->
        WaypointMissionManager.getInstance().pauseMission(callback)
    }

    fun resumeMission() = submitLegacyCommand(
        stage = "resumeMission",
    ) { callback ->
        WaypointMissionManager.getInstance().resumeMission(callback)
    }

    fun resumeMission(
        breakpoint: BreakPointInfo,
        onComplete: (IDJIError?) -> Unit,
    ) = submitLegacyCommand(
        stage = "resumeMission",
        onComplete = onComplete,
    ) { callback ->
        WaypointMissionManager.getInstance().resumeMission(breakpoint, callback)
    }

    fun resumeMission(
        expected: MissionExecutionSnapshot,
        breakpoint: BreakPointInfo,
        onSubmissionBoundary: (MissionExecutionSnapshot) -> Unit,
        onComplete: (MissionExecutionSnapshot, IDJIError?) -> Unit,
    ): MissionCommandSubmission? = submitCommand(
        expected,
        "resumeMission",
        onSubmissionBoundary,
        onComplete,
    ) { callback ->
        WaypointMissionManager.getInstance().resumeMission(breakpoint, callback)
    }

    fun observeMissionExecution(observer: (MissionExecutionSnapshot) -> Unit): () -> Unit {
        val registration = SerializedSnapshotObserver(observer)
        synchronized(missionLock) {
            missionObservers += registration
            registration.enqueue(executionSnapshot.get())
        }
        drainObservers(listOf(registration))
        return {
            synchronized(missionLock) { missionObservers -= registration }
            registration.deactivate()
        }
    }

    fun stopActiveMission() {
        val fileName = activeMissionFileName()
        if (fileName == null) {
            Log.w(TAG, "stopActiveMission called but no active mission")
            return
        }
        submitLegacyCommand(
            stage = "stopMission",
        ) { callback ->
            WaypointMissionManager.getInstance().stopMission(fileName, callback)
        }
    }

    fun queryActiveBreakpoint(onResult: (BreakPointInfo?, IDJIError?) -> Unit) {
        val fileName = activeMissionFileName()
        if (fileName == null) {
            Log.w(TAG, "queryActiveBreakpoint called but no active mission")
            onResult(null, null)
            return
        }
        queryBreakpoint(fileName, onResult)
    }

    fun queryBreakpoint(missionFileName: String, onResult: (BreakPointInfo?, IDJIError?) -> Unit) {
        WaypointMissionManager.getInstance().queryBreakPointInfoFromAircraft(
            missionFileName,
            object : CommonCallbacks.CompletionCallbackWithParam<BreakPointInfo> {
                override fun onSuccess(info: BreakPointInfo?) {
                    onResult(info, null)
                }

                override fun onFailure(error: IDJIError) {
                    listener.onError(activeMissionId(), "queryBreakpoint", error)
                    onResult(null, error)
                }
            },
        )
    }

    private fun simpleCallback(missionId: String?, stage: String) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {
            Log.d(TAG, "$stage success missionId=$missionId")
            if (stage == "startMission") {
                listener.onStartAccepted(missionId)
                tiltGimbalToNadir(missionId)
            }
        }

        override fun onFailure(error: IDJIError) {
            listener.onError(missionId, stage, error)
        }
    }

    private fun callback(
        missionId: String?,
        stage: String,
        onComplete: (IDJIError?) -> Unit,
    ) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {
            Log.d(TAG, "$stage success missionId=$missionId")
            onComplete(null)
        }

        override fun onFailure(error: IDJIError) {
            listener.onError(missionId, stage, error)
            onComplete(error)
        }
    }

    private fun submitLegacyCommand(
        stage: String,
        onComplete: (IDJIError?) -> Unit = {},
        submit: (CommonCallbacks.CompletionCallback) -> Unit,
    ) {
        checkNotNull(
            submitCommand(
                expected = null,
                stage = stage,
                onSubmissionBoundary = {},
                onComplete = { _, error -> onComplete(error) },
                submit = submit,
            ),
        )
    }

    private fun submitCommand(
        expected: MissionExecutionSnapshot?,
        stage: String,
        onSubmissionBoundary: (MissionExecutionSnapshot) -> Unit,
        onComplete: (MissionExecutionSnapshot, IDJIError?) -> Unit,
        submit: (CommonCallbacks.CompletionCallback) -> Unit,
    ): MissionCommandSubmission? {
        val (submitted, observersToDrain) = synchronized(missionLock) {
            val current = executionSnapshot.get()
            if (expected != null &&
                (current.identity != expected.identity ||
                    current.missionGeneration != expected.missionGeneration ||
                    current.commandGeneration != expected.commandGeneration ||
                    current.state != expected.state)
            ) {
                return@synchronized null
            }
            val next = current.copy(commandGeneration = current.commandGeneration + 1)
            executionSnapshot.set(next)
            missionObservers.forEach { it.enqueue(next) }
            next to missionObservers.toList()
        } ?: return null
        drainObservers(observersToDrain)
        val callback = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                onComplete(submitted, null)
            }

            override fun onFailure(error: IDJIError) {
                listener.onError(submitted.identity?.missionId, stage, error)
                onComplete(submitted, error)
            }
        }
        // This is the one-way submission boundary: after this notification the
        // caller must assume DJI may receive the command even if the SDK call
        // throws or the coroutine is cancelled.
        onSubmissionBoundary(submitted)
        submit(callback)
        return MissionCommandSubmission(submitted)
    }

    private fun drainObservers(
        observersToDrain: List<SerializedSnapshotObserver<MissionExecutionSnapshot>>,
    ) {
        try {
            observersToDrain.forEach { it.drain() }
        } finally {
            val inactive = observersToDrain.filterNot { it.isActive() }
            if (inactive.isNotEmpty()) {
                synchronized(missionLock) { missionObservers.removeAll(inactive.toSet()) }
            }
        }
    }

    private fun tiltGimbalToNadir(missionId: String?) {
        val client = gimbalActionClient ?: return
        scope.launch(dispatcher) {
            runCatching {
                client.rotateGimbalToPitch(NADIR_GIMBAL_PITCH_DEGREES)
            }.onSuccess {
                Log.i(TAG, "gimbal tilted to nadir missionId=$missionId pitch=$NADIR_GIMBAL_PITCH_DEGREES")
            }.onFailure { error ->
                Log.w(TAG, "gimbal nadir adjustment failed missionId=$missionId: ${error.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WaypointMissionExecutor"
        private const val NADIR_GIMBAL_PITCH_DEGREES: Double = -45.0
        private val NO_ACTIVE_STATES = setOf(
            WaypointMissionExecuteState.IDLE,
            WaypointMissionExecuteState.READY,
            WaypointMissionExecuteState.FINISHED,
            WaypointMissionExecuteState.DISCONNECTED,
        )
    }
}
