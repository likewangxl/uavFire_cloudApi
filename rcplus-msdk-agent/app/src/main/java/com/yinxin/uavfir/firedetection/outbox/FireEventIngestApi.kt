package com.yinxin.uavfir.firedetection.outbox

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.yinxin.uavfir.api.AgentApiEnvelope
import com.yinxin.uavfir.api.DualStreamEventRequest
import com.yinxin.uavfir.api.backendGson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.UUID

interface FireEventIngestApi {
    @Multipart
    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/fire-evidence")
    suspend fun uploadEvidence(
        @Header("x-agent-token") token: String,
        @Header("x-agent-timestamp") timestamp: String,
        @Header("x-agent-nonce") nonce: String,
        @Path("droneSn") droneSn: String,
        @Part("event_id") eventId: RequestBody,
        @Part("sha256") sha256: RequestBody,
        @Part("captured_at") capturedAt: RequestBody,
        @Part file: MultipartBody.Part,
    ): AgentApiEnvelope<FireEvidenceUploadReceipt>

    @POST("/manage/api/v1/dual-stream/tasks/{taskId}/agent-fire-events")
    suspend fun ingest(
        @Header("x-agent-token") token: String,
        @Header("x-agent-timestamp") timestamp: String,
        @Header("x-agent-nonce") nonce: String,
        @Path("taskId") taskId: String,
        @Body body: DualStreamEventRequest,
    ): AgentApiEnvelope<FireEventIngestReceipt>
}

data class FireEvidenceUploadReceipt(
    val eventId: String,
    val status: String,
    val visibleImageUrl: String,
    val evidenceSha256: String,
    val evidenceCapturedAt: Long,
)

data class FireEventIngestReceipt(
    val eventId: String,
    val status: String,
    val reason: String? = null,
)

class RetrofitFireEventSender(
    private val api: FireEventIngestApi,
    private val auth: FireEventAuthProvider,
    private val gson: Gson = backendGson(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) : FireEventSender {
    override suspend fun send(entry: FireEventOutboxEntry): FireEventDeliveryResult {
        val request = try {
            gson.fromJson(entry.payloadJson, DualStreamEventRequest::class.java)
                ?: return FireEventDeliveryResult.Rejected("invalid-local-payload")
        } catch (error: JsonParseException) {
            return FireEventDeliveryResult.Rejected("invalid-local-payload:${error.message}")
        }
        if (request.droneSn.isBlank() || request.taskId != entry.taskId) {
            return FireEventDeliveryResult.Rejected("invalid-local-identity")
        }
        return if (entry.evidenceUrl.isNullOrBlank()) {
            uploadEvidence(entry, request.droneSn)
        } else {
            sendEvent(entry, request)
        }
    }

    private suspend fun uploadEvidence(
        entry: FireEventOutboxEntry,
        droneSn: String,
    ): FireEventDeliveryResult {
        val jpeg = entry.evidenceJpeg
            ?: return FireEventDeliveryResult.Rejected("missing-local-evidence")
        if (entry.evidenceSha256.length != 64 || entry.evidenceCapturedAt <= 0) {
            return FireEventDeliveryResult.Rejected("invalid-local-evidence-metadata")
        }
        return try {
            val receipt = api.uploadEvidence(
                token = auth.token(droneSn),
                timestamp = clock().toString(),
                nonce = nonce(),
                droneSn = droneSn,
                eventId = entry.eventId.textPart(),
                sha256 = entry.evidenceSha256.textPart(),
                capturedAt = entry.evidenceCapturedAt.toString().textPart(),
                file = MultipartBody.Part.createFormData(
                    "file",
                    "${entry.eventId}.jpg",
                    jpeg.toRequestBody(JPEG_MEDIA_TYPE),
                ),
            ).data ?: return FireEventDeliveryResult.Retryable("missing-evidence-receipt")
            if (receipt.eventId != entry.eventId
                || !receipt.evidenceSha256.equals(entry.evidenceSha256, ignoreCase = true)
                || receipt.evidenceCapturedAt != entry.evidenceCapturedAt
                || receipt.status.lowercase() != "stored"
                || receipt.visibleImageUrl.isBlank()
            ) {
                FireEventDeliveryResult.Retryable("invalid-evidence-receipt")
            } else {
                FireEventDeliveryResult.EvidenceUploaded(receipt.visibleImageUrl)
            }
        } catch (error: HttpException) {
            retryHttp(error, droneSn)
        } catch (error: Exception) {
            FireEventDeliveryResult.Retryable(
                "${error.javaClass.simpleName}:${error.message ?: "evidence-upload-failed"}",
            )
        }
    }

    private suspend fun sendEvent(
        entry: FireEventOutboxEntry,
        request: DualStreamEventRequest,
    ): FireEventDeliveryResult {
        val authenticatedRequest = request.copy(
            visibleImageUrl = entry.evidenceUrl,
            evidenceSha256 = entry.evidenceSha256,
            evidenceCapturedAt = entry.evidenceCapturedAt,
            evidenceStatus = "UPLOADED",
        )
        return try {
            val receipt = api.ingest(
                token = auth.token(request.droneSn),
                timestamp = clock().toString(),
                nonce = nonce(),
                taskId = entry.taskId,
                body = authenticatedRequest,
            ).data ?: return FireEventDeliveryResult.Retryable("missing-business-receipt")
            if (receipt.eventId != entry.eventId) {
                return FireEventDeliveryResult.Retryable("receipt-event-id-mismatch:${receipt.eventId}")
            }
            when (receipt.status.lowercase()) {
                "accepted", "duplicate" -> FireEventDeliveryResult.Delivered(receipt.status.lowercase())
                "rejected" -> if (receipt.reason == "evidence-not-verified") {
                    uploadEvidence(entry, request.droneSn)
                } else {
                    FireEventDeliveryResult.Rejected(receipt.reason ?: "backend-rejected")
                }
                else -> FireEventDeliveryResult.Retryable("unknown-receipt-status:${receipt.status}")
            }
        } catch (error: HttpException) {
            retryHttp(error, request.droneSn)
        } catch (error: Exception) {
            FireEventDeliveryResult.Retryable(
                "${error.javaClass.simpleName}:${error.message ?: "request-failed"}",
            )
        }
    }

    private fun retryHttp(error: HttpException, droneSn: String): FireEventDeliveryResult {
        if (error.code() == 401 || error.code() == 403) auth.invalidate(droneSn)
        return FireEventDeliveryResult.Retryable("http-${error.code()}:${error.message()}")
    }

    private fun String.textPart() = toRequestBody(TEXT_MEDIA_TYPE)

    private companion object {
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        val TEXT_MEDIA_TYPE = "text/plain".toMediaType()
    }
}
