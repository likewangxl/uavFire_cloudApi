package com.yinxin.uavfir.wayline

import android.util.Log
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.BreakPointInfo
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
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
) {

    interface Listener {
        fun onState(missionId: String?, msdk: WaypointMissionExecuteState, previous: WaypointMissionExecuteState?)
        fun onProgress(missionId: String?, info: WaylineExecutingInfo)
        fun onError(missionId: String?, stage: String, error: IDJIError)
    }

    private val activeMissionId = AtomicReference<String?>(null)
    private val lastState = AtomicReference<WaypointMissionExecuteState?>(null)

    private val stateListener = WaypointMissionExecuteStateListener { newState ->
        val previous = lastState.getAndSet(newState)
        listener.onState(activeMissionId.get(), newState, previous)
    }

    private val progressListener = WaylineExecutingInfoListener { info ->
        listener.onProgress(activeMissionId.get(), info)
    }

    fun attach() {
        WaypointMissionManager.getInstance().addWaypointMissionExecuteStateListener(stateListener)
        WaypointMissionManager.getInstance().addWaylineExecutingInfoListener(progressListener)
    }

    fun detach() {
        WaypointMissionManager.getInstance().removeWaypointMissionExecuteStateListener(stateListener)
        WaypointMissionManager.getInstance().removeWaylineExecutingInfoListener(progressListener)
    }

    fun pushKmz(missionId: String, kmzPath: String, onComplete: (Boolean, IDJIError?) -> Unit) {
        Log.i(TAG, "pushKmz missionId=$missionId path=$kmzPath")
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

    fun stopMission(missionFileName: String) {
        WaypointMissionManager.getInstance().stopMission(missionFileName, simpleCallback(activeMissionId.get(), "stopMission"))
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
        }

        override fun onFailure(error: IDJIError) {
            listener.onError(missionId, stage, error)
        }
    }

    companion object {
        private const val TAG = "WaypointMissionExecutor"
    }
}
