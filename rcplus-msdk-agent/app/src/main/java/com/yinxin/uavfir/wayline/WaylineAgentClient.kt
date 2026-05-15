package com.yinxin.uavfir.wayline

import android.util.Log
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicReference

class WaylineAgentClient(
    private val api: WaylineAgentApi,
    private val droneSn: String,
    private val sharedSecret: String,
) {
    private val token = AtomicReference<String?>(null)

    suspend fun ensureToken(): String {
        token.get()?.let { return it }
        val resp = api.issueToken(WaylineAgentTokenRequest(droneSn, sharedSecret))
        val fresh = resp.data?.token ?: throw IllegalStateException("token endpoint returned empty body")
        token.set(fresh)
        return fresh
    }

    suspend fun pollCommand(): WaylineAgentCommand? {
        return try {
            api.pollCommand(ensureToken(), droneSn)?.data
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                Log.w(TAG, "token rejected, clearing for retry")
                token.set(null)
            }
            null
        }
    }

    suspend fun ack(tid: String, result: Int, output: String? = null) {
        runCatching {
            api.ackCommand(ensureToken(), droneSn, WaylineAgentCommandAck(tid, result, output))
        }.onFailure { Log.w(TAG, "ack failed for tid=$tid", it) }
    }

    companion object {
        private const val TAG = "WaylineAgentClient"
    }
}
