package com.yinxin.uavfir

import android.app.Application
import android.util.Log
import com.yinxin.uavfir.api.AgentBackendConfig
import com.yinxin.uavfir.api.AgentBackendApiFactory
import com.yinxin.uavfir.api.AgentBackendClient
import com.yinxin.uavfir.api.AgentReporter
import com.yinxin.uavfir.api.AgentRuntimeLoop
import com.yinxin.uavfir.api.CommandPollingCoordinator
import com.yinxin.uavfir.api.CompositeCommandPoller
import com.yinxin.uavfir.sdk.DjiDeviceSession
import com.yinxin.uavfir.sdk.DjiSdkGatewayImpl
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.RealMsdkStreamProvider
import com.yinxin.uavfir.ui.ValidationConsoleController
import com.yinxin.uavfir.wayline.WaylineAgentApi
import com.yinxin.uavfir.wayline.WaylineAgentClient
import com.yinxin.uavfir.wayline.WaylineAgentCommandRouter
import com.yinxin.uavfir.wayline.WaypointMissionExecutor
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
    private val dualStreamPoller = CommandPollingCoordinator(backendClient, sessionManager)

    // Wayline-agent control plane: poll /wayline-agent commands and route to
    // the MSDK WaypointMissionManager wrapper. MSDK init is shared with the
    // dual-stream side; only the executor's listeners attach independently.
    private val waylineApi = AgentBackendApiFactory.create(WaylineAgentApi::class.java)
    private val waylineClient = WaylineAgentClient(
        api = waylineApi,
        sharedSecret = WAYLINE_AGENT_SHARED_SECRET,
    )
    private val waypointExecutorListener = object : WaypointMissionExecutor.Listener {
        override fun onState(
            missionId: String?,
            msdk: dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState,
            previous: dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState?,
        ) {
            Log.i(TAG, "wayline state mission=$missionId msdk=$msdk previous=$previous")
            // TODO: publish wayline_state_change via WaylineMqttPublisher
        }

        override fun onProgress(
            missionId: String?,
            info: dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo,
        ) {
            Log.d(TAG, "wayline progress mission=$missionId wayline=${info.waylineID} waypoint=${info.currentWaypointIndex}")
            // TODO: publish wayline_progress via WaylineMqttPublisher
        }

        override fun onError(
            missionId: String?,
            stage: String,
            error: dji.v5.common.error.IDJIError,
        ) {
            Log.w(TAG, "wayline error mission=$missionId stage=$stage err=${error.errorCode()} desc=${error.description()}")
            // TODO: publish wayline_state_change with error via WaylineMqttPublisher
        }
    }
    private val waypointExecutor = WaypointMissionExecutor(waypointExecutorListener)
    private val waylineRouter = WaylineAgentCommandRouter(waylineClient, waypointExecutor)

    private val commandPoller = CompositeCommandPoller(listOf(dualStreamPoller, waylineRouter))

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
        waypointExecutor.attach()
    }

    fun shutdown() {
        waypointExecutor.detach()
        runtimeLoop.stop()
        appScope.cancel()
    }

    companion object {
        private const val TAG = "AppServices"
        // TODO: load from BuildConfig / encrypted preferences before production
        private const val WAYLINE_AGENT_SHARED_SECRET = "change-me-in-production"
    }
}
