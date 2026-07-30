package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.BuildConfig

internal object AgentSdkHealthState {
    val tracker = AgentSdkHealthTracker(
        buildId = BuildConfig.AGENT_BUILD_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )
}
