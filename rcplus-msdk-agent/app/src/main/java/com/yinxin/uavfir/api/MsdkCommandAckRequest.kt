package com.yinxin.uavfir.api

data class MsdkCommandAckRequest(
    val commandId: String,
    val status: String,
    val message: String? = null,
)
