package com.yinxin.uavfir.api

data class AgentHeartbeatRequest(
    val droneSn: String,
    val connectionState: String,
    val sessionState: String,
    val detectorIntent: String = "DISARMED",
    val detectorState: String = "DISARMED",
    val detectorHealth: String = "HEALTHY",
    val detectorReason: String? = "operator-disarmed",
    val detectorIntentVersion: Long = 0L,
)
