package com.yinxin.uavfir.wayline

import android.util.Log
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin wrapper around [WaylineAgentApi]. The agent serves a single physical
 * drone at a time, but the droneSn is only known after MSDK enumerates the
 * connected aircraft — so droneSn is a method parameter, mirroring the
 * existing dual-stream `CommandPollingCoordinator` pattern.
 *
 * JWT tokens are cached per drone and cleared on 401/403 so the next call
 * re-issues.
 */
class WaylineAgentClient(
    private val api: WaylineAgentApi,
    private val sharedSecret: String,
) {
    private val tokenByDrone = ConcurrentHashMap<String, String>()

    /**
     * Returns a valid agent JWT for the given drone, fetching a fresh one if
     * we haven't issued one yet. Public so the command router can use the
     * same token to download KMZ via [WaylineKmzDownloader].
     */
    suspend fun ensureToken(droneSn: String): String {
        tokenByDrone[droneSn]?.let { return it }
        val resp = api.issueToken(WaylineAgentTokenRequest(droneSn, sharedSecret))
        val fresh = resp.data?.token ?: throw IllegalStateException("token endpoint returned empty body")
        tokenByDrone[droneSn] = fresh
        return fresh
    }

    fun invalidateToken(droneSn: String) {
        tokenByDrone.remove(droneSn)
    }

    suspend fun pollCommand(droneSn: String): WaylineAgentCommand? {
        return try {
            api.pollCommand(ensureToken(droneSn), droneSn)?.data
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                Log.w(TAG, "token rejected for $droneSn, clearing for retry")
                invalidateToken(droneSn)
            }
            null
        }
    }

    suspend fun ack(droneSn: String, tid: String, result: Int, output: String? = null) {
        runCatching {
            api.ackCommand(ensureToken(droneSn), droneSn, WaylineAgentCommandAck(tid, result, output))
        }.onFailure { Log.w(TAG, "ack failed for tid=$tid", it) }
    }

    companion object {
        private const val TAG = "WaylineAgentClient"
    }
}
