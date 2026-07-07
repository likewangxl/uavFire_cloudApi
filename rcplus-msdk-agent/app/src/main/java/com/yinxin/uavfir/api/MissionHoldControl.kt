package com.yinxin.uavfir.api

interface MissionHoldControl {
    /** Pause the active wayline; false means there is no active route and confirmation can continue. */
    suspend fun holdForConfirmation(): Boolean

    /** Resume the paired wayline hold. Implementations should be idempotent and log resume failures. */
    suspend fun resumeAfterConfirmation()
}
