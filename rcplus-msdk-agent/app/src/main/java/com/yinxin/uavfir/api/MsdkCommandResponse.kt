package com.yinxin.uavfir.api

data class MsdkCommandResponse(
    val commandId: String,
    val aircraftSn: String,
    val command: String,
    val params: Map<String, Any?>? = null,
    val status: String,
    val message: String? = null,
)
