package com.yinxin.uavfir.api

data class AgentStatusRequest(
    val droneSn: String,
    val connectionState: String,
    val message: String,
    val liveStatus: String,
    val currentMode: String,
    val visibleState: String? = null,
    val thermalState: String? = null,
    val statusReason: String? = null,
    val playbackStatus: String? = null,
    val visiblePlayUrl: String? = null,
    val thermalPlayUrl: String? = null,
)
