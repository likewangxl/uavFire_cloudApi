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
    // 后端派的抵近命令参数被丢弃，agent 秒拒 fire-confirmation-params-required（2026-07-26 实飞）。
    val params: Map<String, Any?>? = null,
)
