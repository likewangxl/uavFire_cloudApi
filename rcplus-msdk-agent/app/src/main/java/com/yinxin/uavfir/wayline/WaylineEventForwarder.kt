package com.yinxin.uavfir.wayline

import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridge between MSDK's synchronous WaypointMissionExecutor.Listener and the
 * MQTT publisher. Listener callbacks fire on whatever thread MSDK uses; we
 * hop to [dispatcher] (defaults to IO) before doing blocking Paho publish.
 *
 * State changes are throttled? — no, the MSDK only fires on transition.
 * Progress firings can be frequent (~1Hz from MSDK), so each onProgress
 * publishes one MQTT message; consider rate-limiting if the broker / mobile
 * uplink can't keep up.
 */
class WaylineEventForwarder(
    private val publisher: WaylineMqttPublisher,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WaypointMissionExecutor.Listener {

    override fun onState(
        missionId: String?,
        msdk: WaypointMissionExecuteState,
        previous: WaypointMissionExecuteState?,
    ) {
        Log.i(TAG, "state mission=$missionId msdk=$msdk previous=$previous")
        scope.launch(dispatcher) {
            publisher.publishEvent(
                method = "wayline_state_change",
                payload = mapOf(
                    "mission_id" to missionId,
                    "msdk_state" to msdk.name,
                    "previous_msdk_state" to previous?.name,
                ),
            )
        }
    }

    override fun onProgress(missionId: String?, info: WaylineExecutingInfo) {
        scope.launch(dispatcher) {
            publisher.publishEvent(
                method = "wayline_progress",
                payload = mapOf(
                    "mission_id" to missionId,
                    "mission_file_name" to info.missionFileName,
                    "wayline_id" to info.waylineID,
                    "current_waypoint_index" to info.currentWaypointIndex,
                ),
            )
        }
    }

    override fun onError(missionId: String?, stage: String, error: IDJIError) {
        Log.w(TAG, "stage=$stage err=${error.errorCode()} desc=${error.description()}")
        scope.launch(dispatcher) {
            publisher.publishEvent(
                method = "wayline_state_change",
                payload = mapOf(
                    "mission_id" to missionId,
                    "msdk_state" to "ERROR",
                    "error" to "$stage:${error.errorCode()}:${error.description()}",
                ),
            )
        }
    }

    /**
     * Publish `wayline_dispatch_result` for a dispatch outcome (download / push / start).
     * Fires asynchronously on the forwarder's IO dispatcher; callers should not block on it.
     */
    fun publishDispatchResult(missionId: String, result: Int, reason: String? = null, msdkMissionFileName: String? = null) {
        scope.launch(dispatcher) {
            publisher.publishEvent(
                method = "wayline_dispatch_result",
                payload = mapOf(
                    "mission_id" to missionId,
                    "result" to result,
                    "reason" to reason,
                    "msdk_mission_file_name" to msdkMissionFileName,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "WaylineEventForwarder"
    }
}
