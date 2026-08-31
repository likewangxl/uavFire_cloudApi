package com.yinxin.uavfir.api

data class AgentHeartbeatRequest(
    val droneSn: String,
    val connectionState: String,
    val sessionState: String,
    val fireEventOutboxPendingCount: Int = 0,
    val fireEventOutboxOldestPendingAt: Long? = null,
    val fireEventOutboxLastError: String? = null,
)
