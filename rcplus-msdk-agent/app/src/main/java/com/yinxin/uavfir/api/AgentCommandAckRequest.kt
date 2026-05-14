package com.yinxin.uavfir.api

data class AgentCommandAckRequest(
    val commandId: String,
    val status: String,
    val message: String? = null,
)
