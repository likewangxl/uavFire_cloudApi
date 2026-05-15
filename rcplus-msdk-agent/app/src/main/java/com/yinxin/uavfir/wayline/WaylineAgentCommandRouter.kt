package com.yinxin.uavfir.wayline

import android.util.Log
import com.yinxin.uavfir.api.CommandPoller

/**
 * Routes commands fetched from `WaylineAgentClient.pollCommand()` into the
 * MSDK executor. The HTTP ack happens immediately (skeleton always result=0);
 * actual execution results flow back through MQTT events via the executor's
 * Listener.
 *
 * Skeleton: KMZ download is NOT yet wired — when wayline_dispatch arrives,
 * we currently log and ack with result=2001 (NOT_IMPLEMENTED). Wire this to
 * a real downloader + executor.pushKmz once the http-download path is ready.
 */
class WaylineAgentCommandRouter(
    private val client: WaylineAgentClient,
    private val executor: WaypointMissionExecutor,
) : CommandPoller {

    override suspend fun pollOnce(droneSn: String) {
        val cmd = client.pollCommand(droneSn) ?: return
        Log.i(TAG, "received method=${cmd.method} tid=${cmd.tid} drone=$droneSn")

        when (cmd.method) {
            WaylineAgentMethod.DISPATCH -> {
                // TODO: download cmd.data["kmz_url"], md5-verify against cmd.data["kmz_md5"],
                // then executor.pushKmz(missionId, localPath, ...).
                client.ack(droneSn, cmd.tid, RESULT_NOT_IMPLEMENTED, "dispatch download path not wired")
            }
            WaylineAgentMethod.PAUSE -> {
                executor.pauseMission()
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            WaylineAgentMethod.RESUME -> {
                executor.resumeMission()
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            WaylineAgentMethod.STOP -> {
                executor.stopActiveMission()
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            WaylineAgentMethod.QUERY_BREAKPOINT -> {
                executor.queryActiveBreakpoint { _, err ->
                    // TODO: forward breakpoint info via MQTT event
                    Log.i(TAG, "breakpoint result err=${err?.errorCode()}")
                }
                client.ack(droneSn, cmd.tid, RESULT_OK)
            }
            else -> {
                client.ack(droneSn, cmd.tid, RESULT_UNKNOWN_METHOD, "unknown method ${cmd.method}")
            }
        }
    }

    companion object {
        private const val TAG = "WaylineAgentRouter"
        private const val RESULT_OK = 0
        private const val RESULT_NOT_IMPLEMENTED = 2001
        private const val RESULT_UNKNOWN_METHOD = 2002
    }
}
