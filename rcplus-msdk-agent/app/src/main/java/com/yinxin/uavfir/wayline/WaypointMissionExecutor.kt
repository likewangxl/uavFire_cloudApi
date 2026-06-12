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
import java.util.concurrent.atomic.AtomicReference

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

    private val activeMissionId = AtomicReference<String?>(null)
    private val activeMissionFileName = AtomicReference<String?>(null)
    private val lastState = AtomicReference<WaypointMissionExecuteState?>(null)

    fun activeMissionId(): String? = activeMissionId.get()
    fun activeMissionFileName(): String? = activeMissionFileName.get()

    private val stateListener = WaypointMissionExecuteStateListener { newState ->
        val previous = lastState.getAndSet(newState)
        listener.onState(activeMissionId.get(), newState, previous)
    }

    private val progressListener = WaylineExecutingInfoListener { info ->
        listener.onProgress(activeMissionId.get(), info)
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
        activeMissionId.set(missionId)
        activeMissionFileName.set(missionFileName)
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

    fun pauseMission() {
        WaypointMissionManager.getInstance().pauseMission(simpleCallback(activeMissionId.get(), "pauseMission"))
    }

    fun resumeMission() {
        WaypointMissionManager.getInstance().resumeMission(simpleCallback(activeMissionId.get(), "resumeMission"))
    }

    fun stopActiveMission() {
        val fileName = activeMissionFileName.get()
        if (fileName == null) {
            Log.w(TAG, "stopActiveMission called but no active mission")
            return
        }
        WaypointMissionManager.getInstance().stopMission(fileName, simpleCallback(activeMissionId.get(), "stopMission"))
    }

    fun queryActiveBreakpoint(onResult: (BreakPointInfo?, IDJIError?) -> Unit) {
        val fileName = activeMissionFileName.get()
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
                    listener.onError(activeMissionId.get(), "queryBreakpoint", error)
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
        private const val NADIR_GIMBAL_PITCH_DEGREES: Double = -90.0
    }
}
