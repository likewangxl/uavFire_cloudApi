package com.yinxin.uavfir.api

data class AgentCommandResponse(
    val commandId: String,
    val droneSn: String,
    val action: String,
    val status: String,
    val message: String? = null,
)
