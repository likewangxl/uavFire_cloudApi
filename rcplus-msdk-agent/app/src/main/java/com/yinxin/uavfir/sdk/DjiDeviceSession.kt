package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.session.AgentConnectionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DjiDeviceSessionAdapter {
    suspend fun initialize(): DjiDeviceState
}

class DjiDeviceSession(
    private val djiSdkGateway: DjiSdkGateway,
) : DjiDeviceSessionAdapter {
    private val initializeMutex = Mutex()

    override suspend fun initialize(): DjiDeviceState {
        return initializeMutex.withLock {
            if (!djiSdkGateway.initialize()) {
                return@withLock DjiDeviceState(connectionState = AgentConnectionState.ERROR)
            }

            if (!djiSdkGateway.isAircraftConnected()) {
                return@withLock DjiDeviceState(connectionState = AgentConnectionState.SDK_READY)
            }

            DjiDeviceState(
                connectionState = AgentConnectionState.CAPABILITY_READY,
                capability = djiSdkGateway.loadCapability(),
            )
        }
    }
}
