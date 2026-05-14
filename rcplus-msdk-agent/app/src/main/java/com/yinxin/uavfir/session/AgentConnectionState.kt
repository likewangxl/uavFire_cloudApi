package com.yinxin.uavfir.session

enum class AgentConnectionState {
    IDLE,
    SDK_READY,
    AIRCRAFT_CONNECTED,
    CAPABILITY_READY,
    STREAMING,
    DEGRADED,
    ERROR,
}
