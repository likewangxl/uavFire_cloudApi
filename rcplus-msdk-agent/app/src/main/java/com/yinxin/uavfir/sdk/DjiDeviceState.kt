package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.session.AgentConnectionState

data class DjiDeviceState(
    val connectionState: AgentConnectionState,
    val capability: CameraCapability? = null,
)
