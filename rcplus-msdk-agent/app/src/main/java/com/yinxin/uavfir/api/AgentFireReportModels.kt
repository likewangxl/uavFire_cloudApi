package com.yinxin.uavfir.api

import com.google.gson.annotations.SerializedName

data class AgentFireReportResponse(
    @SerializedName("eventId") val eventId: String?,
    @SerializedName("acceptedSequence") val acceptedSequence: Long?,
    @SerializedName("eventPersisted") val eventPersisted: Boolean?,
    @SerializedName("notificationQueued") val notificationQueued: Boolean?,
    @SerializedName("duplicate") val duplicate: Boolean = false,
)
