package com.yinxin.uavfir.api

import com.google.gson.Gson
import com.yinxin.uavfir.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

interface ThermalSnapshotUploader {
    suspend fun upload(eventId: String, snapshotPath: String): String?
}

interface VisibleSnapshotConfirmer {
    suspend fun confirm(
        taskId: String,
        eventId: String,
        droneSn: String,
        sourceTs: Long,
        snapshotPath: String,
        thermalSourceEventId: String? = null,
        thermalImageUrl: String? = null,
    ): String?
}

class AiServiceThermalSnapshotUploader(
    private val baseUrl: String = BuildConfig.AGENT_SNAPSHOT_SERVICE_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = backendGson(),
) : ThermalSnapshotUploader {
    override suspend fun upload(eventId: String, snapshotPath: String): String? {
        val first = uploadOnce(eventId, snapshotPath, allowUnverified = false)
        if (first.url != null || !first.validationRejected) {
            return first.url
        }
        return uploadOnce(eventId, snapshotPath, allowUnverified = true).url
    }

    private fun uploadOnce(eventId: String, snapshotPath: String, allowUnverified: Boolean): UploadAttemptResult {
        val file = File(snapshotPath)
        if (!file.exists() || file.length() <= 0L) {
            return UploadAttemptResult(url = null, validationRejected = false)
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
        if (allowUnverified) {
            bodyBuilder.addFormDataPart("allow_unverified", "true")
        }
        val body = bodyBuilder
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody(JPEG_MEDIA_TYPE),
            )
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/snapshots/msdk-thermal/" + eventId)
            .post(body)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use UploadAttemptResult(
                    url = null,
                    validationRejected = response.code == 422 && !allowUnverified,
                )
            }
            val payload = response.body?.string().orEmpty()
            UploadAttemptResult(
                url = gson.fromJson(payload, UploadResponse::class.java)?.url,
                validationRejected = false,
            )
        }
    }

    private data class UploadResponse(
        val url: String?,
    )

    private data class UploadAttemptResult(
        val url: String?,
        val validationRejected: Boolean,
    )

    private companion object {
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}

class AiServiceVisibleSnapshotConfirmer(
    private val baseUrl: String = BuildConfig.AGENT_SNAPSHOT_SERVICE_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = backendGson(),
) : VisibleSnapshotConfirmer {
    override suspend fun confirm(
        taskId: String,
        eventId: String,
        droneSn: String,
        sourceTs: Long,
        snapshotPath: String,
        thermalSourceEventId: String?,
        thermalImageUrl: String?,
    ): String? {
        val file = File(snapshotPath)
        if (!file.exists() || file.length() <= 0L) {
            return null
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("task_id", taskId)
            .addFormDataPart("drone_sn", droneSn)
            .addFormDataPart("source_ts", sourceTs.toString())
        if (!thermalSourceEventId.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("thermal_source_event_id", thermalSourceEventId)
        }
        if (!thermalImageUrl.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("thermal_image_url", thermalImageUrl)
        }
        val body = bodyBuilder.addFormDataPart(
            "file",
            file.name,
            file.asRequestBody(JPEG_MEDIA_TYPE),
        ).build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/snapshots/msdk-visible/" + eventId)
            .post(body)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use null
            }
            val payload = response.body?.string().orEmpty()
            gson.fromJson(payload, VisibleUploadResponse::class.java)?.visibleImageUrl
        }
    }

    private data class VisibleUploadResponse(
        val visibleImageUrl: String?,
    )

    private companion object {
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
