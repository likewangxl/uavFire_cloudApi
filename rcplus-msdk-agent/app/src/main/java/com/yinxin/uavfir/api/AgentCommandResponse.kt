package com.yinxin.uavfir.api

data class AgentCommandResponse(
    val commandId: String,
    val droneSn: String,
    val action: String,
    val status: String,
    val message: String? = null,
    val taskId: String? = null,
    val sourceTs: Long? = null,
    val thermalMeasureRoi: Map<String, Double>? = null,
    val urgent: Boolean? = null,
)
