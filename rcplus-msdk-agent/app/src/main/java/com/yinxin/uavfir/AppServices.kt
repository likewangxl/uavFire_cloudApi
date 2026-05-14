package com.yinxin.uavfir

import android.app.Application
import android.util.Log
import com.yinxin.uavfir.api.AgentBackendConfig
import com.yinxin.uavfir.api.AgentBackendApiFactory
import com.yinxin.uavfir.api.AgentBackendClient
import com.yinxin.uavfir.api.AgentReporter
import com.yinxin.uavfir.api.AgentRuntimeLoop
import com.yinxin.uavfir.api.CommandPollingCoordinator
import com.yinxin.uavfir.sdk.DjiDeviceSession
import com.yinxin.uavfir.sdk.DjiSdkGatewayImpl
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.RealMsdkStreamProvider
import com.yinxin.uavfir.ui.ValidationConsoleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppServices(
    application: Application,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = AgentBackendApiFactory.create()
    private val backendClient = AgentBackendClient(api)
    private val reporter = AgentReporter(backendClient)
    private val deviceSession = DjiDeviceSession(DjiSdkGatewayImpl())
    private val sessionManager = DualStreamSessionManager(RealMsdkStreamProvider())
    private val commandPoller = CommandPollingCoordinator(backendClient, sessionManager)

    val validationController = ValidationConsoleController(
        deviceSession = deviceSession,
        commandExecutor = sessionManager,
    )

    val runtimeLoop = AgentRuntimeLoop(
        deviceSession = deviceSession,
        reporter = reporter,
        commandPoller = commandPoller,
        sessionManager = sessionManager,
        scope = appScope,
        onError = { stage, throwable ->
            Log.e(TAG, "runtime loop $stage failed", throwable)
        },
    )

    init {
        Log.i(TAG, "initialized backend=${AgentBackendConfig.DEFAULT_BASE_URL}")
    }

    fun shutdown() {
        runtimeLoop.stop()
        appScope.cancel()
    }

    companion object {
        private const val TAG = "AppServices"
    }
}
