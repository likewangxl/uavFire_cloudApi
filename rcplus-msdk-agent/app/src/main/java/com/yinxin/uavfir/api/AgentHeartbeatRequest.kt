package com.yinxin.uavfir.api

data class AgentHeartbeatRequest(
    val droneSn: String,
    val connectionState: String,
    val sessionState: String,
)
