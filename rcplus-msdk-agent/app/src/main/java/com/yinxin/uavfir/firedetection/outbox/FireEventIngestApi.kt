package com.yinxin.uavfir.firedetection.outbox

import com.google.gson.JsonParseException
import com.google.gson.Gson
import com.yinxin.uavfir.api.AgentApiEnvelope
import com.yinxin.uavfir.api.DualStreamEventRequest
import com.yinxin.uavfir.api.backendGson
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface FireEventIngestApi {
    @POST("/manage/api/v1/dual-stream/tasks/{taskId}/agent-fire-events")
    suspend fun ingest(
        @Path("taskId") taskId: String,
        @Body body: DualStreamEventRequest,
    ): AgentApiEnvelope<FireEventIngestReceipt>
}

data class FireEventIngestReceipt(
    val eventId: String,
    val status: String,
    val reason: String? = null,
)

class RetrofitFireEventSender(
    private val api: FireEventIngestApi,
    private val gson: Gson = backendGson(),
) : FireEventSender {
    override suspend fun send(entry: FireEventOutboxEntry): FireEventDeliveryResult {
        val request = try {
            gson.fromJson(entry.payloadJson, DualStreamEventRequest::class.java)
                ?: return FireEventDeliveryResult.Rejected("invalid-local-payload")
        } catch (error: JsonParseException) {
            return FireEventDeliveryResult.Rejected("invalid-local-payload:${error.message}")
        }
        return try {
            val receipt = api.ingest(entry.taskId, request).data
                ?: return FireEventDeliveryResult.Retryable("missing-business-receipt")
            if (receipt.eventId != entry.eventId) {
                return FireEventDeliveryResult.Retryable("receipt-event-id-mismatch:${receipt.eventId}")
            }
            when (receipt.status.lowercase()) {
                "accepted", "duplicate" -> FireEventDeliveryResult.Delivered(receipt.status.lowercase())
                "rejected" -> FireEventDeliveryResult.Rejected(receipt.reason ?: "backend-rejected")
                else -> FireEventDeliveryResult.Retryable("unknown-receipt-status:${receipt.status}")
            }
        } catch (error: HttpException) {
            val code = error.code()
            // Only a committed business receipt can finish an outbox record. HTTP failures,
            // including 404 during a rolling backend deployment, remain retryable.
            FireEventDeliveryResult.Retryable("http-$code:${error.message()}")
        } catch (error: Exception) {
            FireEventDeliveryResult.Retryable(
                "${error.javaClass.simpleName}:${error.message ?: "request-failed"}",
            )
        }
    }
}
