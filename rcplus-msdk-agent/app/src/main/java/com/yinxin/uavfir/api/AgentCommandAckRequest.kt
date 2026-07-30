package com.yinxin.uavfir.api

data class AgentCommandAckRequest(
    val commandId: String,
    val status: String,
    val message: String? = null,
    val taskId: String? = null,
    val sourceTs: Long? = null,
    val thermalTemperature: Double? = null,
    val thermalMeasureRoi: Map<String, Double>? = null,
    val eventId: String? = null,
    val fireLat: Double? = null,
    val fireLng: Double? = null,
    val fireAlt: Double? = null,
    val geoMethod: String? = null,
    val geoQuality: String? = null,
    val geoErrorRadiusM: Double? = null,
)
