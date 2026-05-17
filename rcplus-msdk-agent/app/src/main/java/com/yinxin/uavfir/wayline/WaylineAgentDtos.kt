package com.yinxin.uavfir.wayline

data class WaylineAgentTokenRequest(
    val droneSn: String,
    val sharedSecret: String,
)

data class WaylineAgentTokenResponse(
    val token: String,
    val expiresIn: Long,
)

data class WaylineAgentCommand(
    val tid: String,
    val bid: String?,
    val timestamp: Long,
    val method: String,
    val data: Map<String, Any?>?,
)

data class WaylineAgentCommandAck(
    val tid: String,
    val result: Int,
    val output: String? = null,
)

data class WaylineDispatchData(
    val missionId: String,
    val kmzUrl: String,
    val kmzFilename: String?,
    val kmzMd5: String?,
    val waylineIds: List<Int>?,
    val rthAltitude: Double?,
)

data class WaylineControlData(
    val missionId: String,
)

object WaylineAgentMethod {
    const val DISPATCH = "wayline_dispatch"
    const val PAUSE = "wayline_pause"
    const val RESUME = "wayline_resume"
    const val STOP = "wayline_stop"
    const val QUERY_BREAKPOINT = "wayline_query_breakpoint"
}
