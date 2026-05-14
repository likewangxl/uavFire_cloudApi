package com.yinxin.uavfir.api

import com.yinxin.uavfir.BuildConfig

data class AgentBackendConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = BuildConfig.AGENT_BACKEND_BASE_URL
    }
}
