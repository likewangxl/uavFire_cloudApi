package com.yinxin.uavfir.api

import com.yinxin.uavfir.firedetection.store.FireReportTransport
import com.yinxin.uavfir.firedetection.store.OutboxRow
import com.yinxin.uavfir.firedetection.store.SendOutcome
import com.yinxin.uavfir.firedetection.store.sha256
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonParser
import retrofit2.HttpException

/** Sends the exact durable Outbox JSON. Identity is never rebuilt here. */
class AgentFireReportTransport(
    private val api: DualStreamApi,
    private val tokenProvider: suspend (droneSn: String) -> String,
) : FireReportTransport {
    override suspend fun send(row: OutboxRow): SendOutcome {
        if (sha256(row.payload) != row.payloadSha256) {
            return SendOutcome.PayloadConflict("durable-payload-hash-mismatch")
        }
        val droneSn = try {
            JsonParser.parseString(row.payload).asJsonObject.get("droneSn").asString
                .also { require(it.isNotBlank()) }
        } catch (invalid: RuntimeException) {
            return SendOutcome.PayloadConflict("durable-drone-identity-missing")
        }
        val response = try {
            val token = tokenProvider(droneSn)
            api.reportAgentFire(token, row.payload.toRequestBody(JSON))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            return SendOutcome.TransientFailure(io.message ?: "network-io")
        } catch (http: HttpException) {
            return if (http.code() in TRANSIENT_HTTP) {
                SendOutcome.TransientFailure("token-http-${http.code()}")
            } else {
                SendOutcome.PayloadConflict("token-http-${http.code()}")
            }
        } catch (malformed: RuntimeException) {
            return SendOutcome.PayloadConflict("malformed-response:${malformed.javaClass.simpleName}")
        }
        val code = response.code()
        if (!response.isSuccessful) response.errorBody()?.close()
        if (code in TRANSIENT_HTTP) return SendOutcome.TransientFailure("http-$code")
        if (code == 409) return SendOutcome.PayloadConflict("http-409")
        if (!response.isSuccessful) return SendOutcome.PayloadConflict("http-$code")

        val responseText = try {
            response.body()?.string()
        } catch (io: IOException) {
            return SendOutcome.TransientFailure(io.message ?: "response-io")
        } ?: return SendOutcome.PayloadConflict("empty-success-response")
        val body = try {
            backendGson().fromJson(responseText, AgentFireReportResponse::class.java)
        } catch (malformed: RuntimeException) {
            return SendOutcome.PayloadConflict("malformed-success-response")
        } ?: return SendOutcome.PayloadConflict("empty-success-response")
        if (body.eventId != row.eventId || body.acceptedSequence != row.sequence) {
            return SendOutcome.PayloadConflict("response-identity-mismatch")
        }
        if (body.eventPersisted != true || body.notificationQueued == null) {
            return SendOutcome.PayloadConflict("response-not-durably-committed")
        }
        val duplicate = body.duplicate?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
        }?.asBoolean ?: return SendOutcome.PayloadConflict("response-duplicate-invalid")
        return if (duplicate) SendOutcome.ExactDuplicate else SendOutcome.Acknowledged
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val TRANSIENT_HTTP = setOf(408, 425, 429) + (500..599)
    }
}
